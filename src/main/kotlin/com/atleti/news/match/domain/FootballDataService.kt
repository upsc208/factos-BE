package com.atleti.news.match.domain

import org.slf4j.LoggerFactory
import com.atleti.news.news.domain.CollectionLog
import com.atleti.news.news.domain.CollectionStatus
import com.atleti.news.news.domain.CollectionLogRepository
import kotlinx.coroutines.*
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import com.atleti.news.match.external.FotMobScrapeException
import com.atleti.news.match.external.MatchScraperService
import com.atleti.news.news.domain.Club

@Service
class FootballDataService(
    private val collectionLogRepository: CollectionLogRepository,
    private val matchResultRepository: MatchResultRepository,
    private val upcomingMatchRepository: UpcomingMatchRepository,
    private val matchScraperService: MatchScraperService,
    private val transactionTemplate: TransactionTemplate
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

    suspend fun getRecentMatch(): MatchResult? =
        matchResultRepository.findTopByOrderByMatchDateDesc()
            ?: matchScraperService.scrapeAndSaveLatestMatch()

    suspend fun getUpcomingMatches(): List<UpcomingMatch> {
        loadFreshUpcoming().takeIf { it.isNotEmpty() }?.let { return it }

        // 비어 있거나 전부 지난 경기뿐이면 즉시 갱신을 시도한다 (쿨다운 내에는 재시도하지 않음)
        if (claimRefreshSlot()) {
            runCatching { updateUpcomingMatches() }
                .onFailure { log.error("❌ [FotMob] 향후 일정 동기화 실패: ${it.message}", it) }
        }
        return loadFreshUpcoming()
    }

    suspend fun syncUpdate(): String {
        return try {
            val result = matchScraperService.scrapeAndSaveLatestMatch()
            val count = updateUpcomingMatches()

            if (result != null) "✅ 동기화 완료 — 최근 경기: ${result.homeTeam} vs ${result.awayTeam} / 향후 일정 ${count}건"
            else "✅ 향후 일정 ${count}건은 동기화되었으나, 최근 경기 데이터는 찾지 못했습니다."
        } catch (e: Exception) {
            "❌ 에러 발생: ${e.message}"
        }
    }

    @Scheduled(cron = "0 0 7,19 * * *", zone = "Asia/Seoul")
    fun scheduledUpdate() {
        serviceScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                matchScraperService.scrapeAndSaveLatestMatch()
                updateUpcomingMatches()
                saveLog(CollectionStatus.SUCCESS, startTime, 0)
            } catch (e: Exception) {
                // 예전에는 예외가 갱신 함수 안에서 삼켜져 실패해도 SUCCESS로 기록됐다
                log.error("❌ [FotMob] 예정 경기 갱신 실패: ${e.message}", e)
                saveLog(CollectionStatus.FAIL, startTime, 0, e.message)
            }
        }
    }

    /** 아직 안 끝난 경기만, 킥오프 순으로 */
    private fun loadFreshUpcoming(): List<UpcomingMatch> {
        val cutoff = Instant.now().minus(KEEP_AFTER_KICKOFF)
        return upcomingMatchRepository.findAllByOrderByMatchDateAsc()
            .filter { parseKickoff(it.matchDate)?.isAfter(cutoff) == true }
    }

    /**
     * FotMob에서 다가올 경기를 받아 upcoming_match 테이블을 통째로 교체한다.
     *
     * 스크랩이 실패하면 예외가 그대로 올라가고 기존 데이터는 손대지 않는다.
     * 반대로 성공했다면 그 결과가 최신 상태이므로, 0건이어도(시즌 종료 등) 지난 경기는 지운다.
     * — 예전 구현은 결과가 비면 아무것도 하지 않아 지난 경기가 영원히 남았다.
     */
    private suspend fun updateUpcomingMatches(): Int {
        lastRefreshAttempt.set(Instant.now())
        var total = 0
        val failed = mutableListOf<String>()

        // Big6 팀 페이지를 하나씩. 팀마다 따로 교체하므로 한 팀 스크랩이 실패해도 다른 팀 일정은 그대로 산다
        Club.entries.forEachIndexed { index, club ->
            if (index > 0) delay(TEAM_SCRAPE_GAP_MS) // FotMob을 연달아 두드리지 않는다
            val fresh = try {
                matchScraperService.scrapeUpcomingMatches(club, UPCOMING_LIMIT)
            } catch (e: Exception) {
                log.warn("⚠️ [FotMob] {} 일정 갱신 실패 (기존 일정 유지): {}", club.id, e.message)
                failed += club.id
                return@forEachIndexed
            }
            withContext(Dispatchers.IO) {
                transactionTemplate.executeWithoutResult {
                    upcomingMatchRepository.deleteAllByClub(club.id)
                    upcomingMatchRepository.saveAll(fresh)
                }
            }
            total += fresh.size
        }

        if (failed.size == Club.entries.size) throw FotMobScrapeException("Big6 일정을 하나도 받지 못했습니다")
        // 아틀레티 시절 행은 이제 쓸 일이 없다
        withContext(Dispatchers.IO) { transactionTemplate.executeWithoutResult { upcomingMatchRepository.deleteAllWithoutClub() } }

        log.info("✅ [FotMob] Big6 향후 일정 {}건 갱신 완료{}", total, if (failed.isEmpty()) "" else " (실패: $failed)")
        return total
    }

    /** 한 팀의 다가올 경기 (채팅 매치 방 목록용) */
    fun getUpcomingMatches(club: Club): List<UpcomingMatch> {
        val cutoff = Instant.now().minus(KEEP_AFTER_KICKOFF)
        val fresh = upcomingMatchRepository.findAllByClubOrderByMatchDateAsc(club.id)
            .filter { parseKickoff(it.matchDate)?.isAfter(cutoff) == true }

        // 비어 있으면(배포 직후, 정기 갱신 07:00/19:00 전) 뒤에서 갱신을 건다.
        // 6팀을 도는 데 20초 남짓 걸려서 요청을 붙잡지 않는다 — 이번 응답은 빈 목록, 다음 요청부터 채워진다
        if (fresh.isEmpty() && claimRefreshSlot()) {
            serviceScope.launch {
                runCatching { updateUpcomingMatches() }
                    .onFailure { log.error("❌ [FotMob] 향후 일정 동기화 실패: ${it.message}", it) }
            }
        }
        return fresh
    }

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
