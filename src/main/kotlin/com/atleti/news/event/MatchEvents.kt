package com.atleti.news.event

import com.atleti.news.match.domain.MatchKickoff
import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.match.external.TournamentNameKo
import com.atleti.news.news.domain.Club
import java.time.OffsetDateTime

/**
 * 경기 이벤트 (Redis Stream `events:match`). 계약은 docs/events.md — travel-svc(킥오프 알림·직관 기록)가 받는다.
 *
 * 일정 이벤트는 **팀 기준**이다: 맞대결(아스날-첼시)은 두 팀 일정에 각각 있어 club만 다른 이벤트가 두 번 나간다.
 */
object MatchEvents {
    /** 우리가 새로 알게 된 다가올 경기 (일정 창 3경기 안에 들어옴). 소비자는 upsert로 받는다 */
    const val SCHEDULED = "match.scheduled"
    /** 킥오프 시각·대회·홈원정이 바뀜 */
    const val UPDATED = "match.updated"
    /** 킥오프 전인데 일정에서 빠짐 (연기·취소) */
    const val CANCELLED = "match.cancelled"
    /** 경기가 끝나 결과가 처음 저장됨 */
    const val FINISHED = "match.finished"

    data class Fixture(
        val matchId: String,
        val club: String,
        val opponent: String,
        val kickoff: String,          // ISO-8601 (KST 오프셋)
        val isHome: Boolean,
        val tournament: String,       // 한글 대회명 — 일정 API(UpcomingMatchResponse)와 같은 값
        val previousKickoff: String? = null, // UPDATED에서 시각이 바뀌었을 때만
    )

    data class Result(
        val matchId: String,
        val clubs: List<String>,      // 이 경기를 뛴 Big6 (맞대결이면 둘)
        val homeTeam: String,
        val awayTeam: String,
        val homeScore: Int,
        val awayScore: Int,
        val kickoff: String,
        val tournament: String,
    )

    data class Change(val type: String, val payload: Fixture)

    /**
     * 한 팀의 다가올 경기 목록이 [before]에서 [after]로 바뀔 때 나갈 이벤트.
     *
     * FotMob은 앞으로 3경기만 보여 주므로 "목록에서 빠짐"이 곧 취소는 아니다:
     * - 킥오프가 지나서 빠짐 → 경기를 한 것 (FINISHED가 따로 나간다)
     * - 더 이른 경기가 끼어들어 창 밖으로 밀림 → 아무 일 없음
     * 그래서 **킥오프 전이고, 새 목록의 마지막 경기보다 이른데도 빠진 경기**만 취소로 본다.
     * 새 목록이 비면(시즌 끝·일시 오류) 판단하지 않는다.
     */
    fun diff(club: Club, before: List<UpcomingMatch>, after: List<UpcomingMatch>, now: OffsetDateTime): List<Change> {
        val old = before.filter { it.matchId != null }.associateBy { it.matchId!! }
        val new = after.filter { it.matchId != null }.associateBy { it.matchId!! }
        val changes = mutableListOf<Change>()

        for ((id, m) in new) {
            val prev = old[id]
            when {
                prev == null -> changes += Change(SCHEDULED, fixture(club, m))
                prev.matchDate != m.matchDate || prev.tournament != m.tournament || prev.isHome != m.isHome ->
                    changes += Change(UPDATED, fixture(club, m, previousKickoff = prev.matchDate.takeIf { it != m.matchDate }))
            }
        }

        val windowEnd = new.values.mapNotNull { MatchKickoff.parse(it.matchDate) }.maxOrNull()
        if (windowEnd != null) {
            for ((id, m) in old) {
                if (id in new) continue
                val kickoff = MatchKickoff.parse(m.matchDate) ?: continue
                if (kickoff.isAfter(now) && !kickoff.isAfter(windowEnd)) changes += Change(CANCELLED, fixture(club, m))
            }
        }
        return changes
    }

    fun result(m: MatchResult) = Result(
        matchId = m.matchId,
        clubs = listOfNotNull(m.homeTeamId, m.awayTeamId).mapNotNull { Club.fromFotmobId(it)?.id },
        homeTeam = m.homeTeam,
        awayTeam = m.awayTeam,
        homeScore = m.homeScore,
        awayScore = m.awayScore,
        kickoff = m.matchDate,
        tournament = TournamentNameKo.toKorean(m.tournament),
    )

    private fun fixture(club: Club, m: UpcomingMatch, previousKickoff: String? = null) = Fixture(
        matchId = m.matchId!!,
        club = club.id,
        opponent = m.opponent,
        kickoff = m.matchDate,
        isHome = m.isHome,
        tournament = TournamentNameKo.toKorean(m.tournament),
        previousKickoff = previousKickoff,
    )
}
