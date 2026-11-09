package eu.kanade.tachiyomi.extension.zh.siwa

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

/**
 * Parsing checks against pages saved from the live site.
 *
 * The playback fixture is a real `GET /api/videos/<code>` response: the payload is time-limited but
 * the ciphertext itself is static, so decrypting it is a stable assertion on the key-derivation
 * rule rather than on the site being up.
 */
class SiwaParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("siwa/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String, baseUrl: String = "https://siwa.cam/"): Document =
        Jsoup.parse(load(name), baseUrl)

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://siwa.cam$url"
        else -> "https://siwa.cam/$url"
    }

    @Test
    fun `listing reads every card in the grid`() {
        val items = SiwaParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBe 12
        items.forEach { it.url shouldStartWith "https://siwa.cam/videos/" }
        items.forEach { it.thumbnailUrl.shouldNotBeNull() }
        items.first().title shouldStartWith "维纳斯女王棕色油亮连身丝袜"
    }

    @Test
    fun `listing page is not mistaken for a details page`() {
        SiwaParser.isListing(load("listing.html")) shouldBe true
        SiwaParser.isListing(load("detail.html")) shouldBe false
    }

    @Test
    fun `pager follows rel next`() {
        SiwaParser.findNextPageUrl(document("listing.html"), ::absolute) shouldBe
            "https://siwa.cam?page=2"
    }

    @Test
    fun `details come from the h1 and json-ld`() {
        val details = SiwaParser.parseDetails(
            document("detail.html", "https://siwa.cam/videos/e14348cec02ee9146e68b07b2e0970a9"),
        )

        details.title shouldStartWith "维纳斯女王棕色油亮连身丝袜"
        details.genre.shouldNotBeNull()
        details.genre shouldContain "丝袜"
        details.thumbnailUrl shouldBe "https://videothumbs.me/vz9c1yu56g53.jpg"
        details.dateUpload shouldBeGreaterThan 0L
    }

    @Test
    fun `player call names the embed host`() {
        val player = SiwaParser.parsePlayer(document("detail.html"))

        player.shouldNotBeNull()
        player.url shouldBe "https://bysetayico.com/e/vz9c1yu56g53"
        player.kind shouldBe "iframe"
    }

    @Test
    fun `embed code is the last path segment`() {
        SiwaParser.parseEmbedCode("https://bysetayico.com/e/vz9c1yu56g53") shouldBe "vz9c1yu56g53"
    }

    @Test
    fun `playback payload decrypts to the master playlist`() {
        val stream = SiwaParser.decryptPlayback(load("api.json"))

        stream.shouldBeInstanceOf<SiwaParser.Stream.Success>()
        (stream as SiwaParser.Stream.Success).url shouldContain "/vz9c1yu56g53_x/master.m3u8"
    }

    @Test
    fun `playback rejects a version that does not name a key pair`() {
        // The live API sends the version as a JSON string, so the rewrite keeps that shape.
        val body = load("api.json").replace("\"version\":\"14\"", "\"version\":\"21\"")

        SiwaParser.decryptPlayback(body).shouldBeInstanceOf<SiwaParser.Stream.Failure>()
    }
}
