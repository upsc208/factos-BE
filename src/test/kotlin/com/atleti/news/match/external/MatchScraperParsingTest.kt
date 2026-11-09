package com.atleti.news.match.external

import com.atleti.news.match.domain.FotmobPlayerRating
import com.atleti.news.match.external.MatchScraperService.Companion.parseKickoff
import com.atleti.news.match.external.MatchScraperService.Companion.parseLastMatch
import com.atleti.news.match.external.MatchScraperService.Companion.parseMatchStats
import com.atleti.news.match.external.MatchScraperService.Companion.selectUpcoming
import com.atleti.news.match.external.MatchScraperService.Companion.toUpcomingEntity
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FotMob 응답 파싱 검증. 저장해 둔 응답 모양으로 순수 함수만 검증한다 (실제 FotMob을 부르지 않는다).
 *
 * 여기서 지키는 것은 **이미 한 번 틀렸던 판단들**이다. 홈/원정을 팀 이름으로 정하다 뒤집혔고,
 * 킥오프 형식이 두 가지로 오고, 저장 형식이 `ChatService`의 파서와 맞아야 한다.
 */
class MatchScraperParsingTest {

    private val mapper = ObjectMapper().registerKotlinModule()
    private fun json(s: String) = mapper.readTree(s.trimIndent())

    private val ARSENAL = "9825"

    // ── 홈/원정 판정 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("홈/원정은 팀 이름이 아니라 팀 ID로 정한다")
    fun testIsHomeByTeamId() {
        val node = json(
            """
            {
              "id": "4506789",
              "home": { "id": "8650", "name": "Liverpool" },
              "away": { "id": "9825", "name": "Arsenal" },
              "opponent": { "name": "Liverpool" },
              "tournament": { "name": "Premier League" }
            }
            """
        )

        val result = toUpcomingEntity(node, Instant.parse("2026-09-20T19:00:00Z"), ARSENAL)
        assertEquals(false, result.isHome, "아스날이 away인데 home으로 잡혔다")
        assertEquals("Liverpool", result.opponent)
    }

    @Test
    @DisplayName("Big6 팀 일정이면 그 팀 ID로 홈/원정을 가르고 팀을 기록한다")
    fun testBig6FixtureKeepsClubAndHomeSide() {
        val node = json(
            """
            {
              "id": "4813999", "home": { "id": "9825", "name": "Arsenal" },
              "away": { "id": "8455", "name": "Chelsea" },
              "opponent": { "name": "Chelsea" }, "tournament": { "name": "Premier League" }
            }
            """
        )
        val arsenalSide = toUpcomingEntity(node, Instant.parse("2026-11-08T17:30:00Z"), "9825", "arsenal")
        val chelseaSide = toUpcomingEntity(node, Instant.parse("2026-11-08T17:30:00Z"), "8455", "chelsea")

        assertEquals("arsenal", arsenalSide.club)
        assertTrue(arsenalSide.isHome)
        assertEquals(false, chelseaSide.isHome)
        // 두 팀 일정에 같은 경기 ID — 채팅 매치 방이 하나로 합쳐지는 근거
        assertEquals(arsenalSide.matchId, chelseaSide.matchId)
    }

    @Test
    @DisplayName("opponent 이름이 비면 반대편 팀 이름으로 메꾼다")
    fun testOpponentFallback() {
        val node = json(
            """
            {
              "id": "1", "home": { "id": "9825", "name": "Arsenal" },
              "away": { "id": "8667", "name": "Hull" },
              "opponent": { "name": "" }, "tournament": { "name": "Premier League" }
            }
            """
        )
        assertEquals("Hull", toUpcomingEntity(node, Instant.parse("2026-09-20T19:00:00Z"), ARSENAL).opponent)
    }

    @Test
    @DisplayName("대회명이 비면 기본값을 넣는다")
    fun testTournamentFallback() {
        val node = json(
            """
            {
              "id": "1", "home": { "id": "9825", "name": "Arsenal" },
              "away": { "id": "2", "name": "X" }, "opponent": { "name": "X" },
              "tournament": { "name": "" }
            }
            """
        )
        assertEquals("Tournament", toUpcomingEntity(node, Instant.parse("2026-09-20T19:00:00Z"), ARSENAL).tournament)
    }

    // ── 저장 형식 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("킥오프는 ChatService가 읽는 KST 형식으로 저장된다")
    fun testKickoffStoredAsKstIsoOffset() {
        // ChatService.findKickoffTime과 MatchKickoff.parse가 이 형식을 기대한다.
        // 여기서 형식이 어긋나면 채팅방이 열리지 않는다
        val node = json(
            """
            {
              "id": "1", "home": { "id": "9825", "name": "A" }, "away": { "id": "2", "name": "B" },
              "opponent": { "name": "B" }, "tournament": { "name": "Premier League" }
            }
            """
        )

        val result = toUpcomingEntity(node, Instant.parse("2026-08-23T15:00:00Z"), ARSENAL)
        assertEquals("2026-08-24T00:00:00+09:00", result.matchDate)
    }

