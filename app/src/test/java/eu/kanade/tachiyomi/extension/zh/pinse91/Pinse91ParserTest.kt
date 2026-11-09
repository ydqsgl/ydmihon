package eu.kanade.tachiyomi.extension.zh.pinse91

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.longs.shouldBeGreaterThan
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
 * The pager on this site is a bare `?page=N` query string, so the test pins the resolved result:
 * a category page and its second page share a path and only the query differs.
 */
class Pinse91ParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("pinse91/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String, baseUrl: String = "https://91pinse.com/v/"): Document =
        Jsoup.parse(load(name), baseUrl)

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://91pinse.com$url"
        else -> "https://91pinse.com/$url"
    }

    @Test
    fun `listing reads video cards`() {
        val items = Pinse91Parser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBe 4
        items.forEach { it.url shouldStartWith "https://91pinse.com/v/" }
        items.forEach { it.title.isNotBlank() shouldBe true }
        items.forEach { it.thumbnailUrl.shouldNotBeNull() }
    }

    @Test
    fun `listing resolves the bare query pager against the current page`() {
        val next = Pinse91Parser.findNextPageUrl(document("listing.html"), ::absolute)

        next.shouldNotBeNull()
        next shouldBe "https://91pinse.com/v/?page=2"
    }

    @Test
    fun `details come from the history blob and json-ld`() {
        val details = Pinse91Parser.parseDetails(
            document("detail.html", "https://91pinse.com/v/473168"),
        )

        details.title shouldContain "放大音量"
        details.dateUpload shouldBeGreaterThan 0L
        details.thumbnailUrl.shouldNotBeNull()
        details.genre shouldBe "jds"
        details.description shouldContain "jds"
    }

    @Test
    fun `video id is read from the history blob`() {
        val id = Pinse91Parser.parseVideoId(
            document("detail.html", "https://91pinse.com/v/473168"),
            "https://91pinse.com/v/473168",
        )

        id shouldBe "473168"
    }

    @Test
    fun `playback reads the m3u8 url`() {
        val playback = Pinse91Parser.parsePlayback(
            """{"url": "https://cdnawsgoogle.456311.xyz/eec154b542efd955e859b6189ca8b50d/pl.m3u8", "fallback_url": ""}""",
        )

        playback.shouldNotBeNull()
        (playback as Pinse91Parser.Playback.Success).url shouldContain "/pl.m3u8"
    }

    @Test
    fun `playback without a url is reported as unavailable`() {
        Pinse91Parser.parsePlayback("""{"url": "", "fallback_url": ""}""") shouldBe
            Pinse91Parser.Playback.Unavailable
    }
}
