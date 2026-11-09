package com.atleti.news.event

import com.atleti.news.match.domain.FootballDataService
import com.atleti.news.match.domain.UpcomingMatch
import com.atleti.news.match.domain.UpcomingMatchRepository
import com.atleti.news.news.domain.Club
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Range
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate

/**
 * 일정 저장 → 같은 트랜잭션의 아웃박스 → Redis Stream (docs/events.md).
 * Redis가 필요한 테스트는 Redis가 없으면 건너뛴다 (CI에는 있다).
 */
@SpringBootTest
@ActiveProfiles("h2")
class OutboxFlowTest {

    @Autowired private lateinit var footballDataService: FootballDataService
    @Autowired private lateinit var upcoming: UpcomingMatchRepository
    @Autowired private lateinit var outbox: OutboxEventRepository
    @Autowired private lateinit var writer: OutboxWriter
    @Autowired private lateinit var relay: OutboxRelay
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var connectionFactory: RedisConnectionFactory
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var tx: TransactionTemplate

    private val stream = OutboxRelay.streamOf(MatchEvents.SCHEDULED)

    @BeforeEach
    fun setUp() {
        outbox.deleteAll()
        tx.executeWithoutResult { upcoming.deleteAllByClub(Club.CHELSEA.id) }
        runCatching { redis.delete(stream) }
    }

    private fun redisAvailable() = runCatching { connectionFactory.connection.use { it.ping() } }.isSuccess

    private fun m(id: String, kickoff: String) =
        UpcomingMatch(club = Club.CHELSEA.id, matchId = id, opponent = "Leeds", matchDate = kickoff, isHome = true, tournament = "프리미어리그")

    @Test
    fun `일정을 바꾸면 같은 트랜잭션에서 아웃박스에 이벤트가 적힌다`() {
        val written = footballDataService.replaceUpcoming(Club.CHELSEA, listOf(m("e1", "2099-11-22T00:00:00+09:00")))

        assertThat(written).isEqualTo(1)
        val event = outbox.findAll().single()
        assertThat(event.eventType).isEqualTo(MatchEvents.SCHEDULED)
        assertThat(event.aggregateId).isEqualTo("e1")
        assertThat(event.publishedAt).isNull()
        assertThat(objectMapper.readTree(event.payload)["club"].asText()).isEqualTo("chelsea")

        // 같은 목록으로 다시 바꾸면 새 이벤트가 없다
        assertThat(footballDataService.replaceUpcoming(Club.CHELSEA, listOf(m("e1", "2099-11-22T00:00:00+09:00")))).isZero()
        assertThat(outbox.count()).isEqualTo(1)
    }

    @Test
    fun `킥오프가 바뀌면 updated가 적힌다`() {
        footballDataService.replaceUpcoming(Club.CHELSEA, listOf(m("u1", "2099-11-22T00:00:00+09:00")))
        val written = footballDataService.replaceUpcoming(Club.CHELSEA, listOf(m("u1", "2099-11-22T02:30:00+09:00")))

        assertThat(written).isEqualTo(1)
        val updated = outbox.findAllByEventTypeOrderByIdAsc(MatchEvents.UPDATED).single()
        assertThat(objectMapper.readTree(updated.payload)["previousKickoff"].asText()).isEqualTo("2099-11-22T00:00:00+09:00")
    }

    @Test
    fun `아웃박스는 호출한 쪽의 트랜잭션 안에서만 쓸 수 있고, 롤백되면 같이 사라진다`() {
        assertThatThrownBy { writer.record(MatchEvents.SCHEDULED, "x", mapOf("a" to 1)) }
            .isInstanceOf(IllegalTransactionStateException::class.java)

        runCatching {
            tx.executeWithoutResult {
                writer.record(MatchEvents.SCHEDULED, "x", mapOf("a" to 1))
                error("저장 도중 실패")
            }
        }
        assertThat(outbox.count()).isZero()
    }

    @Test
    fun `중계는 아웃박스를 id 순으로 스트림에 올리고 보낸 표시를 한다`() {
        assumeTrue(redisAvailable(), "Redis가 없어 건너뜁니다")
        footballDataService.replaceUpcoming(Club.CHELSEA, listOf(m("e1", "2099-11-22T00:00:00+09:00"), m("e2", "2099-11-29T00:00:00+09:00")))

        assertThat(relay.relayOnce()).isEqualTo(2)
        assertThat(relay.relayOnce()).isZero() // 이미 보낸 것은 다시 안 보낸다

        val records = redis.opsForStream<String, String>().range(stream, Range.unbounded())!!
        assertThat(records).hasSize(2)
        val first = records[0].value
        assertThat(first["type"]).isEqualTo(MatchEvents.SCHEDULED)
        assertThat(first["aggregateId"]).isEqualTo("e1")
        assertThat(first["id"]).isEqualTo(outbox.findAll().minOf { it.id!! }.toString())
        assertThat(objectMapper.readTree(first["payload"])["kickoff"].asText()).isEqualTo("2099-11-22T00:00:00+09:00")
        assertThat(outbox.findAll()).allSatisfy { assertThat(it.publishedAt).isNotNull() }
    }
}
