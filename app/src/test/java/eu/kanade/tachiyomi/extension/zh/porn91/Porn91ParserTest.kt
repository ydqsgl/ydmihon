package eu.kanade.tachiyomi.extension.zh.porn91

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

/**
 * Parsing checks against pages saved from the live site.
 *
 * Two fixture details are load-bearing and would be lost by hand-writing markup: the card titles are
 * double-encoded (`&amp;#039;`), and the playable URL only exists inside a
 * `document.write(strencode2("..."))` call, percent-encoded.
 */
class Porn91ParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("porn91/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String, baseUrl: String = "https://www.91porn.com/index.php"): Document =
        Jsoup.parse(load(name), baseUrl)

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://www.91porn.com$url"
        else -> "https://www.91porn.com/$url"
    }

    @Test
    fun `listing reads video cards`() {
        val items = Porn91Parser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBe 4
        items.forEach { it.url shouldContain "view_video.php" }
        items.forEach { it.title.isNotBlank() shouldBe true }
        items.forEach { it.thumbnailUrl.shouldNotBeNull() }
    }

    @Test
    fun `listing decodes the double-encoded title`() {
        val items = Porn91Parser.parseListing(document("listing.html"), ::absolute)

        val first = items.first()
        // The card keeps the site's own "[original]" tag in front of the uploader's title.
        first.title shouldContain "[original]"
        first.title shouldContain "(Re-upload) A married"
        // The page ships "&amp;#039;", jsoup decodes it to "&#039;", and one more pass is needed.
        first.title shouldContain "There's a bonus scene"
        first.title shouldNotContain "&#039;"
    }

    @Test
    fun `homepage follows the more-feature-videos link`() {
        val next = Porn91Parser.findNextPageUrl(document("listing.html"), ::absolute)

        next.shouldNotBeNull()
        next shouldContain "v.php?category=rf"
        next shouldContain "page=2"
    }

    @Test
    fun `category page follows the pager arrow`() {
        val next = Porn91Parser.findNextPageUrl(
            document("listing_page2.html", "https://www.91porn.com/v.php?category=rf&viewtype=basic&page=2"),
            ::absolute,
        )

        next.shouldNotBeNull()
        next shouldContain "page=3"
    }

    @Test
    fun `details read the title, date and poster`() {
        val details = Porn91Parser.parseDetails(
            document("detail.html", "https://www.91porn.com/view_video.php?viewkey=e7f9b65d89f5238b6211"),
        )

        details.title shouldStartWith "(Re-upload) A married"
        details.title shouldContain "There's a bonus scene"
        details.dateUpload shouldBeGreaterThan 0L
        details.thumbnailUrl.shouldNotBeNull()
        details.thumbnailUrl shouldContain "/thumb/1258066.jpg"
        details.author.shouldNotBeNull()
    }

    @Test
    fun `playback decodes the strencode2 source tag`() {
        val url = Porn91Parser.parseVideoUrl(load("detail.html"))

        url.shouldNotBeNull()
        url shouldStartWith "https://"
        url shouldContain ".mp4"
        url shouldContain "secure="
    }
}
