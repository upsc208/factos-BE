package com.atleti.news.news.domain

import com.atleti.news.news.external.CrawlerClient
import com.atleti.news.news.external.ExtractedArticle
import com.atleti.news.news.external.RssFeedReader
import com.atleti.news.news.external.RssItem
import com.atleti.news.news.external.ScrapedArticle
import com.atleti.news.news.external.TwitterApiService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Big6 뉴스 파이프라인: RSS → `/extract` → PENDING 저장 → `/translate` → DONE.
 *
 * **수집과 번역을 한 루프에서 번갈아 돈다.** 번역은 기사당 평균 63초라, 대기열을 한 번에 다 비우려 하면
 * 그동안 새 기사 수집이 멈춘다. 그래서 번역은 한 번에 기사 하나만 하고, 매번 수집할 때가 됐는지 확인한다.
 *
 * `@Scheduled`를 쓰지 않는 이유는 [com.atleti.news.live.domain.LiveMatchPoller]와 같다 — 스프링 스케줄러는
 * 스레드가 1개라 1분짜리 번역이 그 하나를 붙잡으면 다른 배치가 전부 밀린다. 이 루프는 자기 코루틴에서 돈다.
 *
 * 루프가 하나뿐이라 같은 기사를 수집(팀 추가)과 번역이 동시에 고치는 일은 없다.
 */
