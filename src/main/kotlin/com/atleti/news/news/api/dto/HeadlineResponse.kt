package com.atleti.news.news.api.dto

import java.time.OffsetDateTime

/** 팀 브리핑: 최신순 최대 5줄. 프론트 경기 카드의 BriefingResponse와 같은 모양 */
data class BriefingResponse(
    val content: List<String>,
    val createdAt: OffsetDateTime?, // 가장 최근 줄이 만들어진 시각. 비었으면 null
)
