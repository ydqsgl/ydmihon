package eu.kanade.tachiyomi.extension.zh.mrds

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
class MrdsParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("mrds/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String): Document =
        Jsoup.parse(MrdsParser.decodeIfWrapped(load(name)), "https://mrds66.com/")

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://mrds66.com$url"
        else -> "https://mrds66.com/$url"
    }

    @Test
    fun `base64 wrapped entrance page is unwrapped into a real document`() {
        val raw = load("entrance_stub.html")

        // The stub alone has no article markup, which is why a naive probe would reject the host.
        raw.contains("<article") shouldBe false
        MrdsParser.decodeIfWrapped(raw).contains("</html>") shouldBe true
    }

    @Test
    fun `unwrapping leaves an already plain page untouched`() {
        val plain = load("listing_home.html")

        MrdsParser.decodeIfWrapped(plain) shouldBe plain
    }

    @Test
    fun `listing skips advertisement cards and keeps articles`() {
        val items = MrdsParser.parseListing(document("listing_home.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBeGreaterThan 15
        items.forEach {
            it.url shouldStartWith "https://mrds66.com/archives/"
            it.title.isNotBlank() shouldBe true
        }
        items.none { it.title.contains("热搜") } shouldBe true
        items.map { it.url }.distinct().size shouldBe items.size
    }

    @Test
    fun `listing covers come from the loadBannerDirect call`() {
        val items = MrdsParser.parseListing(document("listing_home.html"), ::absolute)

        val thumbs = items.mapNotNull { it.thumbnailUrl }
        thumbs.shouldNotBeEmpty()
        thumbs.forEach { it shouldStartWith "https://" }
        // The theme's placeholder must never be used as a cover.
        thumbs.none { MrdsParser.isPlaceholder(it) } shouldBe true
    }

    @Test
    fun `pagination finds the next page`() {
        MrdsParser.findNextPageUrl(document("listing_home.html"), ::absolute) shouldBe
            "https://mrds66.com/page/2/"

        MrdsParser.findNextPageUrl(document("listing_page2.html"), ::absolute) shouldBe
            "https://mrds66.com/page/3/"
    }

    @Test
    fun `article exposes every video from its DPlayer configs`() {
        val article = MrdsParser.parseArticle(
            document = document("article_multi.html"),
            fallbackTitle = "fallback",
            normalizeUrl = ::absolute,
            dateParser = { 0L },
        )

        article.videoUrls.size shouldBe 2
        article.videoUrls.forEach { url ->
            url shouldStartWith "https://"
            url shouldContain ".m3u8"
            // The escaped-slash bug produced a bare "https:" URL.
            url.length shouldBeGreaterThan 30
        }
    }

    @Test
    fun `article images are read from data-xkrkllgl and skip the placeholder`() {
        val article = MrdsParser.parseArticle(
            document = document("article_multi.html"),
            fallbackTitle = "fallback",
            normalizeUrl = ::absolute,
            dateParser = { 0L },
        )

        article.imageUrls.shouldNotBeEmpty()
        article.imageUrls.forEach { url ->
            url shouldStartWith "https://"
            url.contains("zw.png") shouldBe false
            url.contains("/usr/themes/") shouldBe false
        }
        article.imageUrls.distinct().size shouldBe article.imageUrls.size
    }

    @Test
    fun `description prefers the editorial summary over ad copy`() {
        val article = MrdsParser.parseArticle(
            document = document("article_multi.html"),
            fallbackTitle = "fallback",
            normalizeUrl = ::absolute,
            dateParser = { 0L },
        )

        article.description shouldContain "共 2 个视频"
        article.description.contains("最新地址") shouldBe false
    }

    @Test
    fun `tags are read from the theme tag list`() {
        val article = MrdsParser.parseArticle(
            document = document("article_multi.html"),
            fallbackTitle = "fallback",
            normalizeUrl = ::absolute,
            dateParser = { 0L },
        )

        article.tags.shouldNotBeEmpty()
        article.tags.distinct().size shouldBe article.tags.size
    }
}
