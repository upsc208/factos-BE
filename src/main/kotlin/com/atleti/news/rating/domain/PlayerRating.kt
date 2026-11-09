package com.atleti.news.rating.domain

/* 한 선수의 평점 현황 — 팬 투표 집계, 이 사람이 준 점수, FotMob 평점 */
data class PlayerRating(
    val playerName: String,
    val shirt: String?,
    val averageScore: Double,
    val voteCount: Long,
    val myScore: Int?,
    val fotmobRating: Double? = null, // 0~10. FotMob이 준 평점 (상세를 못 받았으면 null)
    val starter: Boolean = true,
)
