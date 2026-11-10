package com.atleti.news.news.external

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody

/**
 * 크롤러 서버(factos-CRAWLER, FastAPI) 호출.
 *
 * 같은 서버에서 도는 컨테이너라 `localhost:8000`으로 부른다 (외부 포트는 닫혀 있다).
 * 두 엔드포인트는 걸리는 시간이 전혀 달라서 일부러 따로 부른다:
 * - `/extract`: 수집 + 본문 정리 + 핵심 문장. 기사 여러 개에 몇 초
 * - `/translate`: 제목·핵심 문장 번역. ARM 서버 실측으로 기사 하나(4문장)에 평균 63초, 최대 84초
 */
@Service
class CrawlerClient(
    @Value("\${atleti.source.crawler-base-url}") baseUrl: String,
    private val meters: MeterRegistry,
) {
    private val webClient = WebClient.builder()
        .baseUrl(baseUrl)
        // 본문이 기사당 수 KB라 묶음 응답이 기본 상한(256KB)을 넘을 수 있다
        .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
        .build()

    suspend fun extract(urls: List<String>): List<ExtractedArticle> = timed("extract") {
        withTimeout(EXTRACT_TIMEOUT_MS) {
            webClient.post().uri("/extract")
                .bodyValue(ExtractRequest(urls))
                .retrieve()
                .awaitBody<ExtractResponse>()
        }.results
    }

    /**
     * @return 입력과 같은 순서의 번역. 번역 모델이 없을 때(503)는 null — 기사는 PENDING으로 두고 다음 주기에 다시 시도한다
     */
    suspend fun translate(texts: List<String>): List<String>? = timed("translate") {
        try {
            withTimeout(TRANSLATE_TIMEOUT_MS) {
                webClient.post().uri("/translate")
                    .bodyValue(TranslateRequest(texts.map { it.take(MAX_TEXT_LENGTH) }))
                    .retrieve()
                    .awaitBody<TranslateResponse>()
            }.translations
        } catch (e: WebClientResponseException.ServiceUnavailable) {
            null
        }
    }

    /**
     * 걸린 시간·결과를 `factos_crawler_seconds{op,outcome}`로 남긴다 (Grafana "서버 상태").
     * outcome: ok · unavailable(번역 모델 없음 → null) · error(예외, 그대로 다시 던진다)
     */
    private suspend fun <T> timed(op: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        var outcome = "error"
        try {
            return block().also { outcome = if (it == null) "unavailable" else "ok" }
        } finally {
            Timer.builder("factos.crawler").tag("op", op).tag("outcome", outcome)
                .register(meters).record(System.nanoTime() - start, TimeUnit.NANOSECONDS)
        }
    }

    companion object {
        // 기사 수십 개 + 막히면 브라우저 수집까지 고려한 상한
        private const val EXTRACT_TIMEOUT_MS = 5 * 60_000L
        // 크롤러는 번역을 한 번에 하나씩만 하므로(lock), 앞 요청을 기다리는 시간까지 포함
        private const val TRANSLATE_TIMEOUT_MS = 3 * 60_000L
        // 크롤러 스키마의 문장당 상한 (app/schemas/translate.py)
        private const val MAX_TEXT_LENGTH = 2000
    }
}

// 크롤러와의 계약 (factos-CRAWLER app/schemas/). JSON 필드는 camelCase
data class ExtractRequest(val urls: List<String>)
data class ExtractResponse(val results: List<ExtractedArticle>)
data class ExtractedArticle(
    val url: String,
    val status: String, // "ok" | "failed"
    val title: String? = null,
    val body: String? = null,
    val keySentences: List<String>? = null,
    val error: String? = null,
)

data class TranslateRequest(val texts: List<String>)
data class TranslateResponse(val translations: List<String>)
