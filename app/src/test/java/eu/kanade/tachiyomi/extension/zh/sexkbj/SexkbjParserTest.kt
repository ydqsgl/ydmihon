package eu.kanade.tachiyomi.extension.zh.sexkbj

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
 * The embed fixture is the real player page: its stream URL only exists inside a packer-obfuscated
 * script, so this also covers [eu.kanade.tachiyomi.extension.zh.common.JsPacker].
 */
class SexkbjParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("sexkbj/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(name: String, baseUrl: String = "https://sexkbj.com/"): Document =
        Jsoup.parse(load(name), baseUrl)

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://sexkbj.com$url"
        else -> "https://sexkbj.com/$url"
    }

    @Test
    fun `listing reads every article`() {
        val items = SexkbjParser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBe 4
        items.forEach { it.url shouldStartWith "https://sexkbj.com/2026/" }
        items.forEach { it.thumbnailUrl.shouldNotBeNull() }
        items.first().title shouldBe "KBJ26110453_znvely00(BJ한예주)_sexkbj"
    }

    @Test
    fun `pager follows the head link`() {
        SexkbjParser.findNextPageUrl(document("listing.html"), 1, ::absolute) shouldBe
            "https://sexkbj.com/page/2/"
    }

    @Test
    fun `details come from the entry header and open graph`() {
        val details = SexkbjParser.parseDetails(
            document("detail.html", "https://sexkbj.com/2026/11/09/kbj26110430_naras2_sexkbj/"),
        )

        details.title shouldBe "KBJ26110430_naras2(BJ이태리)_sexkbj"
        details.thumbnailUrl shouldContain "/2026/11/30naras2_11040005_sexkbj.mp4.jpg"
        details.dateUpload shouldBeGreaterThan 0L
    }

    @Test
    fun `embed iframe is resolved against the page`() {
        SexkbjParser.parseEmbedUrl(document("detail.html"), ::absolute) shouldBe
            "https://sexkbj.top/embed/ny2frgze81q6"
    }

    @Test
    fun `stream url is recovered from the packed player script`() {
        val url = SexkbjParser.parseStream(load("embed.html"))

        url.shouldNotBeNull()
        url shouldContain "ny2frgze81q6"
        url shouldContain "master.m3u8"
    }
}
