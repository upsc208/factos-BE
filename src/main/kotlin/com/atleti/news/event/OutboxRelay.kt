package com.atleti.news.event

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 아웃박스 → Redis Stream 중계 (docs/events.md).
 *
 * 몇 초마다 아직 안 보낸 이벤트를 id 순으로 읽어 [STREAM_PREFIX]`{도메인}` 스트림에 XADD하고 보낸 시각을 적는다.
 * - Redis가 죽어 있으면 이번 턴은 그만두고 다음 턴에 이어서 보낸다 (아웃박스에 그대로 남아 있다)
 * - XADD 뒤 DB 표시 전에 죽으면 다음 턴에 한 번 더 나간다 → "최소 한 번". 소비자는 `id`로 거른다
 * - 스트림은 약 [MAX_LEN]개만 남긴다 (오래된 것부터 잘림). 소비자가 그보다 오래 멈춰 있으면 놓친다
 *
 * 스프링 @Scheduled 스레드(1개)를 쓰지 않는다 — Redis 타임아웃으로 다른 배치를 붙잡지 않게 (LiveMatchPoller와 같은 이유).
 */
@Component
class OutboxRelay(
    private val repository: OutboxEventRepository,
    private val redis: StringRedisTemplate,
    @Value("\${atleti.events.relay.enabled:true}") private val enabled: Boolean,
    @Value("\${atleti.events.relay.interval-ms:2000}") private val intervalMs: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val redisDown = AtomicBoolean(false)
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "outbox-relay").apply { isDaemon = true } }

    @PostConstruct
    fun start() {
        if (!enabled) return
        ticker.scheduleWithFixedDelay({ runCatching { relayOnce() }.onFailure { log.warn("⚠️ [Outbox] 중계 실패: {}", it.message) } },
            intervalMs, intervalMs, TimeUnit.MILLISECONDS)
    }

    @PreDestroy
    fun stop() = ticker.shutdownNow()

    /** 한 번 돌린다. @return 보낸 이벤트 수 */
    fun relayOnce(): Int {
        var sent = 0
        for (event in repository.findTop100ByPublishedAtIsNullOrderByIdAsc()) {
            try {
                redis.opsForStream<String, String>().add(
                    streamOf(event.eventType),
                    mapOf(
                        "id" to event.id.toString(),
                        "type" to event.eventType,
                        "aggregateId" to event.aggregateId,
                        "occurredAt" to event.createdAt.toString(),
                        "payload" to event.payload,
                    ),
                    XAddOptions.maxlen(MAX_LEN).approximateTrimming(true),
                )
            } catch (e: Exception) {
                if (redisDown.compareAndSet(false, true)) log.warn("⚠️ [Outbox] Redis에 못 올립니다 — 아웃박스에 쌓아 두고 다시 시도합니다: {}", e.message)
                break
            }
            event.publishedAt = OffsetDateTime.now()
            repository.save(event)
            sent++
        }
        if (sent > 0 && redisDown.compareAndSet(true, false)) log.info("✅ [Outbox] Redis가 돌아와 다시 보냅니다")
        return sent
    }

    /** 보낸 지 오래된 아웃박스 행 정리 (스트림에는 이미 있고, DB에 계속 쌓을 이유가 없다) */
    @Scheduled(cron = "0 40 4 * * *", zone = "Asia/Seoul")
    fun cleanUp() {
        val removed = repository.deletePublishedBefore(OffsetDateTime.now().minusDays(KEEP_DAYS))
        if (removed > 0) log.info("🧹 [Outbox] 보낸 이벤트 {}건 정리", removed)
    }

    companion object {
        const val STREAM_PREFIX = "events:"
        const val MAX_LEN = 10_000L
        private const val KEEP_DAYS = 7L

        /** `match.scheduled` → `events:match` (도메인별 스트림 하나) */
        fun streamOf(eventType: String) = STREAM_PREFIX + eventType.substringBefore('.')
    }
}