    // ── 킥오프 파싱 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("킥오프 형식 두 가지를 모두 읽는다")
    fun testKickoffFormats() {
        val expected = Instant.parse("2026-08-23T15:00:00Z")
        assertEquals(expected, parseKickoff("2026-08-23T15:00:00Z"))
        // FotMob이 공백으로 주는 경우가 있다
        assertEquals(expected, parseKickoff("2026-08-23 15:00:00Z"))
    }

    @Test
    @DisplayName("못 읽는 킥오프는 null — 지어내지 않는다")
    fun testKickoffUnparsable() {
        assertNull(parseKickoff(""))
        assertNull(parseKickoff("나중에"))
    }

    // ── 예정 경기 선별 ───────────────────────────────────────────────────

    private val fixtures = """
        [
          { "id": "past",      "status": { "utcTime": "2026-08-01T15:00:00Z", "finished": true,  "cancelled": false },
            "home": { "id": "9825", "name": "A" }, "away": { "id": "2", "name": "B" }, "opponent": { "name": "B" }, "tournament": { "name": "L" } },
          { "id": "cancelled", "status": { "utcTime": "2026-09-10T15:00:00Z", "finished": false, "cancelled": true },
            "home": { "id": "9825", "name": "A" }, "away": { "id": "3", "name": "C" }, "opponent": { "name": "C" }, "tournament": { "name": "L" } },
          { "id": "third",     "status": { "utcTime": "2026-09-30T15:00:00Z", "finished": false, "cancelled": false },
            "home": { "id": "9825", "name": "A" }, "away": { "id": "4", "name": "D" }, "opponent": { "name": "D" }, "tournament": { "name": "L" } },
          { "id": "first",     "status": { "utcTime": "2026-09-05T15:00:00Z", "finished": false, "cancelled": false },
            "home": { "id": "9825", "name": "A" }, "away": { "id": "5", "name": "E" }, "opponent": { "name": "E" }, "tournament": { "name": "L" } },
          { "id": "broken",    "status": { "utcTime": "", "finished": false, "cancelled": false },
            "home": { "id": "9825", "name": "A" }, "away": { "id": "6", "name": "F" }, "opponent": { "name": "F" }, "tournament": { "name": "L" } },
          { "id": "second",    "status": { "utcTime": "2026-09-20T15:00:00Z", "finished": false, "cancelled": false },
            "home": { "id": "9825", "name": "A" }, "away": { "id": "7", "name": "G" }, "opponent": { "name": "G" }, "tournament": { "name": "L" } }
        ]
    """

    @Test
    @DisplayName("끝난 경기·취소된 경기·과거 경기를 버리고 킥오프 순으로 준다")
    fun testSelectUpcomingOrdersAndFilters() {
        val now = Instant.parse("2026-08-26T00:00:00Z")

        val result = selectUpcoming(json(fixtures), now, limit = 3, teamId = ARSENAL)

        // 배열 순서가 아니라 킥오프 순이어야 한다 — 응답이 정렬돼 온다는 보장이 없다
        assertEquals(listOf("E", "G", "D"), result.map { it.opponent })
    }

    @Test
    @DisplayName("킥오프를 못 읽은 경기는 목록에서 빠진다")
    fun testSelectUpcomingDropsUnparsable() {
        val result = selectUpcoming(json(fixtures), Instant.parse("2026-08-26T00:00:00Z"), limit = 10, teamId = ARSENAL)
        // 방 오픈 시각을 계산할 수 없어 채팅방을 못 여는 경기다
        assertTrue(result.none { it.opponent == "F" })
        assertEquals(3, result.size, "유효한 예정 경기는 셋뿐이다")
    }

    @Test
    @DisplayName("limit만큼만 준다")
    fun testSelectUpcomingRespectsLimit() {
        val result = selectUpcoming(json(fixtures), Instant.parse("2026-08-26T00:00:00Z"), limit = 1, teamId = ARSENAL)
        assertEquals(listOf("E"), result.map { it.opponent })
    }

    // ── 직전 경기 ────────────────────────────────────────────────────────

    // FotMob 팀 페이지 overview.lastMatch 모양 (2026-11 실제 응답에서 필요한 칸만)
    private val lastMatchNode = """
        {
          "id": 5795505,
          "home": { "id": 9825, "name": "Arsenal", "score": 1 },
          "away": { "id": 8667, "name": "Hull", "score": 0 },
          "tournament": { "name": "Premier League" },
          "status": { "utcTime": "2026-11-07T15:00:00.000Z", "finished": true }
        }
    """

    @Test
    @DisplayName("직전 경기를 KST 시각과 양 팀 ID로 담는다")
    fun testParseLastMatch() {
        val result = parseLastMatch(json(lastMatchNode), mapOf("playerOfTheMatch" to "Martin Ødegaard"), teamId = ARSENAL)

        assertEquals("5795505", result.matchId)
        assertEquals(1, result.homeScore)
        assertEquals(0, result.awayScore)
        assertEquals("9825", result.homeTeamId)
        assertEquals("8667", result.awayTeamId)
        assertTrue(result.isHome, "홈팀 ID가 아스날이면 홈 경기다")
        assertEquals("2026-11-08T00:00:00+09:00", result.matchDate)
        assertEquals("Martin Ødegaard", result.playerOfTheMatch)
    }

