package com.atleti.news.rating.api.dto

data class PlayerRatingRequest(
    val club: String, // 어느 팀 선수를 평가하는지 (Club.id). 맞대결은 경기 하나에 두 팀이 있다
    val playerName: String,
    val score: Int
)
