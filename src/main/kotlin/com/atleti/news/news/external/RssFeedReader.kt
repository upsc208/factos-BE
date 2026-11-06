package com.atleti.news.news.external

import com.atleti.news.news.domain.Club
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * BBC·Sky의 Big6 팀별 RSS를 읽는다 (6팀 × 2매체 = 피드 12개).
 *
 * **여기서는 목록만 만든다.** 본문 수집·정리·핵심 문장은 크롤러 서버(`/extract`)가 한다.
 * RSS는 공식 배포 채널이라 차단 걱정이 없고 가볍다 — 목록 페이지를 긁던 마르카·AS 방식과 다른 점.
 *
 * 피드 하나가 깨져도 나머지는 계속 읽는다. 실패한 피드는 빈 목록으로 치고 로그만 남긴다.
 */
@Service
class RssFeedReader {
    private val log = LoggerFactory.getLogger(RssFeedReader::class.java)

    private val webClient = WebClient.builder()
        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
        // 피드 하나가 100KB 안팎이라 기본 상한(256KB)으로도 되지만 여유를 둔다
        .codecs { it.defaultCodecs().maxInMemorySize(2 * 1024 * 1024) }
        .build()

    suspend fun readAll(): List<RssItem> = coroutineScope {
        FEEDS.map { feed -> async { read(feed) } }.awaitAll().flatten()
    }

    private suspend fun read(feed: Feed): List<RssItem> = try {
        val xml = withTimeout(FEED_TIMEOUT_MS) {
            webClient.get().uri(feed.url).retrieve().bodyToMono(String::class.java).awaitSingle()
        }
        parseFeed(xml, feed)
    } catch (e: TimeoutCancellationException) {
        log.warn("⏱️ RSS 시간 초과: ${feed.url}")
        emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("❌ RSS 읽기 실패: ${feed.url} (${e.message})")
        emptyList()
    }

    data class Feed(val club: Club, val sourceName: String, val url: String)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (compatible; factos/1.0)"
        private const val FEED_TIMEOUT_MS = 15_000L
        private val SEOUL = ZoneId.of("Asia/Seoul")

        val FEEDS: List<Feed> = Club.entries.flatMap { club ->
            listOf(
                Feed(club, "BBC Sport", "https://feeds.bbci.co.uk/sport/football/teams/${club.bbcSlug}/rss.xml"),
                Feed(club, "Sky Sports", "https://www.skysports.com/rss/${club.skyFeedId}"),
            )
        }

        // 본문이 없는 페이지 — 크롤러에 보내 봐야 "본문 추출 실패"로 돌아온다. 싼 단계에서 먼저 거른다
        private val NON_ARTICLE_PATHS = listOf("/watch/video/", "/sport/av/", "/live/", "/videos/")

        internal fun parseFeed(xml: String, feed: Feed): List<RssItem> =
            Jsoup.parse(xml, "", Parser.xmlParser()).select("item").mapNotNull { item ->
                val link = item.selectFirst("link")?.text()?.trim()
                if (link.isNullOrEmpty()) return@mapNotNull null
                val url = normalizeUrl(link)
                if (NON_ARTICLE_PATHS.any { url.contains(it) }) return@mapNotNull null

                val title = item.selectFirst("title")?.text()?.trim()
                if (title.isNullOrEmpty()) return@mapNotNull null

                RssItem(
                    club = feed.club,
                    sourceName = feed.sourceName,
                    url = url,
                    title = title,
                    publishedAt = item.selectFirst("pubDate")?.text()?.let(::parsePubDate),
                    // BBC는 media:thumbnail, Sky는 enclosure에 대표 이미지를 둔다
                    imageUrl = (item.selectFirst("media|thumbnail") ?: item.selectFirst("enclosure"))
                        ?.attr("url")?.takeIf { it.isNotBlank() },
                )
            }

        /**
         * 중복 판단의 기준이 되는 URL. BBC는 RSS 링크에 추적용 쿼리(`?at_medium=RSS&at_campaign=rss`)를 붙인다.
         * 이걸 안 떼면 같은 기사가 다른 URL로 보여서 DB 유니크 검사를 통과해 버린다.
         */
        internal fun normalizeUrl(link: String): String = link.substringBefore('#').substringBefore('?')

        // "Wed, 04 Nov 2026 16:02:08 GMT" — 깨진 값이면 null (발행일 없이도 저장은 한다)
        internal fun parsePubDate(raw: String): OffsetDateTime? = runCatching {
            OffsetDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                .atZoneSameInstant(SEOUL).toOffsetDateTime()
        }.getOrNull()
    }
}

data class RssItem(
    val club: Club,
    val sourceName: String,
    val url: String,
    val title: String,
    val publishedAt: OffsetDateTime?,
    val imageUrl: String?,
)