    @Test
    @DisplayName("시각을 못 읽으면 원문을 그대로 둔다 — 지어내지 않는다")
    fun testParseLastMatchKeepsUnparsableTime() {
        val node = json(
            """
            {
              "id": "1", "home": { "id": "9825", "name": "Arsenal", "score": 0 }, "away": { "id": "2", "name": "X", "score": 0 },
              "tournament": { "name": "L" }, "status": { "utcTime": "곧" }
            }
            """
        )
        assertEquals("곧", parseLastMatch(node, emptyMap(), ARSENAL).matchDate)
    }

    // ── 경기 상세 ────────────────────────────────────────────────────────

    // FotMob 경기 페이지 pageProps.content 모양 (필요한 칸만)
    private val matchContent = """
        {
          "lineup": {
            "homeTeam": {
              "formation": "4-3-3",
              "starters": [
                { "id": 1, "name": "David Raya", "shirtNumber": "1", "performance": { "rating": 8.2 } },
                { "id": 2, "name": "Martin Ødegaard", "shirtNumber": "8", "performance": { "rating": 8.5 } }
              ],
              "subs": [
                { "id": 3, "name": "Mikel Merino", "shirtNumber": "23", "performance": { "rating": 6.9 } },
                { "id": 4, "name": "Unused Keeper", "shirtNumber": "32", "performance": {} },
                { "id": 6, "name": "Late Sub", "shirtNumber": "36", "performance": { "substitutionEvents": [ { "time": 89, "type": "subIn" } ] } }
              ]
            },
            "awayTeam": {
              "formation": "5-4-1",
              "starters": [ { "id": 5, "name": "Hull Keeper", "shirtNumber": "1", "performance": { "rating": 7.1 } } ],
              "subs": []
            }
          },
          "stats": { "Periods": { "All": { "stats": [
            { "title": "Top stats", "stats": [
              { "title": "Ball possession", "stats": [62, 38] },
              { "title": "Expected goals (xG)", "stats": ["1.84", "0.31"] },
              { "title": "Keeper saves", "stats": [1, 4] }
            ]},
            { "title": "Expected goals (xG)", "stats": [
              { "title": "Expected goals (xG)", "stats": [null, null] },
              { "title": "Expected goals (xG)", "stats": ["9.99", "9.99"] }
            ]}
          ]}}},
          "matchFacts": {
            "playerOfTheMatch": { "name": { "fullName": "Martin Ødegaard" } },
            "events": { "events": [
              { "type": "Goal", "time": 72, "player": { "name": "Martin Ødegaard" }, "isHome": true },
              { "type": "Card", "time": 80, "card": "YellowRed", "player": { "name": "Hull Defender" }, "isHome": false },
              { "type": "Card", "time": 30, "card": "Yellow", "player": { "name": "David Raya" }, "isHome": true }
            ]}
          }
        }
    """

    @Test
    @DisplayName("통계는 프론트 표시 키로, 같은 지표가 반복되면 처음 값만 담는다")
    fun testParseMatchStatsKeys() {
        val stats = parseMatchStats(json(matchContent), mapper)
        val parsed: Map<String, Map<String, String>> = mapper.readValue(stats["xg"]!!)

        assertEquals("62", parsed["Possession"]?.get("home"))
        assertEquals("1.84", parsed["Expected Goals"]?.get("home"), "xG 그룹의 머리글·중복 행이 덮어쓰면 안 된다")
        assertEquals("4", parsed["Saves"]?.get("away"))
    }

    @Test
    @DisplayName("FotMob 선수 평점: 양 팀 선발 + 들어간 교체 선수(막판 투입은 평점 없이), 안 뛴 교체는 뺀다")
    fun testParsePlayerRatings() {
        val stats = parseMatchStats(json(matchContent), mapper)
        val ratings: List<FotmobPlayerRating> = mapper.readValue(stats["playerRatings"]!!)

        assertEquals(listOf("David Raya", "Martin Ødegaard", "Mikel Merino", "Late Sub", "Hull Keeper"), ratings.map { it.name })
        assertNull(ratings.first { it.name == "Late Sub" }.rating)
        assertEquals("36", ratings.first { it.name == "Late Sub" }.shirt)
        assertEquals(8.5, ratings.first { it.name == "Martin Ødegaard" }.rating)
        assertEquals(false, ratings.first { it.name == "Mikel Merino" }.starter)
        assertEquals(false, ratings.first { it.name == "Hull Keeper" }.isHome)
        assertEquals("Martin Ødegaard", stats["playerOfTheMatch"])
    }

    @Test
    @DisplayName("경고 누적 퇴장(YellowRed)은 레드로 친다")
    fun testSecondYellowIsRed() {
        val cards: List<Map<String, Any?>> = mapper.readValue(parseMatchStats(json(matchContent), mapper)["cards"]!!)
        assertEquals("R", cards.first { it["name"] == "Hull Defender" }["color"])
        assertEquals("Y", cards.first { it["name"] == "David Raya" }["color"])
    }
}
