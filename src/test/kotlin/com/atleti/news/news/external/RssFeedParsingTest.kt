package com.atleti.news.news.external

import com.atleti.news.news.domain.Club
import com.atleti.news.news.external.RssFeedReader.Companion.FEEDS
import com.atleti.news.news.external.RssFeedReader.Companion.normalizeUrl
import com.atleti.news.news.external.RssFeedReader.Companion.parseFeed
import com.atleti.news.news.external.RssFeedReader.Companion.parsePubDate
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * BBC·Sky RSS 해석 검증. 샘플은 2026-11-05 실제 피드에서 그대로 옮겼다.
 *
 * 가장 중요한 건 [normalizeUrl]이다 — URL이 곧 중복 판단 키라서, 추적 쿼리를 못 떼면
 * 같은 기사가 15분마다 새 기사로 들어와 번역 대기열을 채운다.
 */
class RssFeedParsingTest {

    private val bbc = RssFeedReader.Feed(Club.ARSENAL, "BBC Sport", "https://feeds.bbci.co.uk/sport/football/teams/arsenal/rss.xml")
    private val sky = RssFeedReader.Feed(Club.MAN_CITY, "Sky Sports", "https://www.skysports.com/rss/11679")

    private val bbcXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss xmlns:media="http://search.yahoo.com/mrss/" version="2.0"><channel>
        <item>
            <title><![CDATA[What are rules on loan players in Champions League?]]></title>
            <description><![CDATA[BBC Sport's Ask Me Anything team explains the rules]]></description>
            <link>https://www.bbc.co.uk/sport/football/articles/cmp8g4jy2pgpo?at_medium=RSS&amp;at_campaign=rss</link>
            <guid isPermaLink="false">https://www.bbc.co.uk/sport/football/articles/cmp8g4jy2pgpo#0</guid>
            <pubDate>Wed, 04 Nov 2026 16:02:08 GMT</pubDate>
            <media:thumbnail width="240" height="135" url="https://ichef.bbci.co.uk/ace/standard/240/cpsprodpb/202f/live/46fd4a90.jpg"/>
        </item>
        <item>
            <title><![CDATA[Arsenal v Bayern Munich: Live]]></title>
            <link>https://www.bbc.co.uk/sport/football/live/c1234567?at_medium=RSS&amp;at_campaign=rss</link>
            <pubDate>Wed, 04 Nov 2026 19:00:00 GMT</pubDate>
        </item>
        </channel></rss>
    """.trimIndent()

    private val skyXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0"><channel>
        <item>
           <title><![CDATA['It's not a concern' - Maresca backs Donnarumma and praises Haaland heroics ]]></title>
           <link>https://www.skysports.com/watch/video/13608227/enzo-maresca-backs-gianluigi-donnarumma</link>
           <pubDate>Thu, 05 Nov 2026 06:26:00 GMT</pubDate>
           <category>Video</category>
        </item>
        <item>
           <title><![CDATA[Haaland nets Man City winner to haunt RB Leipzig again]]></title>
           <link>https://www.skysports.com/football/rb-leipzig-vs-manchester-city/report/577666</link>
           <pubDate>Wed, 04 Nov 2026 22:00:00 GMT</pubDate>
           <category>Report</category>
           <enclosure type="image/jpg" url="https://e0.365dm.com/26/11/1920x1080/skysports-haaland_7397566.jpg?20261105063723" length="123456" />
        </item>
        </channel></rss>
    """.trimIndent()

    @Test
    @DisplayName("BBC 기사: 추적 쿼리를 떼고 제목·시각·썸네일을 읽는다")
    fun testBbcItem() {
        val items = parseFeed(bbcXml, bbc)

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(Club.ARSENAL, item.club)
        assertEquals("BBC Sport", item.sourceName)
        assertEquals("https://www.bbc.co.uk/sport/football/articles/cmp8g4jy2pgpo", item.url)
        assertEquals("What are rules on loan players in Champions League?", item.title)
        assertEquals(OffsetDateTime.parse("2026-11-05T01:02:08+09:00"), item.publishedAt)
        assertEquals("https://ichef.bbci.co.uk/ace/standard/240/cpsprodpb/202f/live/46fd4a90.jpg", item.imageUrl)
    }

    @Test
    @DisplayName("본문 없는 페이지(라이브·영상)는 목록 단계에서 뺀다")
    fun testSkipsNonArticles() {
        assertEquals(listOf("https://www.bbc.co.uk/sport/football/articles/cmp8g4jy2pgpo"), parseFeed(bbcXml, bbc).map { it.url })
        assertEquals(listOf("https://www.skysports.com/football/rb-leipzig-vs-manchester-city/report/577666"), parseFeed(skyXml, sky).map { it.url })
    }

    @Test
    @DisplayName("Sky 기사: 대표 이미지는 enclosure에서, 이미지 URL의 쿼리는 그대로 둔다")
    fun testSkyItem() {
        val item = parseFeed(skyXml, sky).single()

        assertEquals(Club.MAN_CITY, item.club)
        assertEquals("Haaland nets Man City winner to haunt RB Leipzig again", item.title)
        assertEquals(OffsetDateTime.parse("2026-11-05T07:00+09:00"), item.publishedAt)
        assertEquals("https://e0.365dm.com/26/11/1920x1080/skysports-haaland_7397566.jpg?20261105063723", item.imageUrl)
    }

    @Test
    @DisplayName("URL 정규화: 쿼리와 해시를 뗀다")
    fun testNormalizeUrl() {
        assertEquals(
            "https://www.bbc.co.uk/sport/football/articles/cmp8g4jy2pgpo",
            normalizeUrl("https://www.bbc.co.uk/sport/football/articles/cmp8g4jy2pgpo?at_medium=RSS&at_campaign=rss#0")
        )
    }

    @Test
    @DisplayName("깨진 발행일은 null (기사는 버리지 않는다)")
    fun testBadPubDate() {
        assertNull(parsePubDate("yesterday"))
    }

    @Test
    @DisplayName("피드는 6팀 × 2매체 = 12개")
    fun testFeedList() {
        assertEquals(12, FEEDS.size)
        assertEquals(Club.entries.toSet(), FEEDS.map { it.club }.toSet())
    }
}
