package com.atleti.news.news.api.dto

import com.atleti.news.news.domain.ArticleType
import com.atleti.news.news.domain.ClubNewsPipeline
import com.atleti.news.news.domain.NewsArticle
import com.atleti.news.news.domain.TranslationStatus
import java.time.OffsetDateTime

/**
 * 팀별 기사 목록의 한 줄 (Big6 RSS 경로).
 *
 * 번역 전(PENDING)이면 [titleKo]가 null이고 [keySentences]는 영어 원문이다.
 * 어느 제목을 보여 줄지는 프론트가 [translationStatus]를 보고 정한다 — 번역 전이면 [titleEn].
 */
data class ClubNewsResponse(
    val id: Long,
    val clubs: List<String>, // Club.id ("arsenal", "mancity" …) — 같은 기사가 여러 팀에 걸릴 수 있다
    val titleKo: String?,
    val titleEn: String?,
    val keySentences: List<String>,
    val translationStatus: TranslationStatus,
    val articleType: ArticleType, // WEB_ARTICLE(BBC·Sky 기사) | SNS_POST(기자 트윗)
    val sourceName: String, // "BBC Sport", "Sky Sports", 트윗은 "X"
    val authorName: String?, // 트윗이면 기자 계정 (예: "David_Ornstein")
    val originalUrl: String,
    val imageUrl: String?,
    val publishedAt: OffsetDateTime?,
    val viewCount: Long,
) {
    companion object {
        fun of(article: NewsArticle): ClubNewsResponse {
            val translated = article.translationStatus == TranslationStatus.DONE
            return ClubNewsResponse(
                id = article.id!!,
                clubs = article.clubs.sorted().map { it.id },
                titleKo = article.titleKo,
                titleEn = article.titleEn,
                keySentences = if (translated) ClubNewsPipeline.splitSentences(article.summaryKo)
                               else ClubNewsPipeline.splitSentences(article.keySentencesEn),
                translationStatus = article.translationStatus ?: TranslationStatus.DONE,
                articleType = article.articleType,
                sourceName = article.sourceName,
                authorName = article.authorName,
                originalUrl = article.originalUrl,
                imageUrl = article.imageUrl,
                publishedAt = article.publishedAt,
                viewCount = article.viewCount,
            )
        }
    }
}
