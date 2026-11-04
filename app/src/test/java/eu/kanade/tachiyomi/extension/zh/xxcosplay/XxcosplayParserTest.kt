package eu.kanade.tachiyomi.extension.zh.xxcosplay

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
 * This is the bundle's only gallery source, so the album parse (image order, no duplicates, media
 * host only) matters more than the listing.
 */
class XxcosplayParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("xxcosplay/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://xxcosplay.com/")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("/") -> "https://xxcosplay.com$url"
        else -> "https://xxcosplay.com/$url"
    }

    @Test
    fun `listing reads album cards`() {
        val items = XxcosplayParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 10
        items.forEach { it.url shouldStartWith "https://xxcosplay.com/albums/" }
        items.none { it.url.contains("/artists/") } shouldBe true
    }

    @Test
    fun `album cards carry covers and artist names`() {
        val items = XxcosplayParser.parseListing(document("listing.html"), ::absolute)

        items.mapNotNull { it.thumbnailUrl }.shouldNotBeEmpty()
        items.mapNotNull { it.artist }.shouldNotBeEmpty()
    }

    @Test
    fun `album page yields every image in order`() {
        val album = XxcosplayParser.parseAlbum(document("album.html"), ::absolute)

        album.imageUrls.shouldNotBeEmpty()
        album.imageUrls.size shouldBe 141
        album.imageUrls.first() shouldContain "0001.webp"
        album.imageUrls.last() shouldContain "0141.webp"
    }

    @Test
    fun `album images are full-size originals without duplicates`() {
        val album = XxcosplayParser.parseAlbum(document("album.html"), ::absolute)

        album.imageUrls.size shouldBe album.imageUrls.distinct().size
        album.imageUrls.forEach { url ->
            url.shouldStartWith("https://media.xxcosplay.com/")
            // The scaled lightbox copies live under a size segment; originals never do.
            url.contains("/thumb") shouldBe false
        }
    }

    @Test
    fun `album keeps its tags and artist`() {
        val album = XxcosplayParser.parseAlbum(document("album.html"), ::absolute)

        album.tags.shouldNotBeEmpty()
        album.artist.shouldNotBeNull()
        album.coverUrl.shouldNotBeNull().shouldStartWith("https://")
    }

    @Test
    fun `listing detection distinguishes albums from artist pages`() {
        XxcosplayParser.parseListing(Jsoup.parse("<html><body>nothing</body></html>"), ::absolute)
            .shouldBeEmpty()
    }

    @Test
    fun `a page with no album images yields none`() {
        val empty = Jsoup.parse("<html><body><div data-controller=lightbox></div></body></html>")

        XxcosplayParser.extractImageUrls(empty, ::absolute).shouldBeEmpty()
    }
}
