package com.atleti.news.match.domain


import com.atleti.news.event.MatchEvents
import com.atleti.news.event.OutboxWriter
import java.time.OffsetDateTime
import org.slf4j.LoggerFactory
import com.atleti.news.news.domain.CollectionLog
import com.atleti.news.news.domain.CollectionStatus
import com.atleti.news.news.domain.CollectionLogRepository
import kotlinx.coroutines.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import com.atleti.news.match.external.FotMobScrapeException
import com.atleti.news.match.external.MatchScraperService
import com.atleti.news.news.domain.Club

/**
 * Big6 팀별 경기 데이터: 다가올 경기(upcoming_match)와 직전 경기(match_result, 상세·평점 포함).
 *
 * 팀 페이지 한 번에 둘 다 들어 있어서 팀마다 FotMob을 한 번만 연다 (하루 두 번, 07:00 / 19:00).
 * 직전 경기의 상세 페이지는 처음 볼 때만 연다 — 맞대결은 두 팀 페이지에 같은 경기가 나온다.
 */
@Service
class FootballDataService(
    private val collectionLogRepository: CollectionLogRepository,
    private val matchResultRepository: MatchResultRepository,
    private val upcomingMatchRepository: UpcomingMatchRepository,
    private val matchScraperService: MatchScraperService,
    private val transactionTemplate: TransactionTemplate,
    private val outbox: OutboxWriter,
    // 데이터가 비었을 때 요청을 계기로 FotMob을 여는지. 테스트 프로필은 끈다 (테스트가 실제 사이트를 부르면 안 된다)
    @Value("\${atleti.match.refresh-on-empty:true}") private val refreshOnEmpty: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(FootballDataService::class.java)

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val UPCOMING_LIMIT = 3
    private val TEAM_SCRAPE_GAP_MS = 3_000L

    /** 킥오프 후 이 시간까지는 "다가올 경기"로 계속 노출한다 (채팅방 오픈 구간과 동일, redis-design 6.6) */
    private val KEEP_AFTER_KICKOFF = Duration.ofHours(3)

    /** DB가 비었을 때 요청마다 FotMob을 두드리지 않도록 하는 최소 간격 */
    private val REFRESH_COOLDOWN = Duration.ofMinutes(10)
    private val lastRefreshAttempt = AtomicReference(Instant.EPOCH)

    /**
     * 이 팀의 직전 경기. 맞대결이면 상대 팀과 같은 행이다.
     * 비어 있으면(배포 직후) 뒤에서 갱신을 건다 — 이번 응답은 null, 다음 요청부터 채워진다.
     */
    fun getRecentMatch(club: Club): MatchResult? {
        val found = matchResultRepository.findFirstByHomeTeamIdOrAwayTeamIdOrderByMatchDateDesc(club.fotmobTeamId, club.fotmobTeamId)
        if (found == null) refreshInBackground()
        return found
    }

    /** 한 팀의 다가올 경기 (경기 카드·채팅 매치 방 목록) */
    fun getUpcomingMatches(club: Club): List<UpcomingMatch> {
        val cutoff = Instant.now().minus(KEEP_AFTER_KICKOFF)
        val fresh = upcomingMatchRepository.findAllByClubOrderByMatchDateAsc(club.id)
            .filter { parseKickoff(it.matchDate)?.isAfter(cutoff) == true }

        // 비어 있으면(배포 직후, 정기 갱신 전) 뒤에서 갱신을 건다.
        // 6팀을 도는 데 20초 남짓 걸려서 요청을 붙잡지 않는다
        if (fresh.isEmpty()) refreshInBackground()
        return fresh
    }

    suspend fun syncUpdate(): String = try {
        val (upcoming, finished) = updateClubs()
        "✅ 동기화 완료 — 향후 일정 ${upcoming}건, 직전 경기 ${finished}건"
    } catch (e: Exception) {
        "❌ 에러 발생: ${e.message}"
    }

    @Scheduled(cron = "0 0 7,19 * * *", zone = "Asia/Seoul")
    fun scheduledUpdate() {
        serviceScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                updateClubs()
                saveLog(CollectionStatus.SUCCESS, startTime, 0)
            } catch (e: Exception) {
                log.error("❌ [FotMob] 경기 데이터 갱신 실패: ${e.message}", e)
                saveLog(CollectionStatus.FAIL, startTime, 0, e.message)
            }
        }
    }

    private fun refreshInBackground() {
        if (!refreshOnEmpty || !claimRefreshSlot()) return
        serviceScope.launch {
            runCatching { updateClubs() }
                .onFailure { log.error("❌ [FotMob] 경기 데이터 동기화 실패: ${it.message}", it) }
        }
    }

    /**
     * Big6 팀 페이지를 하나씩 열어 다가올 경기를 팀별로 교체하고, 직전 경기를 저장한다.
     *
     * 팀마다 따로 처리하므로 한 팀이 실패해도 다른 팀 데이터는 산다. 스크랩이 성공했다면
     * 0건이어도(시즌 종료 등) 그 팀의 지난 일정은 지운다.
     *
     * @return (다가올 경기 수, 저장된 직전 경기 수)
     */
    private suspend fun updateClubs(): Pair<Int, Int> {
        lastRefreshAttempt.set(Instant.now())
        var upcomingTotal = 0
        var finishedTotal = 0
        val failed = mutableListOf<String>()

        Club.entries.forEachIndexed { index, club ->
            if (index > 0) delay(TEAM_SCRAPE_GAP_MS) // FotMob을 연달아 두드리지 않는다
            val overview = try {
                matchScraperService.scrapeClub(club, UPCOMING_LIMIT)
            } catch (e: Exception) {
                log.warn("⚠️ [FotMob] {} 갱신 실패 (기존 데이터 유지): {}", club.id, e.message)
                failed += club.id
                return@forEachIndexed
            }
            withContext(Dispatchers.IO) { replaceUpcoming(club, overview.upcoming) }
            upcomingTotal += overview.upcoming.size
            overview.lastMatch?.let { node ->
                if (matchScraperService.saveFinishedMatch(node) != null) finishedTotal++
            }
        }

        if (failed.size == Club.entries.size) throw FotMobScrapeException("Big6 경기 데이터를 하나도 받지 못했습니다")
        // club 없는 예전 일정 행 정리
        withContext(Dispatchers.IO) { transactionTemplate.executeWithoutResult { upcomingMatchRepository.deleteAllWithoutClub() } }

        log.info(
            "✅ [FotMob] Big6 향후 일정 {}건, 직전 경기 {}건 갱신 완료{}",
            upcomingTotal, finishedTotal, if (failed.isEmpty()) "" else " (실패: $failed)"
        )
        return upcomingTotal to finishedTotal
    }

    /**
     * 한 팀의 다가올 경기를 통째로 바꾸고, 바뀐 점을 경기 이벤트로 아웃박스에 적는다 — **한 트랜잭션**.
     * 일정은 바뀌었는데 이벤트가 빠지거나, 롤백됐는데 이벤트만 나가는 일이 없게 (docs/events.md)
     *
     * @return 아웃박스에 적은 이벤트 수
     */
    fun replaceUpcoming(club: Club, upcoming: List<UpcomingMatch>): Int =
        transactionTemplate.execute {
            val before = upcomingMatchRepository.findAllByClubOrderByMatchDateAsc(club.id)
            upcomingMatchRepository.deleteAllByClub(club.id)
            upcomingMatchRepository.saveAll(upcoming)
            val changes = MatchEvents.diff(club, before, upcoming, OffsetDateTime.now())
            changes.forEach { outbox.record(it.type, it.payload.matchId, it.payload) }
            changes.size
        } ?: 0

    /** 쿨다운이 지났으면 갱신 권한을 가져간다. 동시 요청 중 하나만 true를 받는다. */
    private fun claimRefreshSlot(): Boolean {
        val now = Instant.now()
        val last = lastRefreshAttempt.get()
        return last.isBefore(now.minus(REFRESH_COOLDOWN)) && lastRefreshAttempt.compareAndSet(last, now)
    }

    private fun parseKickoff(raw: String): Instant? =
        runCatching { java.time.OffsetDateTime.parse(raw).toInstant() }
            .recoverCatching { Instant.parse(raw.replace(" ", "T")) }
            .getOrNull()

    private fun saveLog(status: CollectionStatus, startTime: Long, callCount: Int, errorMsg: String? = null) {
        collectionLogRepository.save(CollectionLog(
            status = status,
            footballApiCallCount = callCount,
            errorMsg = errorMsg,
            executionTimeMs = System.currentTimeMillis() - startTime
        ))
    }
}
