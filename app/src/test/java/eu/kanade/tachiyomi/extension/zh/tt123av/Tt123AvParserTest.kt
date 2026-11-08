package eu.kanade.tachiyomi.extension.zh.tt123av

import io.kotest.matchers.shouldBe
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test

/** Parsing checks against a server-rendered details page (escaped Alpine payload). */
class Tt123AvParserTest {

    @Test
    fun `listing reads card grid`() {
        val html = """
            <html><body>
            <div class="card">
              <a class="card__cover" href="/cn/v/snos-461"><img class="card__img" src="https://icdn.123av.me/img2/s360/b3/snos-461/cover.jpg"></a>
              <h3 class="card__title"><a class="card__link" href="/cn/v/snos-461">SNOS-461 — 标题</a></h3>
            </div>
            <a class="pager__nav" href="https://123av.com/cn/new?page=2" rel="next">›</a>
            </body></html>
        """.trimIndent()
        val document = Jsoup.parse(html, "https://123av.com/cn/new")

        val items = Tt123AvParser.parseListing(document) { it }
        items.size shouldBe 1
        items[0].url shouldBe "/cn/v/snos-461"
        items[0].title shouldBe "SNOS-461 — 标题"
        items[0].thumbnailUrl shouldBe "https://icdn.123av.me/img2/s360/b3/snos-461/cover.jpg"
    }

    @Test
    fun `next page follows rel next`() {
        val html = """<html><body><a class="pager__nav" href="https://123av.com/cn/new?page=2" rel="next">›</a></body></html>"""
        val next = Tt123AvParser.findNextPageUrl(Jsoup.parse(html, "https://123av.com/cn/new")) { it }
        next shouldBe "https://123av.com/cn/new?page=2"
    }

    @Test
    fun `details extract embed hash from escaped payload`() {
        // The server emits the Alpine attribute with \u0022 (JS-escaped quotes) and escaped slashes;
        // jsoup keeps both as-is, so the attribute survives parsing.
        val html = """<html><head>
            <meta name="description" content="desc">
            <meta property="og:image" content="https://icdn.123av.me/img2/s500/b3/snos-461/cover.jpg">
            </head><body>
            <h1 class="watch__title">SNOS-461 — 标题</h1>
            <div x-data="player(JSON.parse('[{\u0022number\u0022:1,\u0022name\u0022:\u00221\u0022,\u0022url\u0022:\u0022https:\\/\\/sadie-shop.site\\/e\\/0K7285?poster=x\u0022}]'))"></div>
            </body></html>"""
        val document = Jsoup.parse(html, "https://123av.com/cn/v/snos-461")

        val details = Tt123AvParser.parseDetails(document)
        details.title shouldBe "SNOS-461 — 标题"
        details.description shouldBe "desc"
        details.coverUrl shouldBe "https://icdn.123av.me/img2/s500/b3/snos-461/cover.jpg"
        details.embedHashId shouldBe "0K7285"
    }

    @Test
    fun `details extract embed hash from double escaped payload`() {
        val html = """<html><body>
            <div x-data="player(JSON.parse('[{\u0022url\u0022:\u0022https:\\\\\\/\\\\\\/sadie-shop.site\\\\\\/e\\\\\\/aB12_34?poster=x\u0022}]'))"></div>
            </body></html>"""
        val details = Tt123AvParser.parseDetails(Jsoup.parse(html, "https://123av.com/cn/v/x"))
        details.embedHashId shouldBe "aB12_34"
    }

    @Test
    fun `search page one omits the page parameter`() {
        Tt123AvParser.searchUrl("https://123av.com", "SNOS", 1) shouldBe
            "https://123av.com/cn/search?keyword=SNOS"
    }

    @Test
    fun `search pages past one carry the page parameter`() {
        Tt123AvParser.searchUrl("https://123av.com", "SNOS", 2) shouldBe
            "https://123av.com/cn/search?keyword=SNOS&page=2"
        Tt123AvParser.searchUrl("https://123av.com", "SNOS", 7) shouldBe
            "https://123av.com/cn/search?keyword=SNOS&page=7"
    }

    @Test
    fun `search page two is parsed as a listing`() {
        // The site answers `?page=N` with the same card grid plus a pager, so a page-2 document
        // must yield items and another "next" link instead of ending pagination.
        val html = """
            <html><body>
            <div class="card">
              <a class="card__cover" href="/cn/v/abc-123"><img class="card__img" src="https://icdn.123av.me/a.jpg"></a>
              <h3 class="card__title"><a class="card__link" href="/cn/v/abc-123">ABC-123</a></h3>
            </div>
            <a class="pager__nav" href="https://123av.com/cn/search?keyword=SNOS&amp;page=3" rel="next">›</a>
            </body></html>
        """.trimIndent()
        val document = Jsoup.parse(html, "https://123av.com/cn/search?keyword=SNOS&page=2")

        Tt123AvParser.parseListing(document) { it }.size shouldBe 1
        Tt123AvParser.findNextPageUrl(document) { it } shouldBe
            "https://123av.com/cn/search?keyword=SNOS&page=3"
    }
}
