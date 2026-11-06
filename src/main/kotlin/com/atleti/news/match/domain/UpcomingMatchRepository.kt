package com.atleti.news.match.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface UpcomingMatchRepository : JpaRepository<UpcomingMatch, Long> {
    fun findAllByOrderByMatchDateAsc(): List<UpcomingMatch>

    // Big6끼리 붙는 경기는 matchId 하나에 행이 두 개다 — 단건 조회는 First로 (findByMatchId는 두 행이면 예외)
    fun findFirstByMatchId(matchId: String): UpcomingMatch?

    /** 이 경기에 걸린 Big6 팀들을 알아낼 때 (보통 1개, 맞대결이면 2개) */
    fun findAllByMatchIdIn(matchIds: Collection<String>): List<UpcomingMatch>

    fun findAllByClubOrderByMatchDateAsc(club: String): List<UpcomingMatch>

    @Modifying
    @Query("DELETE FROM UpcomingMatch u WHERE u.club = :club")
    fun deleteAllByClub(@Param("club") club: String): Int

    // 아틀레티 시절 행 (club 없음) 정리용
    @Modifying
    @Query("DELETE FROM UpcomingMatch u WHERE u.club IS NULL")
    fun deleteAllWithoutClub(): Int
}
