package com.atleti.news.member

import com.atleti.news.member.auth.AuthProperties
import com.atleti.news.member.auth.MemberAuth
import com.atleti.news.member.domain.AuthProvider
import com.atleti.news.member.domain.FavoriteTeam
import com.atleti.news.member.domain.Member
import com.atleti.news.member.domain.MemberRepository
import com.atleti.news.news.domain.Club
import com.atleti.news.news.domain.NewsArticle
import com.atleti.news.news.domain.NewsArticleRepository
import com.atleti.news.news.domain.TranslationStatus
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 로그인 쿠키 → 댓글·즐겨찾기 전체 흐름. 카카오·구글은 부르지 않고 쿠키를 직접 만들어 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
@TestPropertySource(properties = ["atleti.auth.token-secret=test-secret"])
class CommentBookmarkFlowTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var members: MemberRepository
    @Autowired private lateinit var articles: NewsArticleRepository
    @Autowired private lateinit var props: AuthProperties

    private lateinit var alice: Member
    private lateinit var bob: Member
    private var articleId: Long = 0

    @BeforeEach
    fun setUp() {
        // 테스트끼리 데이터가 겹치지 않게 매번 다른 값
        val suffix = UUID.randomUUID().toString()
        val tag = suffix.take(6)
        alice = members.save(Member(provider = AuthProvider.KAKAO, providerUserId = "a-$suffix", nickname = "앨리스$tag", favoriteTeam = FavoriteTeam.ARSENAL, signupCompleted = true))
        bob = members.save(Member(provider = AuthProvider.GOOGLE, providerUserId = "b-$suffix", nickname = "밥$tag", favoriteTeam = FavoriteTeam.OTHER, signupCompleted = true))
        articleId = articles.save(
            NewsArticle(
                sourceName = "BBC Sport", authorName = "BBC Sport", originalUrl = "https://bbc/$suffix",
                titleKo = null, summaryKo = null, contentKo = null, contentRaw = "body", imageUrl = null,
                publishedAt = OffsetDateTime.now(), category = null, clubs = mutableSetOf(Club.ARSENAL),
                titleEn = "Title", translationStatus = TranslationStatus.PENDING,
            )
        ).id!!
    }

    private fun MockHttpServletRequestBuilder.as_(member: Member) =
        cookie(Cookie(MemberAuth.SESSION_COOKIE, props.sessionTokens.issue(member.id!!)))

    private fun MockHttpServletRequestBuilder.fromFront() = header("Origin", "http://localhost:3000")

    private fun comment(text: String) = post("/api/news/$articleId/comments")
        .contentType(MediaType.APPLICATION_JSON).content("""{"content":"$text"}""")

    @Test
    fun `로그인 정보와 비로그인 401`() {
        mvc.perform(get("/api/auth/me").as_(alice)).andExpect(status().isOk).andExpect(jsonPath("$.nickname").value(alice.nickname))
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/auth/me").cookie(Cookie(MemberAuth.SESSION_COOKIE, "forged.token"))).andExpect(status().isUnauthorized)
    }

    @Test
    fun `댓글은 로그인해야 쓰고, 목록은 누구나 보며, 내 댓글에만 mine이 붙는다`() {
        mvc.perform(comment("안녕").fromFront()).andExpect(status().isUnauthorized)
        mvc.perform(comment("첫 댓글").fromFront().as_(alice)).andExpect(status().isCreated)
        mvc.perform(comment("둘째").fromFront().as_(bob)).andExpect(status().isCreated)

        mvc.perform(get("/api/news/$articleId/comments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(2))
            .andExpect(jsonPath("$.content[0].nickname").value(alice.nickname))
            .andExpect(jsonPath("$.content[0].mine").value(false))

        mvc.perform(get("/api/news/$articleId/comments").as_(alice))
            .andExpect(jsonPath("$.content[0].mine").value(true))
            .andExpect(jsonPath("$.content[1].mine").value(false))
    }

    @Test
    fun `허용 출처가 아닌 곳에서 온 쓰기 요청은 쿠키가 있어도 막는다`() {
        mvc.perform(comment("csrf").as_(alice)).andExpect(status().isForbidden) // Origin 없음
        mvc.perform(comment("csrf").as_(alice).header("Origin", "https://evil.com")).andExpect(status().isForbidden)
    }

    @Test
    fun `빈 댓글·너무 긴 댓글·연속 작성은 400`() {
        mvc.perform(comment("   ").fromFront().as_(alice)).andExpect(status().isBadRequest)
        mvc.perform(comment("가".repeat(501)).fromFront().as_(alice)).andExpect(status().isBadRequest)
        mvc.perform(comment("하나").fromFront().as_(alice)).andExpect(status().isCreated)
        mvc.perform(comment("바로 또").fromFront().as_(alice)).andExpect(status().isBadRequest)
    }

    @Test
    fun `남의 댓글은 못 지우고 내 댓글은 지운다`() {
        val body = mvc.perform(comment("지울 댓글").fromFront().as_(alice)).andReturn().response.contentAsString
        val commentId = Regex("\"id\":(\\d+)").find(body)!!.groupValues[1]

        mvc.perform(delete("/api/comments/$commentId").fromFront().as_(bob)).andExpect(status().isForbidden)
        mvc.perform(delete("/api/comments/$commentId").fromFront().as_(alice)).andExpect(status().isNoContent)
        mvc.perform(get("/api/news/$articleId/comments")).andExpect(jsonPath("$.content.length()").value(0))
    }

    @Test
    fun `즐겨찾기 추가·확인·목록·해제, 두 번 눌러도 하나`() {
        mvc.perform(put("/api/me/bookmarks/$articleId").fromFront()).andExpect(status().isUnauthorized)

        repeat(2) { mvc.perform(put("/api/me/bookmarks/$articleId").fromFront().as_(alice)).andExpect(status().isOk) }
        mvc.perform(get("/api/me/bookmarks/$articleId").as_(alice)).andExpect(jsonPath("$.bookmarked").value(true))
        mvc.perform(get("/api/me/bookmarks/$articleId").as_(bob)).andExpect(jsonPath("$.bookmarked").value(false))
        mvc.perform(get("/api/me/bookmarks").as_(alice))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].titleEn").value("Title"))

        mvc.perform(delete("/api/me/bookmarks/$articleId").fromFront().as_(alice)).andExpect(jsonPath("$.bookmarked").value(false))
        mvc.perform(get("/api/me/bookmarks").as_(alice)).andExpect(jsonPath("$.content.length()").value(0))
    }

    @Test
    fun `앱 키가 없으면 로그인 수단 목록이 비고 로그인 시작은 404`() {
        mvc.perform(get("/api/auth/providers")).andExpect(jsonPath("$.length()").value(0))
        mvc.perform(get("/api/auth/kakao/login")).andExpect(status().isNotFound)
    }
}
