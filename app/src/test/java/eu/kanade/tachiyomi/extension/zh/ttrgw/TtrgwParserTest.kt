package eu.kanade.tachiyomi.extension.zh.ttrgw

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

/** Parsing checks against pages saved from the live site. */
class TtrgwParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("ttrgw/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://ttrgw.com/")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://ttrgw.com$url"
        else -> "https://ttrgw.com/$url"
    }

    @Test
    fun `listing reads video cards`() {
        val items = TtrgwParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 5
        items.forEach { it.url shouldContain "https://ttrgw.com/archives/" }
        items.forEach { it.title.isNotBlank() shouldBe true }
        items.first().thumbnailUrl.shouldNotBeNull()
    }

    @Test
    fun `listing items are unique`() {
        val items = TtrgwParser.parseListing(document("listing.html"), ::absolute)
        items.map { it.url }.toSet().size shouldBe items.size
    }

    @Test
    fun `details expose title cover and genre`() {
        val details = TtrgwParser.parseDetails(document("detail.html"))
        details.title.isNotBlank() shouldBe true
        details.description.isNotBlank() shouldBe true
        details.genre.isNullOrBlank() shouldBe false
        details.coverUrl.shouldNotBeNull()
        details.coverUrl shouldContain "webimg.rlkfus.cn"
    }

    @Test
    fun `playback url resolves from the dplayer data-url`() {
        val url = TtrgwParser.resolveVideoUrl(document("detail.html"), "https://ttrgw.com/h5/m3u8/")
        url.shouldNotBeNull()
        url shouldContain "https://ttrgw.com/h5/m3u8/"
        url shouldContain ".m3u8"
    }

    @Test
    fun `next page follows the rel next link`() {
        val next = TtrgwParser.findNextPageUrl(document("listing.html"), ::absolute)
        next.shouldNotBeNull()
        next shouldContain "ttrgw.com"
    }
}
