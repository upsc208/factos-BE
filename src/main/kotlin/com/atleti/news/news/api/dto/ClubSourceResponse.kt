package com.atleti.news.news.api.dto

/**
 * 팀 페이지 출처 필터의 한 항목.
 *
 * [internalId]는 기사의 authorName과 같은 값이다 — 매체 기사는 매체명, 트윗은 기자 계정.
 * 그래서 목록 API의 `sources`에 그대로 넘기면 된다.
 */
data class ClubSourceResponse(
    val internalId: String,
    val displayName: String,
    val category: String, // "MEDIA" | "JOURNALIST"
) {
    companion object {
        // RssFeedReader.FEEDS의 sourceName과 같아야 한다
        val MEDIA = listOf(
            ClubSourceResponse("BBC Sport", "BBC Sport", "MEDIA"),
            ClubSourceResponse("Sky Sports", "Sky Sports", "MEDIA"),
        )

        fun journalist(handle: String) = ClubSourceResponse(handle, "@$handle", "JOURNALIST")
    }
}
