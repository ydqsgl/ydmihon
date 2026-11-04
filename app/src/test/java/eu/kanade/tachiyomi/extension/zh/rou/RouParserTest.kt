package eu.kanade.tachiyomi.extension.zh.rou

import io.kotest.matchers.collections.shouldBeEmpty
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
 * The stream URL only exists inside a shifted-Base64 RSC payload, so that decoder is the part most
 * worth pinning down: a silent regression there shows up as "source error" on every clip.
 */
class RouParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("rou/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://rou.video/v")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("/") -> "https://rou.video$url"
        else -> "https://rou.video/$url"
    }

    @Test
    fun `listing reads clip cards with titles and covers`() {
        val items = RouParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 20

        items.take(5).forEach { item ->
            item.url shouldStartWith "https://rou.video/v/"
            item.title.isNotBlank() shouldBe true
            item.thumbnailUrl.shouldNotBeNull().shouldStartWith("https://")
        }
    }

    @Test
    fun `listing ignores links without a card heading`() {
        val items = RouParser.parseListing(document("listing.html"), ::absolute)

        // The hero banner and the pager also link to /v/ pages, but carry no <h3> title.
        items.size shouldBe items.map { it.url }.distinct().size
        items.none { it.url.contains("?") } shouldBe true
    }

    @Test
    fun `next page comes from the rel next link`() {
        val next = RouParser.findNextPageUrl(document("listing.html"), ::absolute)

        next.shouldNotBeNull()
        next shouldContain "page=2"
        // The nav also prints the last page (2175); that is not a forward step.
        (next.contains("2175")) shouldBe false
    }

    @Test
    fun `series page is not a clip listing`() {
        // /series links to /s/ entries only, so a clip parse must come back empty rather than
        // silently treating series links as playable clips.
        RouParser.parseListing(document("series.html"), ::absolute).shouldBeEmpty()
    }

    @Test
    fun `details page exposes title tags and poster`() {
        val details = RouParser.parseDetails(document("detail.html"))

        details.title.isNotBlank() shouldBe true
        details.tags.shouldNotBeEmpty()
        details.coverUrl.shouldNotBeNull().shouldStartWith("https://")
        details.uploadDate.shouldNotBeNull() shouldContain "2026-10-19"
    }

    @Test
    fun `playback payload decodes into the hls endpoint`() {
        val url = RouParser.parseVideoUrl(document("detail.html"))

        url.shouldNotBeNull()
        url shouldBe "/api/hls/cmvexc0cz0044muzsiautj6mq"
    }

    @Test
    fun `payload decoder reverses the byte shift`() {
        // "{\"videoUrl\":\"/api/hls/x\",\"thumbVTTUrl\":null}" shifted up by 7 and Base64'd.
        val json = """{"videoUrl":"/api/hls/x","thumbVTTUrl":null}"""
        val shifted = json.map { (it.code + 7).toChar() }.joinToString("")
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(shifted.toByteArray(Charsets.ISO_8859_1))

        RouParser.decodePayload(encoded, 7) shouldBe json
    }

    @Test
    fun `payload decoder refuses a wrong shift instead of emitting garbage`() {
        // A shift larger than the smallest character code underflows and must be rejected.
        val json = """{"videoUrl":"/api/hls/x"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(json.toByteArray())

        RouParser.decodePayload(encoded, 9_999) shouldBe null
    }

    @Test
    fun `payload decoder rejects non-base64 input`() {
        RouParser.decodePayload("not base64 !!", 1) shouldBe null
        RouParser.decodePayload("", 1) shouldBe null
    }

    @Test
    fun `a page without playback data reports no url`() {
        val stripped = document("detail.html").clone()
        stripped.select("script").remove()

        RouParser.parseVideoUrl(stripped) shouldBe null
    }

    @Test
    fun `an unrelated page yields no clips`() {
        RouParser.parseListing(Jsoup.parse("<html><body>nothing</body></html>"), ::absolute)
            .shouldBeEmpty()
    }
}
