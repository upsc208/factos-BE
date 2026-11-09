package com.atleti.news.news.external

import org.slf4j.LoggerFactory
import com.atleti.news.news.domain.Club
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

/** 팀 브리핑 5줄을 Gemini로 만드는 곳. 기사 번역은 크롤러가 한다 */
@Service
class GeminiService(
    @Value("\${gemini.api.key}") private val rawApiKey: String,
    @Value("\${gemini.api.model}") private val model: String,
    @Value("\${gemini.api.base-url}") private val baseUrl: String,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(GeminiService::class.java)

    private val apiKey = rawApiKey.trim()
    private val endpoint = "${baseUrl.trimEnd('/')}/v1/models/$model:generateContent"
    private val webClient = WebClient.builder().build()

    /**
     * 팀 브리핑 한 줄짜리 헤드라인. [team] 기사에서 중요한 것만 0~4개 (0개도 괜찮다 — 채우려고 지어내지 않는다).
     * 이적시장 밖에서는 이적설보다 경기·부상·감독 발언이 중요해서, 이적을 무조건 앞세우지 않는다.
     */
    suspend fun generateHeadlines(newsLines: List<String>, existingHeadlines: List<String>, team: Club): List<String>? {
        if (apiKey == "NO_KEY" || apiKey.isBlank()) {
            log.warn("⚠️ GEMINI_API_KEY가 설정되지 않았습니다.")
            return null
        }
        if (newsLines.isEmpty()) return emptyList()

        val newsBlock = newsLines.joinToString("\n")
        val existingBlock = if (existingHeadlines.isEmpty()) "(none)" else existingHeadlines.joinToString("\n")
        val teamEn = team.englishName
        val teamKo = team.nameKo

        val prompt = """
            You are the front-page editor for a $teamEn ($teamKo) news feed for Korean fans of the Premier League.
            Below are the LATEST news items about $teamEn collected since the previous briefing.
            From them, select the MOST IMPORTANT stories and write AT MOST 4 short Korean headlines (fewer is fine).

            RULES:
            1. These headlines are a live "breaking news" ticker. Cover only the most important / newest developments about $teamEn — ignore minor or repetitive items, and items that are mainly about other clubs.
            2. If nothing is genuinely important or newsworthy, return an EMPTY list. Do NOT invent or pad headlines just to fill space. It is perfectly fine to return zero headlines.
            3. Return between 0 and 4 headlines, chosen strictly by importance. List the MOST important first.
            4. [Importance priority] Rank items in this order:
               (a) Official club announcements (signings/departures made official, contracts, manager changes).
               (b) Match results and decisive performances, injuries and team news affecting upcoming matches.
               (c) Manager or key player quotes, and transfer news or rumors reported by a credible journalist/outlet (e.g., BBC Sport, Sky Sports, David Ornstein, Fabrizio Romano). Weigh the [출처] when judging credibility.
            5. Each headline: informative Korean newspaper style, target 30-50 characters (max 50). Long enough to carry WHO + WHAT with a concrete detail (opponent, score, injury length, source, status). Avoid one-word or overly terse headlines. NO ending punctuation.
            6. Use plain style endings (평어체) or noun-ending style. Write player and club names the way Korean football media usually do. e.g., "$teamKo, 리그 3연승으로 4위 도약", "주전 수비수 햄스트링 부상, 3주 결장 예상".
            7. DO NOT repeat or paraphrase any of the [Already Posted Headlines]. Produce genuinely new angles only.

            [Strict Grounding - MOST IMPORTANT]
            8. You are RE-HEADLINING existing news items, NOT writing new stories. Every headline MUST be directly and fully supported by ONE specific item in [Latest News].
            9. Do NOT change the factual meaning of an item. Never turn a goal, injury, quote, or match report into a transfer/signing story (or vice versa).
            10. NEVER use outside or background knowledge. Do NOT assume who currently plays for which club, who is a transfer target, or any squad detail not stated in the item itself. Example of a FORBIDDEN hallucination: an item mentions a CURRENT $teamEn player, and you invent that $teamEn is "trying to sign him" — that is completely wrong. Only restate what the item actually says.
            11. Do NOT merge multiple items into a single fabricated narrative. One headline reflects one item's actual content.
            12. If you cannot write a headline that faithfully matches an item, DROP it. Fewer (or zero) accurate headlines is always better than one distorted or invented one.

            [Already Posted Headlines]
            $existingBlock

            [Latest News]
            $newsBlock

            Respond ONLY in valid JSON (headlines may be an empty array):
            {
              "headlines": ["headline 1", "headline 2"]
            }
        """.trimIndent()

        return try {
            val requestBody = mapOf(
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt))))
            )
            callGemini(requestBody).let { parseHeadlineResponse(it) }
        } catch (e: TimeoutCancellationException) {
            log.error("❌ [헤드라인] Gemini 응답 시간 초과 (${REQUEST_TIMEOUT_MS}ms)")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("❌ Gemini 헤드라인 생성 오류: ${e.message}", e)
            null
        }
    }

    private fun parseHeadlineResponse(response: Map<String, Any>): List<String>? {
        return try {
            val candidates = response["candidates"] as? List<*> ?: return null
            val firstCandidate = candidates.firstOrNull() as? Map<*, *> ?: return null
            val content = firstCandidate["content"] as? Map<*, *> ?: return null
            val parts = content["parts"] as? List<*> ?: return null
            val firstPart = parts.firstOrNull() as? Map<*, *> ?: return null
            val text = (firstPart["text"] as? String)?.trim() ?: return null

            var cleanJson = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            cleanJson = cleanJson.replace(Regex("\\\\(?![u\"\\\\/bfnrt])"), "")

            objectMapper.readValue<HeadlineResponse>(cleanJson).headlines
        } catch (e: Exception) {
            log.warn("⚠️ 헤드라인 JSON 파싱 실패: ${e.message}", e)
            null
        }
    }

    private suspend fun callGemini(requestBody: Map<String, Any>): Map<String, Any> =
        withTimeout(REQUEST_TIMEOUT_MS) {
            webClient.post()
                .uri(endpoint)
                .header(API_KEY_HEADER, apiKey)
                .bodyValue(requestBody)
                .retrieve()
                .awaitBody<Map<String, Any>>()
        }

    private data class HeadlineResponse(
        @JsonProperty("headlines") val headlines: List<String> = emptyList()
    )

    companion object {
        private const val API_KEY_HEADER = "x-goog-api-key"
        private const val REQUEST_TIMEOUT_MS = 120_000L
    }
}
