package eu.kanade.tachiyomi.extension.zh.cavporn

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

/** Parsing checks against pages saved from the live site. */
class CavpornParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("cavporn/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://cavporn.com/newvideo/2/")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://cavporn.com$url"
        else -> "https://cavporn.com/$url"
    }

    @Test
    fun `listing reads clip cards`() {
        val items = CavpornParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 20
        items.forEach { it.url shouldContain "https://cavporn.com/video/" }
        items.first().title.isNotBlank() shouldBe true
    }

    @Test
    fun `listing covers use the lazy attributes not the placeholder gif`() {
        val items = CavpornParser.parseListing(document("listing.html"), ::absolute)
        val covers = items.mapNotNull { it.thumbnailUrl }

        covers.shouldNotBeEmpty()
        covers.forEach { cover ->
            cover.shouldStartWith("https://")
            // The placeholder is a data: URI; a real cover must never be one.
            (cover.startsWith("data:")) shouldBe false
        }
        covers.any { it.contains("videos_screenshots") } shouldBe true
    }

    @Test
    fun `details page carries title categories and duration`() {
        val details = CavpornParser.parseDetails(document("detail.html"), ::absolute)

        details.title.isNotBlank() shouldBe true
        (details.categories + details.tags).shouldNotBeEmpty()
        details.duration shouldBe "23:39"
        details.coverUrl.shouldNotBeNull().shouldStartWith("https://")
    }

    @Test
    fun `playable url is read from the player config`() {
        val url = CavpornParser.parseVideoUrl(document("detail.html"))

        url.shouldNotBeNull()
        url shouldContain ".m3u8"
        url.shouldStartWith("https://")
        // KT signs the URL with md5 + expiry; without them the CDN rejects the request.
        url shouldContain "md5="
    }

    @Test
    fun `a members-only clip is reported as login required`() {
        // Most clips on this site replace the player with a "登录 / 注册" panel.
        CavpornParser.parsePlayback(document("locked.html")) shouldBe CavpornParser.Playback.LoginRequired
        CavpornParser.parseVideoUrl(document("locked.html")) shouldBe null
    }

    @Test
    fun `a page with neither player nor login panel is missing`() {
        CavpornParser.parsePlayback(Jsoup.parse("<html><body>gone</body></html>")) shouldBe
            CavpornParser.Playback.Missing
    }

    @Test
    fun `listing detection rejects an unrelated page`() {
        CavpornParser.parseListing(Jsoup.parse("<html><body>nothing</body></html>"), ::absolute)
            .shouldBeEmpty()
    }
}
