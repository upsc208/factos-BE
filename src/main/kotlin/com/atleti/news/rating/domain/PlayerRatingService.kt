package com.atleti.news.rating.domain

import com.atleti.news.match.domain.FotmobPlayerRating
import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.LineupInfo
import com.atleti.news.match.domain.PlayerInfo
import com.atleti.news.match.domain.SubstitutionInfo
import com.atleti.news.common.InvalidRequestException
import com.atleti.news.common.NotFoundException
import com.atleti.news.match.domain.MatchResultRepository
import com.atleti.news.news.domain.Club
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 팬 선수 평점 (로그인 없이 IP당 선수 한 표, 0~5점). 평가 대상은 **요청한 팀 쪽** 선수다 —
 * Big6 맞대결은 경기 하나에 두 팀이 있어 팀을 함께 받는다.
 */
@Service
class PlayerRatingService(
    private val matchResultRepository: MatchResultRepository,
    private val playerRatingVoteRepository: PlayerRatingVoteRepository,
    private val objectMapper: ObjectMapper
) {
    companion object {
        const val MIN_SCORE = 0
        const val MAX_SCORE = 5
    }

    private class Player(val name: String, val shirt: String?, val starter: Boolean, val fotmobRating: Double?)

    /** 이 경기에서 [club]이 홈이었는지. 그 팀 경기가 아니면 null */
    private fun clubIsHome(match: MatchResult, club: Club): Boolean? = when (club.fotmobTeamId) {
        match.homeTeamId -> true
        match.awayTeamId -> false
        else -> null
    }

    // 그 팀에서 뛴 선수 (선발 + 교체 투입). FotMob 평점을 붙인다
    private fun clubPlayers(match: MatchResult, isHome: Boolean): List<Player> {
        val ratings = match.playerRatings
            ?.let { runCatching { objectMapper.readValue<List<FotmobPlayerRating>>(it) }.getOrNull() }
            .orEmpty()
            .filter { it.isHome == isHome }
        val lineup = match.lineup?.let { runCatching { objectMapper.readValue<LineupInfo>(it) }.getOrNull() }
        val subs = match.substitutions
            ?.let { runCatching { objectMapper.readValue<List<SubstitutionInfo>>(it) }.getOrNull() }
            .orEmpty()

        val ratingByName = ratings.associateBy { it.name }
        val starters: List<PlayerInfo> = (if (isHome) lineup?.home else lineup?.away)?.starters.orEmpty()
        val subbedIn = subs.filter { it.isHome == isHome }.map { it.inName }

        // 이름 기준 중복 제거 (선발 정보 우선). 선발 명단을 못 받았으면 FotMob 평점 목록으로
        val ordered = LinkedHashMap<String, Player>()
        starters.forEach { p ->
            if (p.name.isNotBlank()) ordered.putIfAbsent(p.name, Player(p.name, p.shirt, true, ratingByName[p.name]?.rating))
        }
        subbedIn.forEach { name ->
            if (name.isNotBlank()) ordered.putIfAbsent(name, Player(name, ratingByName[name]?.shirt, false, ratingByName[name]?.rating))
        }
        if (ordered.isEmpty()) ratings.forEach { r -> ordered.putIfAbsent(r.name, Player(r.name, r.shirt, r.starter, r.rating)) }
        return ordered.values.toList()
    }

    fun getRatings(matchId: String, club: Club, ip: String): List<PlayerRating> {
        val match = matchResultRepository.findByMatchId(matchId) ?: return emptyList()
        val isHome = clubIsHome(match, club) ?: return emptyList()
        val aggMap = playerRatingVoteRepository.aggregateByMatch(matchId).associateBy { it.playerName }
        val myVotes = playerRatingVoteRepository.findByMatchIdAndIpAddress(matchId, ip).associate { it.playerName to it.score }

        return clubPlayers(match, isHome).map { p ->
            val agg = aggMap[p.name]
            PlayerRating(
                playerName = p.name,
                shirt = p.shirt,
                averageScore = agg?.average() ?: 0.0,
                voteCount = agg?.voteCount ?: 0L,
                myScore = myVotes[p.name],
                fotmobRating = p.fotmobRating,
                starter = p.starter,
            )
        }
    }

    @Transactional
    fun submitRating(matchId: String, club: Club, playerName: String, score: Int, ip: String): PlayerRating {
        if (score !in MIN_SCORE..MAX_SCORE) {
            throw InvalidRequestException("점수는 $MIN_SCORE~$MAX_SCORE 사이여야 합니다. (입력: $score)")
        }

        val match = matchResultRepository.findByMatchId(matchId)
            ?: throw NotFoundException("해당 경기를 찾을 수 없습니다: $matchId")
        val isHome = clubIsHome(match, club)
            ?: throw InvalidRequestException("${club.nameKo} 경기가 아닙니다: $matchId")

        // 실제로 그 경기에서 그 팀으로 뛴 선수만 평가 가능
        val player = clubPlayers(match, isHome).firstOrNull { it.name == playerName }
            ?: throw InvalidRequestException("이 경기에서 뛴 ${club.nameKo} 선수가 아닙니다: $playerName")

        val existing = playerRatingVoteRepository.findByMatchIdAndPlayerNameAndIpAddress(matchId, playerName, ip)
        if (existing != null) {
            // 재투표: 점수 수정
            existing.score = score
            existing.updatedAt = OffsetDateTime.now()
            playerRatingVoteRepository.save(existing)
        } else {
            playerRatingVoteRepository.save(
                PlayerRatingVote(
                    matchId = matchId,
                    playerName = playerName,
                    shirt = player.shirt,
                    ipAddress = ip,
                    score = score
                )
            )
        }

        // 저장 직후 평균 재계산해서 반환
        val agg = playerRatingVoteRepository.aggregateByMatch(matchId).firstOrNull { it.playerName == playerName }
        return PlayerRating(
            playerName = playerName,
            shirt = player.shirt,
            averageScore = agg?.average() ?: score.toDouble(),
            voteCount = agg?.voteCount ?: 1L,
            myScore = score,
            fotmobRating = player.fotmobRating,
            starter = player.starter,
        )
    }
}
