package com.atleti.news.match.api

import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.MatchResultRepository
import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.match.domain.UpcomingMatchRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 팀 페이지 경기 카드: 팀별 직전 경기(맞대결은 같은 행, 홈/원정은 팀 기준), 다가올 경기, 팀별 선수 평점.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("h2")
class ClubMatchApiTest {

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var results: MatchResultRepository
    @Autowired private lateinit var upcoming: UpcomingMatchRepository

    @BeforeEach
    fun setUp() {
        results.deleteAll()
        // 아스날(홈) 2-0 첼시 — 두 팀 페이지가 같이 쓰는 행
        results.save(
            MatchResult(
                matchId = "800001", homeTeam = "Arsenal", awayTeam = "Chelsea", homeScore = 2, awayScore = 0,
                matchDate = "2026-11-01T01:30:00+09:00", tournament = "Premier League", isHome = true,
                lineup = """{"home":{"formation":"4-3-3","starters":[{"name":"David Raya","shirt":"1"}]},
                             "away":{"formation":"4-2-3-1","starters":[{"name":"Cole Palmer","shirt":"10"}]}}""",
                substitutions = """[{"time":"60","outName":"Declan Rice","inName":"Mikel Merino","isHome":true}]""",
                homeTeamId = "9825", awayTeamId = "8455",
                playerRatings = """[{"name":"David Raya","shirt":"1","rating":7.4,"isHome":true,"starter":true},
                                    {"name":"Mikel Merino","shirt":"23","rating":6.9,"isHome":true,"starter":false},
                                    {"name":"Cole Palmer","shirt":"10","rating":6.8,"isHome":false,"starter":true}]""",
            )
        )
        // 첼시의 더 예전 경기 — 직전 경기로 나오면 안 된다
        results.save(
            MatchResult(
                matchId = "800000", homeTeam = "Chelsea", awayTeam = "Hull", homeScore = 3, awayScore = 1,
                matchDate = "2026-10-25T23:00:00+09:00", tournament = "Premier League", isHome = true,
                homeTeamId = "8455", awayTeamId = "8667",
            )
        )
    }

    @AfterEach
    fun cleanUp() {
        results.deleteAll()
        upcoming.deleteAll()
    }

    @Test
    fun `맞대결 직전 경기는 두 팀에 같은 경기로, 홈 원정은 각 팀 기준`() {
        mvc.perform(get("/api/matches/clubs/arsenal/recent"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matchId").value("800001"))
            .andExpect(jsonPath("$.isHome").value(true))
            .andExpect(jsonPath("$.tournament").value("프리미어리그"))
            .andExpect(jsonPath("$.playerRatings.length()").value(3))
        mvc.perform(get("/api/matches/clubs/chelsea/recent"))
            .andExpect(jsonPath("$.matchId").value("800001"))
            .andExpect(jsonPath("$.isHome").value(false))
    }

    @Test
    fun `직전 경기가 없으면 204, 없는 팀은 404`() {
        mvc.perform(get("/api/matches/clubs/liverpool/recent")).andExpect(status().isNoContent)
        mvc.perform(get("/api/matches/clubs/barcelona/recent")).andExpect(status().isNotFound)
    }

    @Test
    fun `다가올 경기는 그 팀 것만`() {
        val kickoff = OffsetDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(3).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        upcoming.save(UpcomingMatch(club = "arsenal", matchId = "900010", opponent = "Hull", matchDate = kickoff, isHome = true, tournament = "Premier League"))
        upcoming.save(UpcomingMatch(club = "liverpool", matchId = "900011", opponent = "Everton", matchDate = kickoff, isHome = false, tournament = "Premier League"))

        mvc.perform(get("/api/matches/clubs/arsenal/upcoming"))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].opponent").value("Hull"))
            .andExpect(jsonPath("$[0].tournament").value("프리미어리그"))
    }

    @Test
    fun `선수 평점은 요청한 팀 선수만, FotMob 평점과 함께`() {
        mvc.perform(get("/api/matches/800001/ratings").param("club", "arsenal"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2)) // 선발 Raya + 교체 Merino
            .andExpect(jsonPath("$[0].playerName").value("David Raya"))
            .andExpect(jsonPath("$[0].fotmobRating").value(7.4))
            .andExpect(jsonPath("$[1].starter").value(false))
        mvc.perform(get("/api/matches/800001/ratings").param("club", "chelsea"))
            .andExpect(jsonPath("$[0].playerName").value("Cole Palmer"))
        // 그 경기를 안 뛴 팀이면 빈 목록
        mvc.perform(get("/api/matches/800001/ratings").param("club", "liverpool"))
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `평점 투표는 그 팀으로 뛴 선수만`() {
        fun vote(club: String, player: String, score: Int) = post("/api/matches/800001/ratings")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"club":"$club","playerName":"$player","score":$score}""")

        mvc.perform(vote("arsenal", "Cole Palmer", 5)).andExpect(status().isBadRequest)
        mvc.perform(vote("liverpool", "David Raya", 5)).andExpect(status().isBadRequest)
        mvc.perform(vote("arsenal", "David Raya", 4))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myScore").value(4))
            .andExpect(jsonPath("$.fotmobRating").value(7.4))
    }
}
