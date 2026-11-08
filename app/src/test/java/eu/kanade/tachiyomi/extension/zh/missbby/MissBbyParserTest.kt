package eu.kanade.tachiyomi.extension.zh.missbby

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

/** Parsing checks against pages saved from the live site. */
class MissBbyParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("missbby/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://missbby.cc/")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://missbby.cc$url"
        else -> "https://missbby.cc/$url"
    }

    @Test
    fun `listing reads photo collections`() {
        val items = MissBbyParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 5
        items.forEach { it.url shouldContain "missbby.cc/" }
        items.forEach { it.title.isNotBlank() shouldBe true }
        items.forEach { it.thumbnailUrl.shouldContain("media.missbby.asia") }
    }

    @Test
    fun `listing deduplicates duplicated render cards`() {
        val items = MissBbyParser.parseListing(document("listing.html"), ::absolute)
        items.map { it.url }.toSet().size shouldBe items.size
    }

    @Test
    fun `next page follows the rendered Next link`() {
        val next = MissBbyParser.findNextPageUrl(document("listing.html"), ::absolute)
        next shouldContain "/?page=2"
    }

    @Test
    fun `details expose title and cover`() {
        val details = MissBbyParser.parseDetails(document("detail.html"))
        details.title.isNotBlank() shouldBe true
        details.coverUrl.shouldContain("http")
    }

    @Test
    fun `gallery images are the asia media host`() {
        val images = MissBbyParser.galleryImages(document("detail.html"))
        images.shouldNotBeEmpty()
        images.forEach { it shouldContain "media.missbby.asia/img/" }
    }
}
