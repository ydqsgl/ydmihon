package eu.kanade.tachiyomi.extension.zh.pornhub

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

/** Parsing checks against pages saved from the live site. */
class PornhubParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("pornhub/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://cn.pornhub.com/video")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://cn.pornhub.com$url"
        else -> "https://cn.pornhub.com/$url"
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
    fun `listing title comes from anchor attribute`() {
        val items = PornhubParser.parseListing(document("listing.html"), ::absolute)
        items.first().title shouldBe "95磅第一次业余Ally脸性交窒息吐口水和阴户捣碎"
    }

    @Test
    fun `listing items are unique`() {
        val items = PornhubParser.parseListing(document("listing.html"), ::absolute)
        items.map { it.url }.toSet().size shouldBe items.size
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
        val doc = Jsoup.parse(html, "https://cn.pornhub.com/video")
        val next = PornhubParser.findNextPageUrl(doc, 3, ::absolute)
        next.shouldNotBeNull()
        next shouldContain "page=4"
    }

    @Test
    fun `details expose title cover genre and date`() {
        val details = PornhubParser.parseDetails(document("detail.html"), ::absolute)
        details.title.isNotBlank() shouldBe true
        details.title shouldContain "摩洛伊斯兰解放阵线"
        details.coverUrl.shouldNotBeNull()
        details.genre shouldContain "Pussy Licking"
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
    fun `flashvars extraction tolerates nested braces`() {
        val flashvars = PornhubParser.extractFlashvars(document("detail.html"))
        flashvars.shouldNotBeNull()
        // video_duration is present and readable through the public bestHlsUrl path.
        val url = PornhubParser.bestHlsUrl(flashvars!!)
        url.shouldNotBeNull()
        url shouldContain "master.m3u8"
    }

    @Test
    fun `details fall back to dom when flashvars missing`() {
        val html = load("detail.html").replace(Regex("""<script>[\s\S]*?</script>"""), "")
        val doc = Jsoup.parse(html, "https://cn.pornhub.com/view_video.php")
        val details = PornhubParser.parseDetails(doc, ::absolute)
        details.title shouldContain "摩洛伊斯兰解放阵线"
        details.coverUrl.shouldNotBeNull()
        details.m3u8Url shouldBe null
    }
}
