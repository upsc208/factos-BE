package com.atleti.news.news.domain

import org.slf4j.LoggerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneId
import com.atleti.news.news.external.GeminiService

/**
 * 팀 브리핑 5줄 (아틀레티 시절 헤드라인을 팀별로).
 *
 * 6시간마다 팀마다: 직전 브리핑 이후 들어와 번역이 끝난 그 팀 기사·트윗을 Gemini에 주고
 * 중요한 것만 0~4줄 새로 받는다. 팀마다 최신 5줄만 남긴다 (롤링).
 * 중요한 게 없으면 새 줄을 만들지 않는다 — 지난 줄이 그대로 남는다.
 */
@Service
class HeadlineService(
    private val geminiService: GeminiService,
    private val newsArticleRepository: NewsArticleRepository,
    private val newsHeadlineRepository: NewsHeadlineRepository,
    private val transactionTemplate: TransactionTemplate,
    @Value("\${atleti.news.enabled.headline:true}") private val enabled: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(HeadlineService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val MAX_HEADLINES = 5                  // 팀마다 남길 줄 수 (롤링)
        private const val MAX_PER_RUN = 4            // 한 번에 새로 올릴 최대 개수 (중요도 기준, 꼭 4개는 아님)
        private const val ARTICLE_FETCH_LIMIT = 40   // 한 번에 참고할 최신 기사 상한 (토큰 보호)
        private const val FALLBACK_HOURS = 12L       // 직전 브리핑이 없을 때 되돌아볼 시간
        private const val MAX_LOOKBACK_HOURS = 24L   // 브리핑이 오래 끊겨도 이보다 더 과거는 안 봄
        private const val SUMMARY_CHARS = 200
    }

    fun latest(club: Club): List<NewsHeadline> = newsHeadlineRepository.findTop5ByClubOrderByCreatedAtDesc(club.id)

    // 스프링 스케줄러는 스레드가 하나라 6팀 × Gemini 호출로 붙잡지 않는다
    @Scheduled(cron = "0 30 0/6 * * *", zone = "Asia/Seoul")
    fun scheduled() {
        if (!enabled) return
        scope.launch { generateAll() }
    }

    /** 6팀 차례로. 한 팀이 실패해도 다음 팀은 한다 */
    suspend fun generateAll() {
        for (club in Club.entries) {
            runCatching { generateFor(club) }
                .onFailure { log.error("❌ [브리핑] {} 실패: {}", club.id, it.message, it) }
        }
    }

    /** @return 새로 올린 줄 수 */
    suspend fun generateFor(club: Club): Int = withContext(Dispatchers.IO) {
        val nowSeoul = OffsetDateTime.now(ZoneId.of("Asia/Seoul"))

        // 1. 커서: 직전 브리핑 이후만. 없으면 최근 12시간, 너무 오래됐으면 24시간으로 제한
        val last = newsHeadlineRepository.findTopByClubOrderByCreatedAtDesc(club.id)
        val floor = nowSeoul.minusHours(MAX_LOOKBACK_HOURS)
        var since = last?.createdAt ?: nowSeoul.minusHours(FALLBACK_HOURS)
        if (since.isBefore(floor)) since = floor

        // 2. 커서 이후 번역 끝난 그 팀 기사
        val articles = newsArticleRepository.findRecentTranslatedForClub(club, since, PageRequest.of(0, ARTICLE_FETCH_LIMIT))
        val newsLines = articles.mapNotNull { toNewsLine(it) }
        if (newsLines.isEmpty()) {
            log.info("📰 [브리핑] {} 신규 기사 없음(since={}) → 생략", club.id, since)
            return@withContext 0
        }

        // 3. 기존 줄은 중복 방지용으로 전달
        val existing = newsHeadlineRepository.findAllByClubOrderByCreatedAtDesc(club.id).map { it.content }
        val generated = geminiService.generateHeadlines(newsLines, existing, club)
        if (generated == null) {
            log.error("❌ [브리핑] {} 생성 실패", club.id)
            return@withContext 0
        }

        val fresh = generated
            .map { it.trim() }
            .filter { it.isNotBlank() && existing.none { e -> e.equals(it, ignoreCase = true) } }
            .distinct()
            .take(MAX_PER_RUN)
        if (fresh.isEmpty()) {
            log.info("📰 [브리핑] {} 중요한 새 소식 없음 → 추가 생략", club.id)
            return@withContext 0
        }

        // 4. 저장과 롤링 삭제를 한 트랜잭션으로 — 나눠서 하면 중간 실패 때 5줄을 넘기거나 비어 보인다.
        // suspend 함수에 @Transactional을 붙여도 경계가 안 생겨(SuspendTransactionTest) 명시적으로 친다
        transactionTemplate.executeWithoutResult {
            fresh.forEach { newsHeadlineRepository.save(NewsHeadline(content = it, club = club.id)) }
            val all = newsHeadlineRepository.findAllByClubOrderByCreatedAtDesc(club.id)
            if (all.size > MAX_HEADLINES) newsHeadlineRepository.deleteAll(all.drop(MAX_HEADLINES))
        }
        log.info("✅ [브리핑] {} {}줄 등록: {}", club.id, fresh.size, fresh.joinToString(" | "))
        fresh.size
    }

    /** 기사 한 건 → 프롬프트 한 줄. 출처(공신력 판단용)와 요약(제목만으로 지어내지 않게)을 함께 */
    private fun toNewsLine(a: NewsArticle): String? {
        val title = a.titleKo?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val source = a.authorName?.trim()?.takeIf { it.isNotBlank() }?.let { if (a.sourceName == "X") "@$it" else it } ?: a.sourceName
        val summary = a.summaryKo?.trim()?.replace("\n", " ")?.take(SUMMARY_CHARS)
        return if (summary.isNullOrBlank()) "- (출처: $source) $title" else "- (출처: $source) $title (요약: $summary)"
    }
}
