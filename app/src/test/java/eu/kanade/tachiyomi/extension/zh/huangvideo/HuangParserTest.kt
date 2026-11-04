package eu.kanade.tachiyomi.extension.zh.huangvideo

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
 * The important distinction here is series vs. standalone clip: mixing them would show the same
 * series once per episode in the library.
 */
class HuangParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("huang/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://huang.video/")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("/") -> "https://huang.video$url"
        else -> "https://huang.video/$url"
    }

    @Test
    fun `homepage lists series cards`() {
        val items = HuangParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.forEach { it.url shouldStartWith "https://huang.video/series/" }
        items.first().title.isNotBlank() shouldBe true
    }

    @Test
    fun `series cards carry covers and creator subtitles`() {
        val items = HuangParser.parseListing(document("listing.html"), ::absolute)

        items.mapNotNull { it.thumbnailUrl }.shouldNotBeEmpty()
        items.mapNotNull { it.subtitle }.shouldNotBeEmpty()
    }

    @Test
    fun `carousel slides are not mistaken for series cards`() {
        val items = HuangParser.parseListing(document("listing.html"), ::absolute)

        // The hero carousel links to the same series but has no article id="series_N" wrapper.
        items.size shouldBe items.map { it.url }.distinct().size
        items.size shouldBeGreaterThan 5
    }

    @Test
    fun `series page exposes every episode as a chapter`() {
        val details = HuangParser.parseSeries(document("series.html"), ::absolute)

        details.title shouldBe "女友帮我操闺蜜"
        details.episodes.shouldNotBeEmpty()
        details.episodes.size shouldBe 12
        details.episodes.first().url shouldStartWith "https://huang.video/videos/"
        details.episodes.first().name shouldContain "第"
    }

    @Test
    fun `series cover comes from the social metadata`() {
        val details = HuangParser.parseSeries(document("series.html"), ::absolute)

        details.coverUrl.shouldNotBeNull().shouldStartWith("https://h.matecdn.com/")
    }

    @Test
    fun `episode page yields a playable m3u8`() {
        val url = HuangParser.parseVideoUrl(document("video.html"))

        url.shouldNotBeNull()
        url shouldContain "master.m3u8"
        url.shouldStartWith("https://")
    }

    @Test
    fun `episode page details come from the player attributes`() {
        val details = HuangParser.parseClipDetails(document("video.html"))

        details.title.isNotBlank() shouldBe true
        details.coverUrl.shouldNotBeNull().shouldStartWith("https://")
    }

    @Test
    fun `a page without a player reports no url`() {
        HuangParser.parseVideoUrl(Jsoup.parse("<html><body>gone</body></html>")) shouldBe null
    }

    @Test
    fun `creator shelf pages are clip grids not series feeds`() {
        // /c/tangxin and friends render article id="video_N" cards with no series wrapper.
        HuangParser.parseListing(document("category_clips.html"), ::absolute).shouldBeEmpty()

        val clips = HuangParser.parseClipListing(document("category_clips.html"), ::absolute)
        clips.shouldNotBeEmpty()
        clips.forEach { it.url shouldStartWith "https://huang.video/videos/" }
        clips.first().thumbnailUrl.shouldNotBeNull().shouldStartWith("https://h.matecdn.com/")
    }

    @Test
    fun `episode rows are not listed as standalone clips`() {
        // A series page repeats /videos/ links inside its episode list; parsing it as a clip feed
        // would surface the same series twelve times.
        HuangParser.parseClipListing(document("series.html"), ::absolute).shouldBeEmpty()
    }
}
