package com.atleti.news.news.domain

import com.atleti.news.news.domain.ClubNewsPipeline.Companion.applyTranslation
import com.atleti.news.news.domain.ClubNewsPipeline.Companion.mergeByUrl
import com.atleti.news.news.domain.ClubNewsPipeline.Companion.toPendingArticle
import com.atleti.news.news.domain.ClubNewsPipeline.Companion.translationInput
import com.atleti.news.news.external.ExtractedArticle
import com.atleti.news.news.external.RssItem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class ClubNewsPipelineTest {

    private val now = OffsetDateTime.parse("2026-11-05T12:00:00+09:00")
    private val since = now.minusHours(24)

    @Test
    fun `여러 팀 피드에 나온 같은 기사는 하나로 합치고 팀을 모은다`() {
        val url = "https://www.bbc.co.uk/sport/football/articles/abc"
        val merged = mergeByUrl(
            listOf(item(Club.ARSENAL, url), item(Club.CHELSEA, url), item(Club.LIVERPOOL, "https://other")),
            since,
        )

        assertThat(merged).hasSize(2)
        assertThat(merged.first { it.url == url }.clubs).containsExactlyInAnyOrder(Club.ARSENAL, Club.CHELSEA)
    }

    @Test
    fun `기준 시각보다 오래됐거나 발행 시각이 없는 기사는 버린다`() {
        val merged = mergeByUrl(
            listOf(
                item(Club.ARSENAL, "https://recent", now.minusHours(2)),
                item(Club.ARSENAL, "https://edge", since),
                item(Club.ARSENAL, "https://old", now.minusHours(25)),
                item(Club.ARSENAL, "https://undated", null),
            ),
            since,
        )

        assertThat(merged.map { it.url }).containsExactlyInAnyOrder("https://recent", "https://edge")
    }

    @Test
    fun `추출 결과로 PENDING 기사를 만든다 - 제목은 RSS 헤드라인을 쓴다`() {
        val candidate = Candidate(item(Club.MAN_CITY, "https://sky/1"), setOf(Club.MAN_CITY, Club.MAN_UTD))
        val article = toPendingArticle(
            candidate,
            ExtractedArticle(url = "https://sky/1", status = "ok", title = "Body title", body = "Full body", keySentences = listOf("One.", "Two.")),
        )

        assertThat(article.translationStatus).isEqualTo(TranslationStatus.PENDING)
        assertThat(article.titleEn).isEqualTo("Headline https://sky/1")
        assertThat(article.titleKo).isNull()
        assertThat(article.keySentencesEn).isEqualTo("One.\nTwo.")
        assertThat(article.contentRaw).isEqualTo("Full body")
        assertThat(article.clubs).containsExactlyInAnyOrder(Club.MAN_CITY, Club.MAN_UTD)
    }

    @Test
    fun `번역 요청은 제목 다음에 핵심 문장 순서이고, 결과도 같은 순서로 넣는다`() {
        val article = toPendingArticle(
            Candidate(item(Club.ARSENAL, "https://a"), setOf(Club.ARSENAL)),
            ExtractedArticle(url = "https://a", status = "ok", keySentences = listOf("First.", "Second.")),
        )

        assertThat(translationInput(article)).containsExactly("Headline https://a", "First.", "Second.")

        applyTranslation(article, listOf("헤드라인", "첫째.", "둘째."))
        assertThat(article.titleKo).isEqualTo("헤드라인")
        assertThat(article.summaryKo).isEqualTo("첫째.\n둘째.")
        assertThat(article.translationStatus).isEqualTo(TranslationStatus.DONE)
    }

    private fun item(club: Club, url: String, publishedAt: OffsetDateTime? = now.minusHours(1)) =
        RssItem(club = club, sourceName = "BBC Sport", url = url, title = "Headline $url", publishedAt = publishedAt, imageUrl = null)
}