@Service
class ClubNewsPipeline(
    private val rssFeedReader: RssFeedReader,
    private val crawlerClient: CrawlerClient,
    private val newsArticleRepository: NewsArticleRepository,
    private val articleDeletionService: ArticleDeletionService,
    private val twitterApiService: TwitterApiService,
    private val collectionLogRepository: CollectionLogRepository,
    @Value("\${atleti.news.enabled.club-rss}") private val enabled: Boolean,
    @Value("\${atleti.news.club-rss.max-age-hours:24}") private val maxAgeHours: Long,
    @Value("\${atleti.news.enabled.club-twitter}") private val twitterEnabled: Boolean,
    @Value("\${atleti.news.club-twitter.interval-minutes:30}") private val twitterIntervalMinutes: Long,
    @Value("\${rapidapi.key}") private val rapidApiKey: String,
) {
    private val log = LoggerFactory.getLogger(ClubNewsPipeline::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastCollectedAt: OffsetDateTime? = null
    @Volatile private var lastTweetsCollectedAt: OffsetDateTime? = null

    /**
     * 번역이 계속 실패하는 기사 (기사 id → 실패 횟수). [MAX_TRANSLATE_FAILURES]번 넘게 실패하면 건너뛴다.
     * 안 그러면 그 기사 하나가 대기열 맨 앞에서 매번 3분(타임아웃)씩 잡아먹는다.
     * 메모리에만 두므로 재기동하면 한 번 더 시도한다 — 일부러 그렇게 둔다.
     */
    private val translateFailures = ConcurrentHashMap<Long, Int>()

    @PostConstruct
    fun start() {
        if (!enabled) {
            log.info("ℹ️ [Big6] RSS 파이프라인이 꺼져 있습니다 (atleti.news.enabled.club-rss=false)")
            return
        }
        if (twitterEnabled && rapidApiKey == NO_KEY) {
            log.info("ℹ️ [Big6] RAPIDAPI_KEY가 없어 기자 트윗 수집은 건너뜁니다")
        }
        scope.launch { loop() }
    }

    @PreDestroy
    fun stop() = scope.cancel()

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                if (collectDue()) collect()
                if (tweetsDue()) collectTweets()
                val translated = translateNext()
                // 대기열이 비었거나 번역 모델이 없으면 잠깐 쉰다. 번역했으면 바로 다음 기사로
                if (!translated) delay(IDLE_DELAY_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 루프가 죽으면 재기동 전까지 수집이 영영 멈춘다 — 무슨 일이 있어도 다음 턴으로 넘어간다
                log.error("❌ [Big6] 파이프라인 턴 실패: ${e.message}", e)
                delay(IDLE_DELAY_MS)
            }
        }
    }

    private fun collectDue(): Boolean {
        val last = lastCollectedAt ?: return true
        return Duration.between(last, OffsetDateTime.now()) >= COLLECT_INTERVAL
    }

    /** RSS를 읽어 새 기사는 PENDING으로 저장하고, 이미 있는 기사에는 새로 나온 팀만 더한다. */
    suspend fun collect() {
        lastCollectedAt = OffsetDateTime.now()
        val started = System.currentTimeMillis()

        val candidates = mergeByUrl(rssFeedReader.readAll(), OffsetDateTime.now().minusHours(maxAgeHours))
        if (candidates.isEmpty()) return

        val existing = newsArticleRepository.findAllByOriginalUrlIn(candidates.map { it.url }).associateBy { it.originalUrl }

        // 이미 있는 기사: 다른 팀 피드에서 처음 보인 경우 팀만 더한다
        existing.values.forEach { article ->
            val clubs = candidates.first { it.url == article.originalUrl }.clubs
            if (article.clubs.addAll(clubs)) newsArticleRepository.save(article)
        }

        val fresh = candidates.filter { it.url !in existing && !articleDeletionService.isDeleted(it.url) }
        var saved = 0
        fresh.chunked(EXTRACT_CHUNK_SIZE).forEach { chunk ->
            val extracted = try {
                crawlerClient.extract(chunk.map { it.url }).associateBy { it.url }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 크롤러가 죽어 있으면 이번 묶음은 버린다. 저장을 안 했으니 다음 수집 때 다시 들어온다
                log.warn("⚠️ [Big6] /extract 실패 (${chunk.size}건): ${e.message}")
                return@forEach
            }
            chunk.forEach { candidate ->
                val result = extracted[candidate.url]
                if (result?.status != "ok") {
                    log.info("⏭️ [Big6] 본문 추출 실패, 건너뜀: ${candidate.url} (${result?.error})")
                    return@forEach
                }
                try {
                    newsArticleRepository.save(toPendingArticle(candidate, result))
                    saved++
                } catch (e: Exception) {
                    log.error("❌ [Big6] 저장 실패: ${candidate.url} (${e.message})")
                }
            }
        }
        log.info("📥 [Big6] 수집 완료: 후보 ${candidates.size}, 새 기사 ${fresh.size}, 저장 $saved (${System.currentTimeMillis() - started}ms)")
    }

    private fun tweetsDue(): Boolean {
        if (!twitterEnabled || rapidApiKey == NO_KEY) return false
        val last = lastTweetsCollectedAt ?: return true
        return Duration.between(last, OffsetDateTime.now()) >= Duration.ofMinutes(twitterIntervalMinutes)
    }

    /**
     * 기자 39명의 타임라인을 읽어 팀이 붙는 트윗만 PENDING으로 저장한다. 번역은 기사와 같은 대기열을 탄다.
     *
     * RapidAPI 하루 한도(rapidapi.daily.limit)는 [TwitterApiService]가 collection_log 합계로 지킨다.
     * 그래서 한 바퀴 돌 때마다 호출 수를 collection_log에 꼭 남긴다. 30분 간격이면 39 × 48 = 하루 1872회.
     */
    suspend fun collectTweets() {
        lastTweetsCollectedAt = OffsetDateTime.now()
        val started = System.currentTimeMillis()
        val since = OffsetDateTime.now().minusHours(maxAgeHours)
        var calls = 0
        var saved = 0

        try {
            for (handle in ClubJournalists.HANDLES.keys) {
                val tweets = twitterApiService.getTweets(handle)
                calls++
                val candidates = tweets.mapNotNull { tweet ->
                    // 리트윗·답글은 기자 본인의 소식이 아니다. 번역 대기열만 채운다
                    if (tweet.isRetweet || tweet.title.startsWith("@")) return@mapNotNull null
                    if (tweet.publishedAt.isBefore(since)) return@mapNotNull null
                    val clubs = ClubJournalists.clubsFor(handle, tweet.title)
                    if (clubs.isEmpty()) null else tweet to clubs
                }
                if (candidates.isEmpty()) continue

                val existing = newsArticleRepository.findAllByOriginalUrlIn(candidates.map { it.first.url }).map { it.originalUrl }.toSet()
                candidates
                    .filter { (tweet, _) -> tweet.url !in existing && !articleDeletionService.isDeleted(tweet.url) }
                    .forEach { (tweet, clubs) ->
                        try {
                            newsArticleRepository.save(toPendingTweet(tweet, clubs))
                            saved++
                        } catch (e: Exception) {
                            log.error("❌ [Big6] 트윗 저장 실패: ${tweet.url} (${e.message})")
                        }
                    }
            }
        } finally {
            collectionLogRepository.save(
                CollectionLog(
                    status = CollectionStatus.SUCCESS, scannedCount = calls, aiProcessedCount = saved,
                    twitterApiCallCount = calls, executionTimeMs = System.currentTimeMillis() - started,
                )
            )
        }
        log.info("🐦 [Big6] 트윗 수집 완료: 기자 $calls, 저장 $saved (${System.currentTimeMillis() - started}ms)")
    }

    /** PENDING 기사 하나를 번역한다. @return 번역을 해냈으면 true */
    suspend fun translateNext(): Boolean {
        val article = newsArticleRepository.findTop20ByTranslationStatusOrderByPublishedAtDesc(TranslationStatus.PENDING)
            .firstOrNull { (translateFailures[it.id] ?: 0) < MAX_TRANSLATE_FAILURES }
            ?: return false

        val texts = translationInput(article)
        val started = System.currentTimeMillis()
        val translations = try {
            crawlerClient.translate(texts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failures = translateFailures.merge(article.id!!, 1, Int::plus)
            log.warn("⚠️ [Big6] 번역 실패 ($failures/$MAX_TRANSLATE_FAILURES) id=${article.id}: ${e.message}")
            return false
        }
        if (translations == null) {
            log.info("⏸️ [Big6] 번역 모델이 아직 없습니다 (503). 잠시 후 다시 시도합니다")
            return false
        }

        applyTranslation(article, translations)
        newsArticleRepository.save(article)
        translateFailures.remove(article.id)
        log.info("🌐 [Big6] 번역 완료 id=${article.id} (${System.currentTimeMillis() - started}ms)")
        return true
    }

    companion object {
        private val COLLECT_INTERVAL: Duration = Duration.ofMinutes(15)
        private const val IDLE_DELAY_MS = 60_000L
        // 크롤러 /extract 한 번에 보낼 기사 수. 타임아웃(5분) 안에 넉넉히 끝나는 크기
        private const val EXTRACT_CHUNK_SIZE = 10
        private const val MAX_TRANSLATE_FAILURES = 3
        private const val NO_KEY = "NO_KEY" // application.properties의 rapidapi.key 기본값

        /** 트윗은 본문이 곧 제목이다. 핵심 문장이 없어 번역 요청은 한 줄이다 */
        internal fun toPendingTweet(tweet: ScrapedArticle, clubs: Set<Club>) = NewsArticle(
            articleType = ArticleType.SNS_POST,
            sourceName = "X",
            authorName = tweet.authorName,
            originalUrl = tweet.url,
            titleKo = null,
            summaryKo = null,
            contentKo = null,
            contentRaw = tweet.title,
            imageUrl = null,
            publishedAt = tweet.publishedAt,
            category = null,
            clubs = clubs.toMutableSet(),
            titleEn = tweet.title,
            translationStatus = TranslationStatus.PENDING,
        )

        /**
         * 피드 12개의 항목을 URL 하나에 하나로 합치고, [since]보다 오래된 기사는 버린다.
         *
         * 발행 시각 제한은 첫 실행 대책이다. 빈 DB에서 처음 돌면 피드에 걸린 100여 건이 한꺼번에
         * 번역 대기열에 들어가 100분 넘게 밀린다. 지난 기사는 지금 번역해 봐야 볼 사람도 적다.
         * 발행 시각이 없는 항목은 얼마나 오래된지 알 수 없으니 버린다 (BBC·Sky는 항상 붙인다).
         */
        internal fun mergeByUrl(items: List<RssItem>, since: OffsetDateTime): List<Candidate> =
            items.filter { it.publishedAt?.isBefore(since) == false }
                .groupBy { it.url }
                .map { (_, sameUrl) -> Candidate(sameUrl.first(), sameUrl.map { it.club }.toSet()) }

        internal fun toPendingArticle(candidate: Candidate, extracted: ExtractedArticle) = NewsArticle(
            articleType = ArticleType.WEB_ARTICLE,
            sourceName = candidate.item.sourceName,
            authorName = candidate.item.sourceName,
            originalUrl = candidate.url,
            titleKo = null,
            summaryKo = null,
            contentKo = null,
            contentRaw = extracted.body,
            imageUrl = candidate.item.imageUrl,
            publishedAt = candidate.item.publishedAt,
            category = null,
            clubs = candidate.clubs.toMutableSet(),
            // RSS 제목이 편집된 헤드라인이라 더 정확하다. 본문에서 뽑은 제목은 예비로만 쓴다
            titleEn = candidate.item.title.ifBlank { extracted.title },
            keySentencesEn = extracted.keySentences.orEmpty().joinToString("\n").ifBlank { null },
            translationStatus = TranslationStatus.PENDING,
        )

        /** 번역 요청: 첫 줄이 제목, 나머지가 핵심 문장 */
        internal fun translationInput(article: NewsArticle): List<String> =
            listOf(article.titleEn.orEmpty()) + splitSentences(article.keySentencesEn)

        internal fun applyTranslation(article: NewsArticle, translations: List<String>) {
            article.titleKo = translations.firstOrNull()?.ifBlank { null }
            article.summaryKo = translations.drop(1).filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
            article.translationStatus = TranslationStatus.DONE
        }

        fun splitSentences(joined: String?): List<String> =
            joined?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
    }
}

/** 여러 팀 피드에 나온 같은 기사를 하나로 합친 것 */
data class Candidate(val item: RssItem, val clubs: Set<Club>) {
    val url: String get() = item.url
}
