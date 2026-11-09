package com.atleti.news.event

import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.news.domain.Club
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/** 다가올 경기 목록이 바뀔 때 어떤 이벤트가 나가는지 (MatchEvents.diff) */
class MatchEventsDiffTest {

    private val now = OffsetDateTime.parse("2026-11-20T12:00:00+09:00")

    private fun m(id: String, kickoff: String, tournament: String = "프리미어리그", home: Boolean = true, opponent: String = "Fulham") =
        UpcomingMatch(club = "arsenal", matchId = id, opponent = opponent, matchDate = kickoff, isHome = home, tournament = tournament)

    private fun diff(before: List<UpcomingMatch>, after: List<UpcomingMatch>) =
        MatchEvents.diff(Club.ARSENAL, before, after, now).map { it.type to it.payload.matchId }

    @Test
    fun `처음 보는 경기는 scheduled, 그대로면 아무것도 없다`() {
        val a = m("1", "2026-11-22T00:00:00+09:00")
        assertThat(diff(emptyList(), listOf(a))).containsExactly(MatchEvents.SCHEDULED to "1")
        assertThat(diff(listOf(a), listOf(m("1", "2026-11-22T00:00:00+09:00")))).isEmpty()
    }

    @Test
    fun `킥오프가 바뀌면 updated와 이전 시각을 함께 준다`() {
        val changes = MatchEvents.diff(
            Club.ARSENAL,
            listOf(m("1", "2026-11-22T00:00:00+09:00")),
            listOf(m("1", "2026-11-22T02:30:00+09:00")),
            now,
        )
        assertThat(changes).hasSize(1)
        assertThat(changes[0].type).isEqualTo(MatchEvents.UPDATED)
        assertThat(changes[0].payload.kickoff).isEqualTo("2026-11-22T02:30:00+09:00")
        assertThat(changes[0].payload.previousKickoff).isEqualTo("2026-11-22T00:00:00+09:00")
        assertThat(changes[0].payload.club).isEqualTo("arsenal")
    }

    @Test
    fun `대회만 바뀌어도 updated, 이때 previousKickoff는 없다`() {
        val changes = MatchEvents.diff(
            Club.ARSENAL,
            listOf(m("1", "2026-11-22T00:00:00+09:00", tournament = "리그컵")),
            listOf(m("1", "2026-11-22T00:00:00+09:00", tournament = "FA컵")),
            now,
        )
        assertThat(changes.single().type).isEqualTo(MatchEvents.UPDATED)
        assertThat(changes.single().payload.previousKickoff).isNull()
    }

    @Test
    fun `킥오프 전인데 창 안에서 빠지면 cancelled`() {
        val before = listOf(m("1", "2026-11-22T00:00:00+09:00"), m("2", "2026-11-25T05:00:00+09:00"), m("3", "2026-11-30T01:30:00+09:00"))
        val after = listOf(m("1", "2026-11-22T00:00:00+09:00"), m("3", "2026-11-30T01:30:00+09:00"), m("4", "2026-12-03T05:00:00+09:00"))
        assertThat(diff(before, after)).containsExactlyInAnyOrder(MatchEvents.CANCELLED to "2", MatchEvents.SCHEDULED to "4")
    }

    @Test
    fun `이미 킥오프한 경기가 빠지는 건 경기를 한 것이라 취소가 아니다`() {
        val before = listOf(m("0", "2026-11-19T21:00:00+09:00"), m("1", "2026-11-22T00:00:00+09:00"))
        val after = listOf(m("1", "2026-11-22T00:00:00+09:00"), m("2", "2026-11-25T05:00:00+09:00"))
        assertThat(diff(before, after)).containsExactly(MatchEvents.SCHEDULED to "2")
    }

    @Test
    fun `더 이른 경기가 끼어들어 창 밖으로 밀린 경기는 취소가 아니다`() {
        // 3경기만 보여 준다: 컵 경기(9)가 새로 잡혀 맨 뒤(3)가 밀려남
        val before = listOf(m("1", "2026-11-22T00:00:00+09:00"), m("2", "2026-11-25T05:00:00+09:00"), m("3", "2026-11-30T01:30:00+09:00"))
        val after = listOf(m("1", "2026-11-22T00:00:00+09:00"), m("9", "2026-11-24T04:00:00+09:00"), m("2", "2026-11-25T05:00:00+09:00"))
        assertThat(diff(before, after)).containsExactly(MatchEvents.SCHEDULED to "9")
    }

    @Test
    fun `새 목록이 비면 취소로 판단하지 않는다`() {
        assertThat(diff(listOf(m("1", "2026-11-22T00:00:00+09:00")), emptyList())).isEmpty()
    }
}
