package eu.kanade.tachiyomi.extension.zh.koreanporn

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
class KoreanPornMovieParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("koreanporn/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://koreanpornmovie.com/")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://koreanpornmovie.com$url"
        else -> "https://koreanpornmovie.com/$url"
    }

    @Test
    fun `listing reads movie cards`() {
        val items = KoreanPornMovieParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 5
        items.forEach { it.url shouldContain "https://koreanpornmovie.com/" }
        items.first().title.isNotBlank() shouldBe true
    }

    @Test
    fun `listing covers are real urls not placeholders`() {
        val covers = KoreanPornMovieParser.parseListing(document("listing.html"), ::absolute)
            .mapNotNull { it.thumbnailUrl }

        covers.shouldNotBeEmpty()
        covers.forEach { cover ->
            cover.shouldStartWith("https://")
            (cover.startsWith("data:")) shouldBe false
        }
    }

    @Test
    fun `details page carries title and cover`() {
        val details = KoreanPornMovieParser.parseDetails(document("detail.html"))

        details.title.isNotBlank() shouldBe true
        details.title shouldContain "365 Days of Solo Pleasure"
        details.coverUrl.shouldNotBeNull().shouldStartWith("https://")
    }

    @Test
    fun `playable url is the koreanporn stream mp4 not an image`() {
        val url = KoreanPornMovieParser.parseVideoUrl(document("detail.html"))

        url.shouldNotBeNull()
        url shouldContain ".mp4"
        url shouldContain "koreanporn.stream"
        url.shouldStartWith("https://")
    }

    @Test
    fun `listing detection rejects an unrelated page`() {
        KoreanPornMovieParser.parseListing(
            Jsoup.parse("<html><body>nothing</body></html>"),
            ::absolute,
        ).shouldBeEmpty()
    }

    @Test
    fun `next page follows the wordpress pager`() {
        val next = KoreanPornMovieParser.findNextPageUrl(document("listing.html"), ::absolute)
        next.shouldNotBeNull()
        next shouldContain "/page/2/"
    }
}
