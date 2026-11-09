package com.atleti.news.match.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface MatchResultRepository : JpaRepository<MatchResult, Long> {
    // matchDate는 KST ISO 문자열로 저장해 문자열 정렬 = 시간 정렬
    fun findFirstByHomeTeamIdOrAwayTeamIdOrderByMatchDateDesc(homeTeamId: String, awayTeamId: String): MatchResult?
    fun findAllByOrderByMatchDateDesc(): List<MatchResult>
    fun existsByMatchId(matchId: String): Boolean
    fun findByMatchId(matchId: String): MatchResult?
}
