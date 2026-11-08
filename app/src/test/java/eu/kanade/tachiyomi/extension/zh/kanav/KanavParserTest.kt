package eu.kanade.tachiyomi.extension.zh.kanav

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

/** Parsing checks against pages saved from the live site. */
class KanavParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("kanav/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://kanav.online/videos")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://kanav.online$url"
        else -> "https://kanav.online/$url"
    }

    @Test
    fun `listing reads video cards`() {
        val items = KanavParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 10
        items.forEach { it.url shouldContain "https://kanav.online/videos/" }
        items.forEach { it.title.isNotBlank() shouldBe true }
        items.first().thumbnailUrl.shouldNotBeNull()
    }

    @Test
    fun `listing items are unique`() {
        val items = KanavParser.parseListing(document("listing.html"), ::absolute)
        items.map { it.url }.toSet().size shouldBe items.size
    }

    @Test
    fun `details expose title cover and genre`() {
        val details = KanavParser.parseDetails(document("detail.html"))
        details.title.isNotBlank() shouldBe true
        details.description.isNotBlank() shouldBe true
        details.genre.isNullOrBlank() shouldBe false
    }

    @Test
    fun `playback url comes from the data-url button`() {
        val url = KanavParser.resolveVideoUrl(document("detail.html"))
        url.shouldNotBeNull()
        url shouldContain ".m3u8"
    }
}
