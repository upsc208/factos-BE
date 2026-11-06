package com.atleti.news.news.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.context.ActiveProfiles
import java.time.OffsetDateTime

/**
 * 팀 목록·번역 대기열 쿼리. 기사 하나가 여러 팀에 걸리는 구조라 JOIN 결과가 중복되지 않는지가 핵심이다.
 */
@DataJpaTest
@ActiveProfiles("h2")
class ClubNewsQueryTest {

    @Autowired
    private lateinit var repository: NewsArticleRepository

    @Autowired
    private lateinit var entityManager: TestEntityManager

    private val now = OffsetDateTime.now()

    @Test
    fun `팀별 목록은 그 팀이 걸린 기사만 최신순으로, 한 번씩만 돌려준다`() {
        repository.save(article("https://a", now.minusHours(3), Club.ARSENAL, Club.CHELSEA))
        repository.save(article("https://b", now.minusHours(1), Club.ARSENAL))
        repository.save(article("https://c", now.minusHours(2), Club.LIVERPOOL))
        entityManager.flush()
        entityManager.clear()

        val page = repository.findAllByClub(Club.ARSENAL, null, null, PageRequest.of(0, 10, Sort.by("publishedAt").descending()))

        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.content.map { it.originalUrl }).containsExactly("https://b", "https://a")
        assertThat(page.content.last().clubs).containsExactlyInAnyOrder(Club.ARSENAL, Club.CHELSEA)
    }

    @Test
    fun `저장된 기사에 팀을 더하면 다시 읽어도 남아 있다`() {
        repository.save(article("https://a", now, Club.ARSENAL))
        entityManager.flush()
        entityManager.clear()

        val loaded = repository.findAllByOriginalUrlIn(listOf("https://a")).single()
        entityManager.clear() // 수집 루프처럼 영속성 컨텍스트 밖에서 고친다
        loaded.clubs.add(Club.TOTTENHAM)
        repository.save(loaded)
        entityManager.flush()
        entityManager.clear()

        assertThat(repository.findAllByOriginalUrlIn(listOf("https://a")).single().clubs)
            .containsExactlyInAnyOrder(Club.ARSENAL, Club.TOTTENHAM)
    }

    @Test
    fun `번역 대기열은 PENDING만 최신순으로`() {
        repository.save(article("https://old", now.minusHours(5), Club.ARSENAL))
        repository.save(article("https://new", now.minusHours(1), Club.ARSENAL))
        repository.save(article("https://done", now, Club.ARSENAL).apply { translationStatus = TranslationStatus.DONE })
        entityManager.flush()
        entityManager.clear()

        val queue = repository.findTop20ByTranslationStatusOrderByPublishedAtDesc(TranslationStatus.PENDING)

        assertThat(queue.map { it.originalUrl }).containsExactly("https://new", "https://old")
    }

    @Test
    fun `출처 필터는 매체명과 기자 계정을 대소문자 상관없이 같이 거른다`() {
        repository.save(article("https://bbc", now, Club.ARSENAL, author = "BBC Sport"))
        repository.save(article("https://sky", now, Club.ARSENAL, author = "Sky Sports"))
        repository.save(article("https://x.com/David_Ornstein/status/1", now, Club.ARSENAL, author = "David_Ornstein"))
        entityManager.flush()
        entityManager.clear()

        val page = repository.findAllByClub(Club.ARSENAL, listOf("bbc sport", "david_ornstein"), null, PageRequest.of(0, 10))

        assertThat(page.content.map { it.originalUrl })
            .containsExactlyInAnyOrder("https://bbc", "https://x.com/David_Ornstein/status/1")
    }

    @Test
    fun `검색은 번역 전 영어 제목과 번역 후 한국어 요약 모두에서 찾는다`() {
        repository.save(article("https://en", now, Club.ARSENAL)) // titleEn = "Title https://en"
        repository.save(article("https://ko", now, Club.ARSENAL).apply {
            summaryKo = "사카가 훈련에 복귀했다"
            translationStatus = TranslationStatus.DONE
        })
        entityManager.flush()
        entityManager.clear()

        fun search(q: String) = repository.findAllByClub(Club.ARSENAL, null, q, PageRequest.of(0, 10)).content.map { it.originalUrl }

        assertThat(search("TITLE https://EN")).containsExactly("https://en")
        assertThat(search("훈련")).containsExactly("https://ko")
        assertThat(search("없는 단어")).isEmpty()
    }

    private fun article(url: String, publishedAt: OffsetDateTime, vararg clubs: Club, author: String = "BBC Sport") = NewsArticle(
        sourceName = "BBC Sport",
        authorName = author,
        originalUrl = url,
        titleKo = null,
        summaryKo = null,
        contentKo = null,
        contentRaw = "body",
        imageUrl = null,
        publishedAt = publishedAt,
        category = null,
        clubs = clubs.toMutableSet(),
        titleEn = "Title $url",
        keySentencesEn = "One.\nTwo.",
        translationStatus = TranslationStatus.PENDING,
    )
}
