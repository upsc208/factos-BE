package com.atleti.news.live.domain

import com.atleti.news.live.external.NaverGame
import com.atleti.news.live.external.NaverSportsClient
import com.atleti.news.match.domain.UpcomingMatchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import com.atleti.news.match.domain.MatchKickoff
import com.atleti.news.news.domain.Club

/**
 * FotMob matchId ↔ 네이버 gameId 매핑 (docs/live-match-feed-design.md 7장).
 *
 * 채팅방 키는 FotMob matchId인데 네이버 gameId는 `Z5BQDTTiMjv6iBO` 같은 불투명 문자열이라
 * 서로 무관하다. **킥오프 시각 + 우리 팀 네이버 코드**([Club.naverTeamCode])로 잇는다.
 *
 * 시각만으로는 안 된다: Big6는 같은 시각에 여러 팀이 뛴다 (토요일 00:00 KST에 아스널·첼시 동시 킥오프).
 * 팀 이름 대신 코드를 쓰는 이유는 표기가 서로 달라서다 (`아스널` vs `Arsenal`, 네이버는 "아스날"도 아니다).
 * 상대팀은 보지 않는다 — 우리 팀이 30분 안에 두 경기를 뛸 수는 없다.
 */
@Service
class LiveMatchMapper(
    private val redis: StringRedisTemplate,
    private val naverSportsClient: NaverSportsClient,
    private val upcomingMatchRepository: UpcomingMatchRepository,
    @Value("\${atleti.live.enabled}") private val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(LiveMatchMapper::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 킥오프 전에 미리 매핑을 만들어 둔다 (설계 10장 2번 완료 기준).
     *
     * FotMob 일정 갱신(07:00 / 19:00)이 끝난 직후에 편승한다 — 새로 잡힌 경기가
     * upcoming_match에 들어온 뒤라야 짝지을 대상이 생긴다.
     */
    @Scheduled(cron = "0 10 7,19 * * *", zone = "Asia/Seoul")
    fun scheduledWarmUp() {
        if (!enabled) return
        scope.launch {
            runCatching { warmUp() }
                .onFailure { log.warn("⚠️ [Live] 매핑 사전 생성 실패: {}", it.message) }
        }
    }

    /**
     * 아직 매핑이 없는 예정 경기를 한 번에 짝지어 둔다.
     * 월별로 묶어 캘린더를 월당 한 번만 훑는다 — 경기마다 훑으면 같은 스캔이 3번 돈다.
     *
     * @return 새로 만든 매핑 수
     */
    suspend fun warmUp(): Int {
        val now = OffsetDateTime.now()
        val targets = upcomingMatchRepository.findAllByOrderByMatchDateAsc()
            .mapNotNull { entity ->
                val matchId = entity.matchId ?: return@mapNotNull null
                val kickoff = MatchKickoff.parse(entity.matchDate) ?: return@mapNotNull null
                matchId to kickoff
            }
            .distinctBy { it.first } // 맞대결은 두 팀 일정에 한 번씩 있다
            .filter { (matchId, kickoff) -> kickoff.isAfter(now) && !hasMapping(matchId) }

        if (targets.isEmpty()) return 0

        var created = 0
        for ((yearMonth, group) in targets.groupBy { (_, kickoff) -> yearMonth(kickoff) }) {
            val scanned = runCatching { naverSportsClient.findGames(yearMonth, ALL_TEAM_CODES) }
            val games = scanned.getOrNull()
            if (games == null) {
                // 이 달만 건너뛴다 — 다른 달 경기까지 함께 잃을 이유가 없다
                log.warn("⚠️ [Live] {} 네이버 일정 조회 실패: {}", yearMonth, scanned.exceptionOrNull()?.message)
                continue
            }
            for ((matchId, kickoff) in group) {
                if (pairAndStore(matchId, kickoff, games) != null) created++
            }
        }

        log.info("✅ [Live] 매핑 사전 생성 — 대상 {}건 중 {}건 성공", targets.size, created)
        return created
    }

    /**
     * matchId에 대응하는 네이버 gameId. 이미 있으면 Redis에서 바로 준다.
     *
     * 없으면 그 자리에서 한 번 찾아본다 — 배치(07:00/19:00) 사이에 새로 잡힌 경기는
     * 사전 생성 기회를 놓치는데, 그때마다 라이브 피드가 통째로 빠지면 안 된다.
     * 실패는 [LiveMatchKeys.mapMiss]에 잠시 기록해 5초마다 캘린더를 다시 훑는 일을 막는다.
     */
    suspend fun resolveGameId(matchId: String, kickoff: OffsetDateTime): String? {
        cached(matchId)?.let { return it }
        if (hasKey(LiveMatchKeys.mapMiss(matchId))) return null

        val games = runCatching { naverSportsClient.findGames(yearMonth(kickoff), ALL_TEAM_CODES) }
            .getOrElse {
                log.warn("⚠️ [Live] 네이버 일정 조회 실패 (matchId={}): {}", matchId, it.message)
                markMiss(matchId)
                return null
            }
        return pairAndStore(matchId, kickoff, games)
    }

    private fun pairAndStore(matchId: String, kickoff: OffsetDateTime, games: List<NaverGame>): String? {
        val codes = teamCodesOf(matchId)
        if (codes.isEmpty()) {
            log.warn("⚠️ [Live] matchId={}의 팀을 모릅니다 (upcoming_match.club 없음)", matchId)
            markMiss(matchId)
            return null
        }
        val paired = pairByKickoff(kickoff, games, codes)

        if (paired == null) {
            log.warn("⚠️ [Live] 킥오프 {}에 맞는 네이버 경기를 찾지 못했습니다 (matchId={}, 팀 코드={})", kickoff, matchId, codes)
            markMiss(matchId)
            return null
        }

        redis.opsForValue().set(LiveMatchKeys.map(matchId), paired.gameId, LiveMatchKeys.MAP_TTL)
        redis.delete(LiveMatchKeys.mapMiss(matchId))
        log.info(
            "✅ [Live] 매핑 {} → {} ({} vs {}, {})",
            matchId, paired.gameId, paired.homeTeamName, paired.awayTeamName, paired.kickoff
        )
        return paired.gameId
    }

    /** 이 경기를 뛰는 우리 팀들의 네이버 코드 (맞대결이면 둘) */
    private fun teamCodesOf(matchId: String): Set<String> =
        runCatching { upcomingMatchRepository.findAllByMatchIdIn(listOf(matchId)) }.getOrDefault(emptyList())
            .mapNotNull { it.club?.let(Club::fromId)?.naverTeamCode }
            .toSet()

    private fun cached(matchId: String): String? =
        runCatching { redis.opsForValue().get(LiveMatchKeys.map(matchId)) }.getOrNull()

    private fun hasMapping(matchId: String): Boolean = hasKey(LiveMatchKeys.map(matchId))

    /** Redis가 없어도 매핑만 못 만들 뿐 채팅은 돌아야 한다 (fail-open, 설계 8.5) */
    private fun hasKey(key: String): Boolean =
        runCatching { redis.hasKey(key) }.getOrNull() == true

    private fun markMiss(matchId: String) {
        runCatching {
            redis.opsForValue().set(LiveMatchKeys.mapMiss(matchId), "1", LiveMatchKeys.MAP_MISS_TTL)
        }
    }

    /** 네이버 캘린더는 KST 날짜 기준이다. 04:00 킥오프가 전월로 새지 않도록 KST로 변환해 뽑는다. */
    private fun yearMonth(kickoff: OffsetDateTime): String =
        kickoff.atZoneSameInstant(KST).format(YEAR_MONTH)

    companion object {
        /** 중계 편성으로 킥오프가 몇 분 흔들려도 짝이 유지되도록 넉넉히 잡는다 (설계 7장) */
        private val KICKOFF_TOLERANCE: Duration = Duration.ofMinutes(30)

        private val ALL_TEAM_CODES: Set<String> = Club.entries.mapTo(HashSet()) { it.naverTeamCode }

        private val KST = ZoneId.of("Asia/Seoul")
        private val YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM")

        /**
         * [teamCodes] 팀이 뛰는 경기 가운데 킥오프가 [KICKOFF_TOLERANCE] 안에서 **가장 가까운** 것을 짝으로 본다.
         * 한 팀이 30분 간격으로 두 경기를 뛸 수 없으므로 가장 가까운 것 하나로 충분하다.
         * [teamCodes]가 비면 팀을 보지 않는다 (테스트용).
         */
        internal fun pairByKickoff(kickoff: OffsetDateTime, games: List<NaverGame>, teamCodes: Set<String> = emptySet()): NaverGame? =
            games
                .filter { teamCodes.isEmpty() || it.involvesAny(teamCodes) }
                .mapNotNull { game -> game.kickoff?.let { game to abs(Duration.between(it, kickoff).toMillis()) } }
                .filter { (_, gap) -> gap <= KICKOFF_TOLERANCE.toMillis() }
                .minByOrNull { (_, gap) -> gap }
                ?.first
    }
}
