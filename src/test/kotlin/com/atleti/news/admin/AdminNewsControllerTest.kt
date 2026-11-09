package com.atleti.news.admin

import com.atleti.news.news.domain.ArticleDeletionService
import com.atleti.news.news.domain.ArticleType
import com.atleti.news.news.domain.CollectionLogRepository
import com.atleti.news.news.domain.NewsArticle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime

class AdminNewsControllerTest {

    private val deletion = mock(ArticleDeletionService::class.java)
    private val collectionLogRepository = mock(CollectionLogRepository::class.java)
    private val controller = AdminNewsController(deletion, collectionLogRepository)

    @Test
    fun `기사를 지운다`() {
        `when`(deletion.delete(7763L)).thenReturn(article())

        val response = controller.delete(7763L)

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body?.get("deleted")).isEqualTo(7763L)
    }

    @Test
    fun `없는 기사는 404로 답한다`() {
        `when`(deletion.delete(1L)).thenReturn(null)

        val response = controller.delete(1L)

        assertThat(response.statusCode.value()).isEqualTo(404)
    }

    private fun article() = NewsArticle(
        articleType = ArticleType.YOUTUBE,
        sourceName = "YouTube",
        authorName = "Ruben Uria",
        originalUrl = "https://www.youtube.com/watch?v=test",
        titleKo = "테스트 기사",
        summaryKo = null,
        contentKo = null,
        contentRaw = null,
        imageUrl = null,
        category = "라이브요약",
        isAtmRelated = true,
        publishedAt = OffsetDateTime.now(),
    )
}
