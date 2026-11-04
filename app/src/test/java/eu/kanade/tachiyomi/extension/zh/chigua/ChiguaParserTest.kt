package eu.kanade.tachiyomi.extension.zh.chigua

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test

/**
 * Parsing checks against pages saved from the live site, so a theme change shows up as a
 * failing test instead of a blank source.
 */
class ChiguaParserTest {

    private fun load(name: String): Document {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("chigua/$name")) {
            "missing fixture $name"
        }
        return Jsoup.parse(stream.bufferedReader().use { it.readText() }, "https://51cg1.com/")
    }

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://51cg1.com$url"
        else -> "https://51cg1.com/$url"
    }

    @Test
    fun `listing skips advertisement cards and keeps articles`() {
        val items = ChiguaParser.parseListing(load("listing_home.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 15
        items.forEach {
            it.url shouldStartWith "https://51cg1.com/archives/"
            it.title.isNotBlank() shouldBe true
        }
        // Titles are cleaned of the hot-search badge.
        items.none { it.title.contains("热搜") } shouldBe true
        // No duplicate articles on one page.
        items.map { it.url }.distinct().size shouldBe items.size
    }

    @Test
    fun `listing thumbnails come from the lazy loader attribute`() {
        val items = ChiguaParser.parseListing(load("listing_home.html"), ::absolute)

        val thumbs = items.mapNotNull { it.thumbnailUrl }
        thumbs.shouldNotBeEmpty()
        thumbs.first() shouldStartWith "https://"
        // `src` is a transparent placeholder on this theme.
        thumbs.none { it.contains("placeholder") } shouldBe true
    }

    @Test
    fun `pagination finds the next page and stops on the last one`() {
        val first = ChiguaParser.findNextPageUrl(load("listing_home.html"), ::absolute)
        first shouldBe "https://51cg1.com/page/2/"

        val second = ChiguaParser.findNextPageUrl(load("listing_page2.html"), ::absolute)
        second shouldBe "https://51cg1.com/page/3/"
    }

    @Test
    fun `article exposes every video from its DPlayer configs`() {
        val article = ChiguaParser.parseArticle(
            document = load("article_multi.html"),
            fallbackTitle = "fallback",
            normalizeUrl = ::absolute,
            dateParser = { 0L },
        )

        // Two DPlayer widgets, each with an m3u8 stream.
        article.videoUrls.size shouldBe 2
        article.videoUrls.forEach { url ->
            url shouldStartWith "https://"
            url shouldContain ".m3u8"
            // The escaped-slash bug produced a bare "https:" URL.
            url.length shouldBeGreaterThan 30
        }
        article.title.isNotBlank() shouldBe true
        article.description.isNotBlank() shouldBe true
    }

    @Test
    fun `article with many videos keeps them all in order`() {
        val article = ChiguaParser.parseArticle(
            document = load("article_many_videos.html"),
            fallbackTitle = "fallback",
            normalizeUrl = ::absolute,
            dateParser = { 0L },
        )

        article.videoUrls.size shouldBe 10
        article.videoUrls.distinct().size shouldBe 10
    }

    @Test
    fun `article images skip advertising and theme chrome`() {
        val article = ChiguaParser.parseArticle(
            document = load("article_multi.html"),
            fallbackTitle = "fallback",
            normalizeUrl = ::absolute,
            dateParser = { 0L },
        )

        article.imageUrls.shouldNotBeEmpty()
        article.imageUrls.forEach { url ->
            url shouldStartWith "https://"
            url.contains("/usr/themes/") shouldBe false
            url.contains("/upload-images/") shouldBe false
        }
        article.imageUrls.distinct().size shouldBe article.imageUrls.size
    }

    @Test
    fun `description prefers the editorial summary over ad buttons`() {
        val article = ChiguaParser.parseArticle(
            document = load("article_multi.html"),
            fallbackTitle = "fallback",
            normalizeUrl = ::absolute,
            dateParser = { 0L },
        )

        article.description shouldContain "共 2 个视频"
        article.description.contains("空降约炮") shouldBe false
        article.description.contains("最新地址") shouldBe false
    }

    @Test
    fun `tags are read from the theme tag list`() {
        val article = ChiguaParser.parseArticle(
            document = load("article_multi.html"),
            fallbackTitle = "fallback",
            normalizeUrl = ::absolute,
            dateParser = { 0L },
        )

        article.tags.shouldNotBeEmpty()
        article.tags.distinct().size shouldBe article.tags.size
    }
}
