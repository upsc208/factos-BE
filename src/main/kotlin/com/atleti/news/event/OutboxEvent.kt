package com.atleti.news.event

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * 트랜잭셔널 아웃박스 — 밖으로 내보낼 이벤트를 **데이터를 바꾸는 같은 트랜잭션 안에서** 이 테이블에 적는다.
 * [OutboxRelay]가 따로 읽어 Redis Stream에 올린다 (docs/events.md).
 *
 * 왜 바로 Redis에 쓰지 않나:
 * - DB 저장은 성공했는데 Redis가 죽어 있으면 이벤트가 사라진다
 * - Redis에 먼저 썼는데 DB가 롤백되면 일어나지 않은 일을 알린 셈이 된다
 * 같은 트랜잭션에 적어 두면 둘 다 생기지 않는다. 대가는 "최소 한 번" 전달 — 소비자는 id로 중복을 거른다.
 */
@Entity
@Table(name = "outbox_event", indexes = [Index(name = "idx_outbox_unpublished", columnList = "published_at, id")])
class OutboxEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    // 예: match.scheduled (MatchEvents)
    @Column(name = "event_type", nullable = false, length = 50)
    val eventType: String,

    // 이벤트 대상의 키 (경기면 FotMob matchId)
    @Column(name = "aggregate_id", nullable = false, length = 64)
    val aggregateId: String,

    @Column(nullable = false, columnDefinition = "TEXT")
    val payload: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "published_at")
    var publishedAt: OffsetDateTime? = null,
)

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {
    fun findTop100ByPublishedAtIsNullOrderByIdAsc(): List<OutboxEvent>

    fun findAllByEventTypeOrderByIdAsc(eventType: String): List<OutboxEvent>

    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt IS NOT NULL AND e.publishedAt < :before")
    fun deletePublishedBefore(@Param("before") before: OffsetDateTime): Int
}

/** 아웃박스에 이벤트를 적는다. **호출하는 쪽의 트랜잭션 안에서만** 부를 수 있다 (MANDATORY) */
@Component
class OutboxWriter(
    private val repository: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(eventType: String, aggregateId: String, payload: Any) {
        repository.save(OutboxEvent(eventType = eventType, aggregateId = aggregateId, payload = objectMapper.writeValueAsString(payload)))
    }
}
