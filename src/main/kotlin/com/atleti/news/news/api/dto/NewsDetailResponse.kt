package com.atleti.news.news.api.dto

import com.atleti.news.news.domain.ArticleType
import com.atleti.news.news.domain.TranslationStatus
import java.time.OffsetDateTime

/* 기사 상세 응답 DTO */
data class NewsDetailResponse(
    val id: Long,
    val titleKo: String?,
    val summaryKo: String?,
    val contentKo: String?,
    val imageUrl: String?,
    val sourceName: String,
    val authorName: String?,
    val originalUrl: String,
    val publishedAt: OffsetDateTime?,
    val articleType: ArticleType,
    val category: String?,
    val viewCount: Long,
    // ---- Big6(RSS·기자 트윗) 기사만. 기존 기사는 null ----
    val titleEn: String? = null,
    val keySentencesEn: List<String>? = null, // 번역 전에는 summaryKo가 비어 있어 이걸 보여 준다
    val translationStatus: TranslationStatus? = null,
)
