package com.atleti.news.match.external

import org.slf4j.LoggerFactory
import com.atleti.news.match.domain.MatchResult
import com.atleti.news.match.domain.FotmobPlayerRating
import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.news.domain.Club
import com.atleti.news.match.domain.MatchResultRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** FotMob 스크랩이 실패했음을 알린다. "일정이 0건"과 구분하기 위해 필요하다. */
class FotMobScrapeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Service
class MatchScraperService(
    private val objectMapper: ObjectMapper,
    private val matchResultRepository: MatchResultRepository,
    private val transactionTemplate: TransactionTemplate,
    private val outbox: com.atleti.news.event.OutboxWriter,
    // 기본값은 실제 FotMob. 로컬 리허설에서만 가짜 서버로 돌린다 (scripts/fake-live-match.js)
    @Value("\${atleti.source.fotmob-base-url}") private val fotmobBaseUrl: String
) {
    private val log = LoggerFactory.getLogger(MatchScraperService::class.java)

    private fun teamUrl(teamId: String, slug: String) = "$fotmobBaseUrl/teams/$teamId/overview/$slug"

    /** 팀 페이지 한 번에서 얻는 것: 다가올 경기 + 직전 경기 노드 */
    class ClubOverview(val upcoming: List<UpcomingMatch>, val lastMatch: JsonNode?)

    /**
     * 팀 페이지를 한 번 열어 다가올 경기 [limit]개와 직전 경기 노드를 함께 가져온다 (저장은 호출자 몫).
     *
     * 팀 페이지 자체가 그 팀 1군 일정이라 여자팀·유스팀을 걸러낼 필요가 없다.
     *
     * @throws FotMobScrapeException 페이지 접속·파싱에 실패한 경우
     */
    suspend fun scrapeClub(club: Club, limit: Int = 3): ClubOverview = withContext(Dispatchers.IO) {
        val overview = fetchOverviewNode(club.fotmobTeamId, club.bbcSlug)
        val fixtures = overview.at("/overviewFixtures")
        if (!fixtures.isArray) throw FotMobScrapeException("overviewFixtures 노드를 찾지 못했습니다")

        ClubOverview(
            upcoming = selectUpcoming(fixtures, Instant.now(), limit, club.fotmobTeamId, club.id),
            lastMatch = overview.at("/lastMatch").takeIf { !it.isMissingNode && !it.isNull && it.at("/id").asText().isNotBlank() },
        )
    }

    /**
     * 끝난 경기를 저장한다. 상세(선발·통계·평점)까지 받아 둔 경기는 다시 받지 않는다 —
     * Big6 맞대결은 두 팀 페이지에 같은 경기가 나오고, 하루 두 번 갱신마다 상세 페이지를 열 필요가 없다.
     *
     * @return 저장된(또는 이미 있던) 경기. 실패하면 null
     */
    suspend fun saveFinishedMatch(lastMatch: JsonNode): MatchResult? = withContext(Dispatchers.IO) {
        val matchId = lastMatch.at("/id").asText()
        matchResultRepository.findByMatchId(matchId)
            ?.takeIf { it.playerRatings != null && it.homeTeamId != null }
            ?.let { return@withContext it }
        try {
            saveLastMatch(lastMatch)
        } catch (e: Exception) {
            log.error("❌ [FotMob Scrape] 경기 {} 저장 실패: {}", matchId, e.message, e)
            null
        }
    }

    /**
     * 팀 페이지의 __NEXT_DATA__에서 overview 노드를 찾는다.
     * 최근 경기(lastMatch)와 전체 일정(overviewFixtures)이 모두 이 노드 아래에 있다.
     */
    private suspend fun fetchOverviewNode(teamId: String, slug: String): JsonNode {
        var lastError: Exception? = null

        // FotMob이 간헐적으로 403/타임아웃을 내므로 1회 재시도한다
        for (retry in 1..2) {
            try {
                val response = Jsoup.connect(teamUrl(teamId, slug))
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9,ko;q=0.8")
                    .referrer("https://www.fotmob.com/")
                    .ignoreHttpErrors(true)
                    .followRedirects(true)
                    .maxBodySize(0)
                    .timeout(30000)
                    .execute()

                if (response.statusCode() != 200) {
                    lastError = FotMobScrapeException("HTTP ${response.statusCode()}")
                    log.warn("⚠️ [FotMob] HTTP ${response.statusCode()} - 재시도 $retry/2")
                    delay(2000)
                    continue
                }

                val jsonData = response.parse().select("#__NEXT_DATA__").first()?.data()
                    ?: throw FotMobScrapeException("__NEXT_DATA__ 스크립트를 찾지 못했습니다")
                val pageProps = objectMapper.readTree(jsonData).at("/props/pageProps")

                var overviewNode = pageProps.at("/fallback/team-$teamId/overview")
                if (overviewNode.isMissingNode) {
                    val fallback = pageProps.at("/fallback")
                    val dataKey = fallback.fieldNames().asSequence().find { it.contains("api/team") }
                    if (dataKey != null) overviewNode = fallback.at("/$dataKey/overview")
                }
                if (overviewNode.isMissingNode) overviewNode = pageProps.at("/overview/overview")
                if (overviewNode.isMissingNode) overviewNode = pageProps.at("/initialData/overview")

                if (overviewNode.isMissingNode) throw FotMobScrapeException("overview 노드를 찾지 못했습니다")
                return overviewNode
            } catch (e: FotMobScrapeException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                log.warn("⚠️ [FotMob] 접속 실패 (재시도 $retry/2): ${e.message}", e)
                delay(2000)
            }
        }
        throw FotMobScrapeException("FotMob 페이지를 가져오지 못했습니다: ${lastError?.message}", lastError)
    }

    /**
     * 최근 경기를 저장한다. **바깥과 말하는 일, 조립, 저장을 각각 다른 곳에 둔다** —
     * 통계 조회는 여기서(I/O), 노드→엔티티 변환은 [parseLastMatch]에서(순수), 쓰기는 트랜잭션 안에서.
     */
    private suspend fun saveLastMatch(lastMatch: JsonNode): MatchResult? {
        val matchId = lastMatch.at("/id").asText()
        val stats = fetchMatchStats(matchId)
        val matchResult = parseLastMatch(lastMatch, stats)

        log.info("✅ [FotMob Scrape] 데이터 저장 시도: ${matchResult.homeTeam} vs ${matchResult.awayTeam} (${matchResult.matchDate})")

        // 같은 경기를 지우고 새로 넣는다. 한 트랜잭션으로 묶지 않으면 중간 실패 시
        // **최근 경기 기록이 통째로 사라진 상태**가 남는다 — 화면의 직전 경기 카드가 빈다.
        // suspend 함수에 @Transactional을 붙여도 withContext가 스레드를 옮겨 경계가 안 생긴다
        // (근거: SuspendTransactionTest). 그래서 명시적으로 경계를 친다.
        // 처음 저장될 때만 match.finished를 아웃박스에 같이 적는다 (상세만 다시 받는 경우는 이미 알린 경기)
        return transactionTemplate.execute {
            val existing = matchResultRepository.findByMatchId(matchId)
            existing?.let {
                matchResultRepository.delete(it)
                matchResultRepository.flush()
            }
            matchResultRepository.save(matchResult).also { saved ->
                if (existing == null) {
                    outbox.record(com.atleti.news.event.MatchEvents.FINISHED, saved.matchId, com.atleti.news.event.MatchEvents.result(saved))
                }
            }
        }
    }

    /**
     * 경기 상세 페이지를 받아 온다. **네트워크만 담당하고 해석은 [parseMatchStats]에 맡긴다** —
     * 그래야 저장해 둔 응답으로 파싱을 검증할 수 있다 (`NaverSportsClient.parseGame`과 같은 이유).
     *
     * 상세 정보는 부가 데이터라 실패해도 빈 맵으로 넘어간다. 경기 결과 자체는 저장돼야 한다.
     */
    private suspend fun fetchMatchStats(matchId: String): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect("$fotmobBaseUrl/match/$matchId")
                .userAgent(USER_AGENT)
                .get()
            val jsonData = doc.select("#__NEXT_DATA__").first()?.data()
                ?: return@withContext emptyMap()
            parseMatchStats(objectMapper.readTree(jsonData).at("/props/pageProps/content"), objectMapper)
        } catch (e: Exception) {
            log.warn("⚠️ [FotMob Details] 상세 정보 추출 실패: ${e.message}", e)
            emptyMap()
        }
    }

    companion object {
        internal val KST: ZoneId = ZoneId.of("Asia/Seoul")
        internal const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        /**
         * 다가올 경기를 골라 킥오프 순으로 [limit]개.
         *
         * 끝났거나 취소된 경기, [now]보다 과거인 경기를 버린다. 킥오프를 못 읽은 경기도 버린다 —
         * `ChatService`가 방 오픈 시각을 계산할 수 없어 어차피 쓸모없다.
         */
        internal fun selectUpcoming(
            fixtures: JsonNode, now: Instant, limit: Int,
            teamId: String, club: String? = null,
        ): List<UpcomingMatch> =
            fixtures.asSequence()
                .filter { !it.at("/status/finished").asBoolean() && !it.at("/status/cancelled").asBoolean() }
                .mapNotNull { node ->
                    val kickoff = parseKickoff(node.at("/status/utcTime").asText()) ?: return@mapNotNull null
                    if (kickoff.isBefore(now)) null else node to kickoff
                }
                .sortedBy { (_, kickoff) -> kickoff }
                .take(limit)
                .map { (node, kickoff) -> toUpcomingEntity(node, kickoff, teamId, club) }
                .toList()

        internal fun toUpcomingEntity(node: JsonNode, kickoff: Instant, teamId: String, club: String? = null): UpcomingMatch {
            // 홈/원정은 팀 이름이 아니라 FotMob 팀 ID로 판별한다.
            // 이름 비교는 표기가 흔들리고(Man City / Manchester City) 상대 이름에 우리 이름이 섞이면 뒤집힌다.
            val isHome = node.at("/home/id").asText() == teamId
            val opponent = node.at("/opponent/name").asText()
                .ifBlank { if (isHome) node.at("/away/name").asText() else node.at("/home/name").asText() }

            return UpcomingMatch(
                club = club,
                matchId = node.at("/id").asText().takeIf { it.isNotBlank() },
                opponent = opponent,
                // ChatService.findKickoffTime이 파싱하는 형식과 맞춰야 한다 (ISO_OFFSET_DATE_TIME / KST)
                matchDate = kickoff.atZone(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                isHome = isHome,
                tournament = node.at("/tournament/name").asText().takeIf { it.isNotBlank() } ?: "Tournament"
            )
        }

        internal fun parseKickoff(raw: String): Instant? =
            runCatching { Instant.parse(raw) }
                .recoverCatching { Instant.parse(raw.replace(" ", "T")) }
                .getOrNull()

        /**
         * 최근 경기 노드 + 상세 통계 → 저장할 엔티티. **순수 함수다** (I/O 없음).
         *
         * 홈/원정 팀 ID를 함께 저장한다. 맞대결은 두 팀이 행 하나를 같이 쓰므로 "우리 팀이 홈인가"는
         * 응답을 만들 때 팀별로 정한다. [isHome] 컬럼은 [teamId] 기준 값이다.
         * 시각을 못 읽으면 원문을 그대로 둔다 — 지어내는 것보다 낫다.
         */
        internal fun parseLastMatch(lastMatch: JsonNode, stats: Map<String, String>, teamId: String? = null): MatchResult {
            val homeTeam = lastMatch.at("/home/name").asText()
            val homeTeamId = lastMatch.at("/home/id").asText().ifBlank { null }
            val utcTimeStr = lastMatch.at("/status/utcTime").asText()
            val kstTimeStr = runCatching {
                ZonedDateTime.parse(utcTimeStr).withZoneSameInstant(KST)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            }.getOrDefault(utcTimeStr)

            return MatchResult(
                matchId = lastMatch.at("/id").asText(),
                homeTeam = homeTeam,
                awayTeam = lastMatch.at("/away/name").asText(),
                homeScore = lastMatch.at("/home/score").asInt(),
                awayScore = lastMatch.at("/away/score").asInt(),
                matchDate = kstTimeStr,
                tournament = lastMatch.at("/tournament/name").asText(),
                isHome = teamId != null && homeTeamId == teamId,
                homeTeamId = homeTeamId,
                awayTeamId = lastMatch.at("/away/id").asText().ifBlank { null },
                playerRatings = stats["playerRatings"],
                playerOfTheMatch = stats["playerOfTheMatch"],
                scorers = stats["scorers"], stats = stats["xg"],
                possession = stats["possession"], shotsOnTarget = stats["shotsOnTarget"],
                cards = stats["cards"], lineup = stats["lineup"],
                substitutions = stats["substitutions"],
                homeFormation = stats["homeFormation"], awayFormation = stats["awayFormation"]
            )
        }

        /** 경기 상세 페이지의 `content` 노드 → 저장용 통계 맵. **순수 함수다** */
        internal fun parseMatchStats(content: JsonNode, objectMapper: ObjectMapper): Map<String, String> {
            val statsMap = mutableMapOf<String, String>()
            val shirtMap = mutableMapOf<String, String>()
            val lineup = content.at("/lineup")
            if (!lineup.isMissingNode) {
                listOf("homeTeam", "awayTeam").forEach { team ->
                    lineup.at("/$team/starters").elements().forEach { p -> shirtMap[p.at("/id").asText()] = p.at("/shirtNumber").asText() }
                    lineup.at("/$team/subs").elements().forEach { p -> shirtMap[p.at("/id").asText()] = p.at("/shirtNumber").asText() }
                }
                statsMap["homeFormation"] = lineup.at("/homeTeam/formation").asText()
                statsMap["awayFormation"] = lineup.at("/awayTeam/formation").asText()
            }

            val topStats = content.at("/stats/Periods/All/stats")
            val structuredStats = mutableMapOf<String, Map<String, String>>()
            if (topStats.isArray) {
                for (statGroup in topStats) {
                    for (stat in statGroup.at("/stats")) {
                        // 그룹 머리글 행("Shots", "Passes" …)은 값이 없다 — 양쪽 값이 있는 행만
                        val values = stat.at("/stats")
                        if (!values.isArray || values.size() < 2 || values[0].isNull) continue
                        // 키는 프론트 MatchResultCard/utils.ts의 STATS_TRANSLATIONS 키와 같아야 한글로 나온다
                        val key = STAT_KEYS[stat.at("/title").asText()] ?: continue
                        // 같은 지표가 여러 그룹에 반복된다 (xG가 Top stats와 xG 그룹에) — 처음 것만
                        structuredStats.putIfAbsent(key, mapOf("home" to values[0].asText(), "away" to values[1].asText()))
                    }
                }
            }
            
            val events = content.at("/matchFacts/events/events")
            val scorersStructured = mutableListOf<Map<String, Any?>>()
            val cardsStructured = mutableListOf<Map<String, Any?>>()
            val subsStructured = mutableListOf<Map<String, Any?>>()
            
            if (events.isArray) {
                for (event in events) {
                    val type = event.at("/type").asText()
                    val time = event.at("/time").asText()
                    val name = event.at("/player/name").asText().ifBlank { event.at("/nameStr").asText() }
                    val isHomeEvent = event.at("/isHome").asBoolean()
                    
                    when (type) {
                        "Goal" -> {
                            scorersStructured.add(mapOf(
                                "name" to name, "time" to time, 
                                "assist" to event.at("/assistInput").asText().ifBlank { null },
                                "isHome" to isHomeEvent
                            ))
                        }
                        "Card" -> {
                            val color = if (event.at("/card").asText() == "Yellow") "Y" else "R" // YellowRed(경고 누적)는 퇴장
                            cardsStructured.add(mapOf("name" to name, "time" to time, "color" to color, "isHome" to isHomeEvent))
                        }
                        "Substitution" -> {
                            subsStructured.add(mapOf(
                                "time" to time,
                                "outName" to event.at("/swap/1/name").asText(),
                                "inName" to event.at("/swap/0/name").asText(),
                                "isHome" to isHomeEvent
                            ))
                        }
                    }
                }
            }
            
            statsMap["scorers"] = objectMapper.writeValueAsString(scorersStructured)
            statsMap["cards"] = objectMapper.writeValueAsString(cardsStructured)
            statsMap["substitutions"] = objectMapper.writeValueAsString(subsStructured)
            statsMap["xg"] = objectMapper.writeValueAsString(structuredStats)

            if (!lineup.isMissingNode) {
                val lineupStructured = mapOf(
                    "home" to mapOf(
                        "formation" to statsMap["homeFormation"],
                        "starters" to lineup.at("/homeTeam/starters").elements().asSequence().map { 
                            mapOf("name" to it.at("/name").asText(), "shirt" to it.at("/shirtNumber").asText())
                        }.toList()
                    ),
                    "away" to mapOf(
                        "formation" to statsMap["awayFormation"],
                        "starters" to lineup.at("/awayTeam/starters").elements().asSequence().map { 
                            mapOf("name" to it.at("/name").asText(), "shirt" to it.at("/shirtNumber").asText())
                        }.toList()
                    )
                )
                statsMap["lineup"] = objectMapper.writeValueAsString(lineupStructured)

                // FotMob 선수 평점. 교체는 실제로 들어간 선수만 (막판 투입이면 평점 없이 들어간다)
                val ratings = listOf("homeTeam" to true, "awayTeam" to false).flatMap { (team, isHome) ->
                    val starters = lineup.at("/$team/starters").elements().asSequence().map { it to true }
                    val subs = lineup.at("/$team/subs").elements().asSequence().map { it to false }
                    (starters + subs).mapNotNull { (p, starter) ->
                        val rating = p.at("/performance/rating").takeIf { it.isNumber }?.asDouble()
                        val cameOn = p.at("/performance/substitutionEvents").any { it.at("/type").asText() == "subIn" }
                        if (!starter && rating == null && !cameOn) return@mapNotNull null
                        FotmobPlayerRating(
                            name = p.at("/name").asText(),
                            shirt = p.at("/shirtNumber").asText().ifBlank { null },
                            rating = rating,
                            isHome = isHome,
                            starter = starter,
                        )
                    }.toList()
                }
                statsMap["playerRatings"] = objectMapper.writeValueAsString(ratings)
            }

            content.at("/matchFacts/playerOfTheMatch/name/fullName").asText().takeIf { it.isNotBlank() }
                ?.let { statsMap["playerOfTheMatch"] = it }

            return statsMap
        }

        /** FotMob 통계 제목 → 프론트 표시 키 */
        private val STAT_KEYS = mapOf(
            "Ball possession" to "Possession",
            "Expected goals (xG)" to "Expected Goals",
            "Total shots" to "Shots",
            "Shots on target" to "Shots on Target",
            "Big chances" to "Big Chances",
            "Accurate passes" to "Pass Accuracy",
            "Corners" to "Corners",
            "Keeper saves" to "Saves",
            "Fouls committed" to "Fouls",
            "Yellow cards" to "Yellow Cards",
            "Red cards" to "Red Cards",
            "Offsides" to "Offsides",
            "Tackles" to "Tackles",
        )
    }
}
