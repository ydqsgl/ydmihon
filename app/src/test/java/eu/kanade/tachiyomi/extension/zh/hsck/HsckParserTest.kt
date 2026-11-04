package eu.kanade.tachiyomi.extension.zh.hsck

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

/**
 * Parsing checks against pages saved from the live site.
 *
 * The interesting cases here are the ones that would silently produce a broken source: the listing
 * is full of paid placements that look like cards, and the playable URL only exists after an
 * exchange with the countdown endpoint.
 */
class HsckParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("hsck/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://333.aiock.cc/")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://333.aiock.cc$url"
        else -> "https://333.aiock.cc/$url"
    }

    @Test
    fun `listing keeps site clips and drops paid placements`() {
        val items = HsckParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 20

        // Every kept item must be a real /v5/ clip page, never an external advert host.
        items.forEach { item ->
            item.url shouldStartWith "https://333.aiock.cc/v5/"
            item.title.isNotBlank() shouldBe true
        }
        items.any { it.url.contains("xmddj") || it.url.contains("index.html?channelCode") } shouldBe false
    }

    @Test
    fun `listing covers come from data-original not the placeholder src`() {
        val items = HsckParser.parseListing(document("listing.html"), ::absolute)
        val withCovers = items.filter { it.thumbnailUrl != null }

        withCovers.shouldNotBeEmpty()
        withCovers.forEach { item ->
            // A placeholder would be a data: URI or an inline gif; real covers are http(s) URLs.
            item.thumbnailUrl.shouldNotBeNull().shouldStartWith("http")
        }
    }

    @Test
    fun `listing titles are the card heading not the advert label`() {
        val items = HsckParser.parseListing(document("listing.html"), ::absolute)

        items.none { it.title.contains("免费观看") || it.title.contains("更多精彩内容") } shouldBe true
    }

    @Test
    fun `search results parse into the same shape as the listing`() {
        val items = HsckParser.parseListing(document("search.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.forEach { it.url shouldStartWith "https://333.aiock.cc/v5/" }
    }

    @Test
    fun `next page link is read from the pager`() {
        val next = HsckParser.findNextPageUrl(document("listing.html"), ::absolute)

        next.shouldNotBeNull()
        next shouldContain "/vodtype/1-2.html"
    }

    @Test
    fun `details page yields title keywords and upload time`() {
        val details = HsckParser.parseDetails(document("detail.html"), ::absolute)

        details.title shouldBe "IRO-057 電車でさわられた五十路母 三枝木玲実"
        details.keywords.shouldNotBeEmpty()
        // The site stamps the upload time in the player footer rather than a <time> tag.
        details.dateText.shouldNotBeNull()
        details.dateText shouldContain "2025-11-26"
    }

    @Test
    fun `play token is extracted from the countdown script`() {
        val token = HsckParser.parsePlayToken(document("detail.html"))

        token.shouldNotBeNull()
        token.id shouldBe "97393"
        token.sid shouldBe "1"
        token.token.length shouldBeGreaterThan 32
    }

    @Test
    fun `play response decodes the base64 m3u8 url`() {
        val body = """{"ok":true,"u":"aHR0cHM6Ly90MzMzLmNkbjIwMjAuY29tL3ZpZGVvL20zdTgvMjAyNS8xMS8yNi8yYzBjZDRjNC9pbmRleC5tM3U4"}"""

        val url = HsckParser.parsePlayResponse(body)

        url.shouldNotBeNull()
        url shouldBe "https://t333.cdn2020.com/video/m3u8/2025/11/26/2c0cd4c4/index.m3u8"
    }

    @Test
    fun `play response without a url is rejected instead of crashing`() {
        HsckParser.parsePlayResponse("""{"ok":false,"err":"请先点击按钮"}""") shouldBe null
        HsckParser.parsePlayResponse("<html>blocked</html>") shouldBe null
        HsckParser.parsePlayResponse("").shouldBe(null)
    }

    @Test
    fun `a page with no countdown script reports no token`() {
        HsckParser.parsePlayToken(Jsoup.parse("<html><body>gone</body></html>")) shouldBe null
    }

    @Test
    fun `listing detection rejects an advert landing page`() {
        HsckParser.isListing("<html><body>广告</body></html>") shouldBe false
        HsckParser.isListing(load("listing.html")) shouldBe true
    }
}
