package eu.kanade.tachiyomi.extension.zh.taiav

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
class TaiavParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("taiav/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://taiav.com/cn")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("/") -> "https://taiav.com$url"
        else -> "https://taiav.com/$url"
    }

    @Test
    fun `listing reads movie cards`() {
        val items = TaiavParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 20
        items.forEach { it.url shouldContain "/movie/" }
        items.first().title.isNotBlank() shouldBe true
    }

    @Test
    fun `listing titles are taken in full from the image alt`() {
        val items = TaiavParser.parseListing(document("listing.html"), ::absolute)

        // Captions truncate with an ellipsis; the alt attribute keeps the whole title.
        items.any { it.title.length > 30 } shouldBe true
        items.none { it.title.endsWith("...") } shouldBe true
    }

    @Test
    fun `search results parse like any listing`() {
        val items = TaiavParser.parseListing(document("search.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.forEach { it.url shouldContain "/movie/" }
    }

    @Test
    fun `details expose title tags and cover`() {
        val details = TaiavParser.parseDetails(document("detail.html"), ::absolute)

        details.title shouldContain "糖心vlog"
        details.tags.shouldNotBeEmpty()
        details.coverUrl.shouldNotBeNull().shouldStartWith("https://")
    }

    @Test
    fun `movie id comes from the canonical link`() {
        val id = TaiavParser.movieId(
            document("detail.html"),
            "https://taiav.com/cn/movie/654c0e8e7b410d26157b1a5f",
        )

        id shouldBe "654c0e8e7b410d26157b1a5f"
    }

    @Test
    fun `movie id falls back to the chapter url`() {
        val id = TaiavParser.movieId(
            Jsoup.parse("<html></html>"),
            "https://taiav.com/cn/movie/654c0e8e7b410d26157b1a5f",
        )

        id shouldBe "654c0e8e7b410d26157b1a5f"
    }

    @Test
    fun `playback response yields an absolute m3u8 url`() {
        val body = """{"m3u8":"/videos/202311/09/654c0e8e7b410d26157b1a5f/b3ffac/index.m3u8?random=x&counts=5"}"""

        val result = TaiavParser.parsePlayback(body, ::absolute)

        result shouldBe TaiavParser.PlaybackResult.Success(
            "https://taiav.com/videos/202311/09/654c0e8e7b410d26157b1a5f/b3ffac/index.m3u8?random=x&counts=5",
        )
    }

    @Test
    fun `a refusal becomes an actionable unavailable result`() {
        val body = """{"message":"对不起，该视频分辨率对应的用户组未设置，获取失败"}"""

        val result = TaiavParser.parsePlayback(body, ::absolute)

        (result is TaiavParser.PlaybackResult.Unavailable) shouldBe true
        (result as TaiavParser.PlaybackResult.Unavailable).reason shouldContain "用户组"
    }

    @Test
    fun `non json playback responses are reported not swallowed`() {
        val result = TaiavParser.parsePlayback("<html>blocked</html>", ::absolute)

        (result is TaiavParser.PlaybackResult.Unavailable) shouldBe true
    }

    @Test
    fun `listing detection rejects an unrelated page`() {
        TaiavParser.parseListing(Jsoup.parse("<html><body>nothing</body></html>"), ::absolute)
            .shouldBeEmpty()
    }
}
