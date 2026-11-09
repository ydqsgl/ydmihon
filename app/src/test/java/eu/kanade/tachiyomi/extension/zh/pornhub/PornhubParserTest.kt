package eu.kanade.tachiyomi.extension.zh.pornhub

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

/**
 * Parsing checks against pages saved from the live site.
 *
 * The fixtures are trimmed real pages, not hand-written markup: the browse page's cards carry two
 * `view_video.php` anchors (thumbnail + title) and the title only exists in `div.title`, so a
 * simplified fixture would keep passing while the real listing parsed to zero items.
 */
class PornhubParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("pornhub/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://www.pornhub.com/video")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://www.pornhub.com$url"
        else -> "https://www.pornhub.com/$url"
    }

    @Test
    fun `listing reads video cards`() {
        val items = PornhubParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 1
        items.forEach { it.url shouldContain "view_video.php" }
        items.forEach { it.title.isNotBlank() shouldBe true }
        items.first().thumbnailUrl.shouldNotBeNull()
    }

    @Test
    fun `listing title comes from the card title link`() {
        val items = PornhubParser.parseListing(document("listing.html"), ::absolute)
        // The thumbnail anchor has no title attribute; the title lives in `div.title > a`.
        items.first().title shouldBe
            "Quick fuck in the kitchen before our parents get home! Creampie"
    }

    @Test
    fun `listing skips the duplicate thumbnail anchor of the same card`() {
        val items = PornhubParser.parseListing(document("listing.html"), ::absolute)
        items.size shouldBe 4
        items.map { it.url }.toSet().size shouldBe items.size
    }

    @Test
    fun `listing thumbnail is the video thumb image`() {
        val items = PornhubParser.parseListing(document("listing.html"), ::absolute)
        items.first().thumbnailUrl shouldContain "phncdn.com"
    }

    @Test
    fun `listing finds next page`() {
        val next = PornhubParser.findNextPageUrl(document("listing.html"), 1, ::absolute)
        next.shouldNotBeNull()
        next shouldContain "page=2"
    }

    @Test
    fun `listing next page skips current page`() {
        // Build a page-3 listing: shift every pagination anchor by +2 so the anchors read
        // 4,5,6,12. From page 3 the smallest page > 3 is 4 (a stale page-2 link would be wrong).
        val html = load("listing.html").replace(Regex("""page=(\d+)""")) {
            "page=${it.groupValues[1].toInt() + 2}"
        }
        val doc = Jsoup.parse(html, "https://www.pornhub.com/video")
        val next = PornhubParser.findNextPageUrl(doc, 3, ::absolute)
        next.shouldNotBeNull()
        next shouldContain "page=4"
    }

    @Test
    fun `listing page is recognised as a listing`() {
        // The mirror probe depends on this predicate; the live page names its cards
        // `videoWrapper`, and `videoBox` only survives inside an inline script config.
        Pornhub().isListingHtml(load("listing.html")) shouldBe true
    }

    @Test
    fun `details expose title cover and date`() {
        val details = PornhubParser.parseDetails(document("detail.html"), ::absolute)
        details.title shouldBe
            "Quick fuck in the kitchen before our parents get home! Creampie"
        details.coverUrl.shouldNotBeNull()
        // The date is no longer in flashvars; it comes from the JSON-LD block.
        (details.dateUpload > 0L) shouldBe true
    }

    @Test
    fun `playback url is the top hls master`() {
        val details = PornhubParser.parseDetails(document("detail.html"), ::absolute)
        val url = details.m3u8Url
        url.shouldNotBeNull()
        url shouldContain ".m3u8"
        url shouldContain "1080P"
    }

    @Test
    fun `playback prefers hls over mp4`() {
        val flashvars = PornhubParser.extractFlashvars(document("detail.html"))
        flashvars.shouldNotBeNull()
        val url = PornhubParser.bestHlsUrl(flashvars!!)
        url.shouldNotBeNull()
        url shouldContain "master.m3u8"
    }

    @Test
    fun `details fall back to dom when flashvars missing`() {
        val html = load("detail.html").replace(Regex("""<script[\s\S]*?</script>"""), "")
        val doc = Jsoup.parse(html, "https://www.pornhub.com/view_video.php")
        val details = PornhubParser.parseDetails(doc, ::absolute)
        details.title shouldContain "Quick Fuck in the Kitchen"
        details.coverUrl.shouldNotBeNull()
        details.m3u8Url shouldBe null
    }
}
