package com.atleti.news.match.api.dto

import com.atleti.news.match.domain.CardInfo
import com.atleti.news.match.domain.FotmobPlayerRating
import com.atleti.news.match.domain.LineupInfo
import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.ScorerInfo
import com.atleti.news.match.domain.StatValue
import com.atleti.news.match.domain.SubstitutionInfo
import com.atleti.news.match.external.TournamentNameKo
import com.atleti.news.news.domain.Club
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class RecentMatch(
    val matchId: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeScore: Int,
    val awayScore: Int,
    val matchDate: String,
    val isHome: Boolean, // 요청한 팀 기준
    val tournament: String,
    val scorers: List<ScorerInfo>? = null,
    val lineup: LineupInfo? = null,
    val substitutions: List<SubstitutionInfo>? = null,
    val cards: List<CardInfo>? = null,
    val stats: Map<String, StatValue> = emptyMap(),
    val playerRatings: List<FotmobPlayerRating>? = null, // FotMob 평점, 양 팀
    val playerOfTheMatch: String? = null,
)

/**
 * 대회명 한국어 변환과 JSON 컬럼 해석. [club]을 주면 홈/원정을 그 팀 기준으로 다시 정한다 —
 * Big6 맞대결은 두 팀이 행 하나를 같이 쓰기 때문이다.
 */
fun MatchResult.toResponse(mapper: ObjectMapper, club: Club? = null) = RecentMatch(
    matchId = matchId,
    homeTeam = homeTeam,
    awayTeam = awayTeam,
    homeScore = homeScore,
    awayScore = awayScore,
    matchDate = matchDate,
    isHome = if (club != null && homeTeamId != null) homeTeamId == club.fotmobTeamId else isHome,
    tournament = TournamentNameKo.toKorean(tournament),
    scorers = jsonOrNull<List<ScorerInfo>>(mapper, scorers),
    lineup = jsonOrNull<LineupInfo>(mapper, lineup),
    substitutions = jsonOrNull<List<SubstitutionInfo>>(mapper, substitutions),
    cards = jsonOrNull<List<CardInfo>>(mapper, cards),
    stats = jsonOrNull<Map<String, StatValue>>(mapper, stats ?: xg) ?: emptyMap(),
    playerRatings = jsonOrNull<List<FotmobPlayerRating>>(mapper, playerRatings),
    playerOfTheMatch = playerOfTheMatch,
)

// 깨진 컬럼은 그 칸만 null 처리 — 경기 카드 전체를 잃지 않기 위함
private inline fun <reified T> jsonOrNull(mapper: ObjectMapper, raw: String?): T? =
    raw?.let { runCatching { mapper.readValue<T>(it) }.getOrNull() }
