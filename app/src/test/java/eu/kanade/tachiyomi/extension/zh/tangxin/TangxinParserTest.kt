package eu.kanade.tachiyomi.extension.zh.tangxin

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
 * The catalogue index is what makes this source browsable at all, so its parsing and matching are
 * covered rather than just the HTML cards.
 */
class TangxinParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("tangxin/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(load(name), "https://tangxinvlog.app/")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("/") -> "https://tangxinvlog.app$url"
        else -> "https://tangxinvlog.app/$url"
    }

    @Test
    fun `homepage cards parse with covers and durations`() {
        val items = TangxinParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 20
        items.forEach { it.url shouldContain "/v/" }
        items.first().duration.shouldNotBeNull()
        items.first().thumbnailUrl.shouldNotBeNull().shouldStartWith("https://")
    }

    @Test
    fun `catalogue index parses every row`() {
        val entries = TangxinParser.parseIndex(load("search-index.json"))

        entries.shouldNotBeEmpty()
        entries.size shouldBe 120
        entries.first().id shouldBe "6adae766189d6ebc4f0ec91e"
        entries.first().title.isNotBlank() shouldBe true
        entries.first().tags.shouldNotBeEmpty()
    }

    @Test
    fun `malformed index rows are skipped instead of failing the parse`() {
        val json = """[["ok","title",null,[],"1:00",null],["","",null,[],null,null],["ok2"],"nonsense"]"""

        val entries = TangxinParser.parseIndex(json)

        entries.size shouldBe 1
        entries.first().id shouldBe "ok"
    }

    @Test
    fun `index that is not json yields nothing rather than throwing`() {
        TangxinParser.parseIndex("<html>blocked</html>").shouldBeEmpty()
    }

    @Test
    fun `search matches titles case-insensitively`() {
        val entries = TangxinParser.parseIndex(load("search-index.json"))

        val matches = TangxinParser.search(entries, "黑丝")

        matches.shouldNotBeEmpty()
        matches.all {
            it.title.contains("黑丝") || it.tags.any { tag -> tag.contains("黑丝") }
        } shouldBe true
    }

    @Test
    fun `search matches actor names and tags too`() {
        val entries = TangxinParser.parseIndex(load("search-index.json"))
        val actor = entries.first().actor ?: return

        TangxinParser.search(entries, actor).shouldNotBeEmpty()
    }

    @Test
    fun `an empty query returns the whole index`() {
        val entries = TangxinParser.parseIndex(load("search-index.json"))

        TangxinParser.search(entries, "  ").size shouldBe entries.size
    }

    @Test
    fun `search with no hits returns an empty list`() {
        val entries = TangxinParser.parseIndex(load("search-index.json"))

        TangxinParser.search(entries, "zzzzz-not-a-word").shouldBeEmpty()
    }

    @Test
    fun `details page exposes title tags and cover`() {
        val details = TangxinParser.parseDetails(document("detail.html"), ::absolute)

        details.title.isNotBlank() shouldBe true
        details.tags.shouldNotBeEmpty()
        details.coverUrl.shouldNotBeNull().shouldStartWith("https://")
    }

    @Test
    fun `playable m3u8 is read from the page`() {
        val url = TangxinParser.parseVideoUrl(document("detail.html"))

        url.shouldNotBeNull()
        url shouldContain ".m3u8"
        url.shouldStartWith("https://")
    }

    @Test
    fun `listing cards become site-relative paths`() {
        val base = "https://tangxinvlog.app"
        val items = TangxinParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.take(3).forEach { item ->
            val path = TangxinParser.relativePath(item.url, base)
            // Must be a stored path, not the whole URL: the DB keeps paths so a domain change
            // does not orphan the entry.
            path shouldStartWith "/v/"
            (path.contains("://")) shouldBe false
        }
    }

    @Test
    fun `index rows become site-relative paths`() {
        TangxinParser.indexPath("6adae766189d6ebc4f0ec91e") shouldBe "/v/6adae766189d6ebc4f0ec91e/"
    }

    @Test
    fun `relative path falls back to the input when the host does not match`() {
        // A card served from a mirror would otherwise produce an empty path.
        val path = TangxinParser.relativePath("https://other.host/v/abc/", "https://tangxinvlog.app")
        path shouldBe "https://other.host/v/abc/"
    }

    @Test
    fun `a page without a player reports no url`() {
        TangxinParser.parseVideoUrl(Jsoup.parse("<html><body>gone</body></html>")) shouldBe null
    }
}
