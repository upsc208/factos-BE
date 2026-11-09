package com.atleti.news.news.domain

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 팀 브리핑 5줄: 그 팀의 번역 끝난 기사만 Gemini에 주고, 중복을 걸러 팀마다 최신 5줄만 남긴다.
 * Gemini는 로컬 가짜 서버로 대신한다 (실제 API를 부르지 않는다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class ClubBriefingTest {

    companion object {
        @Volatile var lastPrompt: String = ""
        @Volatile var reply: List<String> = emptyList()

        private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { ex ->
                lastPrompt = ex.requestBody.readBytes().toString(Charsets.UTF_8)
                val headlines = reply.joinToString(",") { "\\\"$it\\\"" }
                val body = """{"candidates":[{"content":{"parts":[{"text":"{\"headlines\":[$headlines]}"}]}}]}"""
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun gemini(registry: DynamicPropertyRegistry) {
            registry.add("gemini.api.base-url") { "http://localhost:${server.address.port}" }
            registry.add("gemini.api.key") { "test-key" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var service: HeadlineService
    @Autowired private lateinit var headlines: NewsHeadlineRepository
    @Autowired private lateinit var articles: NewsArticleRepository

    private lateinit var tag: String

    @BeforeEach
    fun setUp() {
        headlines.deleteAll()
        tag = UUID.randomUUID().toString().take(6)
    }

    private fun article(club: Club, title: String, status: TranslationStatus = TranslationStatus.DONE) = articles.save(
        NewsArticle(
            sourceName = "BBC Sport", authorName = "BBC Sport", originalUrl = "https://bbc/${UUID.randomUUID()}",
            titleKo = title, summaryKo = "요약 $title", contentKo = null, contentRaw = "body", imageUrl = null,
            publishedAt = OffsetDateTime.now(), category = null, clubs = mutableSetOf(club),
            titleEn = "en", translationStatus = status,
        )
    )

    @Test
    fun `그 팀의 번역 끝난 기사만 넣고, 중복은 걸러 저장한다`() {
        article(Club.ARSENAL, "아스날기사$tag")
        article(Club.CHELSEA, "첼시기사$tag")
        article(Club.ARSENAL, "번역전$tag", TranslationStatus.PENDING)
        reply = listOf("아스날, 헐 꺾고 3연승", "아스날, 헐 꺾고 3연승", "주전 수비수 2주 결장")

        val added = runBlocking { service.generateFor(Club.ARSENAL) }

        assertThat(added).isEqualTo(2)
        assertThat(lastPrompt).contains("Arsenal (아스날)").contains("아스날기사$tag")
            .doesNotContain("첼시기사$tag").doesNotContain("번역전$tag")
        mvc.perform(get("/api/headlines/clubs/arsenal"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.createdAt").exists())
        mvc.perform(get("/api/headlines/clubs/chelsea"))
            .andExpect(jsonPath("$.content.length()").value(0))
            .andExpect(jsonPath("$.createdAt").doesNotExist())
    }

    @Test
    fun `팀마다 최신 5줄만 남기고, 다른 팀 줄은 건드리지 않는다`() {
        val earlier = OffsetDateTime.now().minusHours(2)
        (1..4).forEach { headlines.save(NewsHeadline(content = "예전 줄 $it", club = "arsenal", createdAt = earlier.plusMinutes(it.toLong()))) }
        headlines.save(NewsHeadline(content = "첼시 줄", club = "chelsea", createdAt = earlier))
        article(Club.ARSENAL, "새기사$tag")
        reply = listOf("새 줄 A", "새 줄 B")

        runBlocking { service.generateFor(Club.ARSENAL) }

        val kept = headlines.findAllByClubOrderByCreatedAtDesc("arsenal").map { it.content }
        assertThat(kept).hasSize(HeadlineService.MAX_HEADLINES)
        assertThat(kept).containsExactly("새 줄 B", "새 줄 A", "예전 줄 4", "예전 줄 3", "예전 줄 2") // 4 + 2 = 6 → 가장 오래된 1줄만 빠진다
        assertThat(headlines.findAllByClubOrderByCreatedAtDesc("chelsea")).hasSize(1)
        // 이미 올린 줄은 Gemini에 중복 방지용으로 넘긴다
        assertThat(lastPrompt).contains("예전 줄 4")
    }

    @Test
    fun `새 기사가 없으면 Gemini를 부르지 않는다`() {
        headlines.save(NewsHeadline(content = "방금 올린 줄", club = "liverpool"))
        lastPrompt = ""

        val added = runBlocking { service.generateFor(Club.LIVERPOOL) }

        assertThat(added).isZero()
        assertThat(lastPrompt).isEmpty()
    }
}
