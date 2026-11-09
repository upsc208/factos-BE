package com.atleti.news.rating.api.dto

import com.atleti.news.rating.domain.PlayerRating

data class PlayerRatingResponse(
    val playerName: String,
    val shirt: String?,
    val averageScore: Double,  // 팬 평점 0.0 ~ 5.0 (소수 1자리)
    val voteCount: Long,
    val myScore: Int?,         // 이 IP가 준 점수 (없으면 null)
    val fotmobRating: Double?, // FotMob 평점 0~10
    val starter: Boolean,
)

fun PlayerRating.toResponse() = PlayerRatingResponse(
    playerName = playerName,
    shirt = shirt,
    averageScore = averageScore,
    voteCount = voteCount,
    myScore = myScore,
    fotmobRating = fotmobRating,
    starter = starter,
)
