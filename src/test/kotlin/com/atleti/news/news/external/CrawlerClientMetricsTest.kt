package com.atleti.news.news.external

import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/** 크롤러 호출 시간·결과가 `factos.crawler{op,outcome}`로 남는다 (Grafana "서버 상태"의 크롤러 패널) */
class CrawlerClientMetricsTest {
    private var translateStatus = 200
    private val server = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/translate") { ex ->
            val body = """{"translations":["안녕"]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            if (translateStatus == 200) {
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            } else {
                ex.sendResponseHeaders(translateStatus, -1); ex.close()
            }
        }
        start()
    }
    private val meters = SimpleMeterRegistry()
    private val client = CrawlerClient("http://localhost:${server.address.port}", meters)

    @AfterEach
    fun stop() = server.stop(0)

    private fun count(outcome: String) = meters.find("factos.crawler").tags("op", "translate", "outcome", outcome).timer()?.count() ?: 0

    @Test
    fun `성공 · 번역 모델 없음(503) · 오류를 나눠 센다`() = runBlocking<Unit> {
        assertThat(client.translate(listOf("hello"))).containsExactly("안녕")

        translateStatus = 503
        assertThat(client.translate(listOf("hello"))).isNull()

        translateStatus = 500
        assertThatThrownBy { runBlocking { client.translate(listOf("hello")) } }.isNotNull()

        assertThat(count("ok")).isEqualTo(1)
        assertThat(count("unavailable")).isEqualTo(1)
        assertThat(count("error")).isEqualTo(1)
    }
}
