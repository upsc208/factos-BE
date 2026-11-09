package com.atleti.news.match.api

import com.atleti.news.match.api.dto.toResponse
import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.news.domain.Club
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 엔티티 → 응답 매핑. 대회명 한국어 변환과 JSON 컬럼 해석이 이 매핑에 들어 있어 조용히 빠지면 화면에서만 티가 난다.
 */
class MatchResponseMappingTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `경기 결과를 응답으로 옮긴다`() {
        val response = matchResult().toResponse(mapper, Club.ARSENAL)

        assertThat(response.matchId).isEqualTo("4321")
        assertThat(response.homeScore).isEqualTo(2)
        assertThat(response.tournament).isEqualTo("프리미어리그")
        assertThat(response.scorers?.map { it.name }).containsExactly("Bukayo Saka")
        assertThat(response.lineup?.home?.formation).isEqualTo("4-3-3")
        assertThat(response.substitutions?.first()?.inName).isEqualTo("Mikel Merino")
        assertThat(response.cards?.first()?.color).isEqualTo("Y")
        assertThat(response.stats["Possession"]?.home).isEqualTo("55")
        assertThat(response.playerRatings?.map { it.name }).containsExactly("David Raya", "Cole Palmer")
        assertThat(response.playerOfTheMatch).isEqualTo("Bukayo Saka")
    }

    @Test
    fun `맞대결은 행 하나를 같이 쓰고, 홈 원정은 요청한 팀 기준으로 정한다`() {
        val match = matchResult()
        assertThat(match.toResponse(mapper, Club.ARSENAL).isHome).isTrue()
        assertThat(match.toResponse(mapper, Club.CHELSEA).isHome).isFalse()
    }

    @Test
    fun `JSON 컬럼이 깨져도 그 칸만 비우고 경기는 살린다`() {
        val response = matchResult(scorers = "{망가진 JSON", lineup = null).toResponse(mapper, Club.ARSENAL)

        assertThat(response.scorers).isNull()
        assertThat(response.lineup).isNull()
        assertThat(response.matchId).isEqualTo("4321")
        assertThat(response.homeTeam).isEqualTo("Arsenal")
    }

    @Test
    fun `예정 경기도 대회명을 한국어로 바꾼다`() {
        val response = UpcomingMatch(
            matchId = "9999",
            opponent = "Chelsea",
            matchDate = "2026-09-27T21:00:00+09:00",
            isHome = false,
            tournament = "Premier League",
        ).toResponse()

        assertThat(response.matchId).isEqualTo("9999")
        assertThat(response.opponent).isEqualTo("Chelsea")
        assertThat(response.tournament).isEqualTo("프리미어리그")
    }

    private fun matchResult(
        scorers: String? = """[{"name":"Bukayo Saka","time":"23","assist":null,"isHome":true}]""",
        lineup: String? = """{"home":{"formation":"4-3-3","starters":[{"name":"David Raya","shirt":"1"}]},
                              "away":{"formation":"4-2-3-1","starters":[{"name":"Cole Palmer","shirt":"10"}]}}""",
    ) = MatchResult(
        matchId = "4321",
        homeTeam = "Arsenal",
        awayTeam = "Chelsea",
        homeScore = 2,
        awayScore = 0,
        matchDate = "2026-09-20T21:00:00+09:00",
        tournament = "Premier League",
        isHome = true,
        scorers = scorers,
        lineup = lineup,
        substitutions = """[{"time":"60","outName":"Declan Rice","inName":"Mikel Merino","isHome":true}]""",
        cards = """[{"name":"Moises Caicedo","time":"70","shirt":"25","color":"Y","isHome":false}]""",
        stats = """{"Possession":{"home":"55","away":"45"}}""",
        homeTeamId = "9825",
        awayTeamId = "8455",
        playerRatings = """[{"name":"David Raya","shirt":"1","rating":7.4,"isHome":true,"starter":true},
                            {"name":"Cole Palmer","shirt":"10","rating":6.8,"isHome":false,"starter":true}]""",
        playerOfTheMatch = "Bukayo Saka",
    )
}
