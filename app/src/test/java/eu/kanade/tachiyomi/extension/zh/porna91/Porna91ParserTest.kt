package eu.kanade.tachiyomi.extension.zh.porna91

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
 * The playback payloads are the two shapes the endpoint actually returned: a signed HLS answer, and
 * the refusal the site sends when it has nothing to hand out.
 */
class Porna91ParserTest {

    private fun load(name: String): String {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("porna91/$name")) {
            "missing fixture $name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun document(
        name: String,
        baseUrl: String = "https://91porna.com/comic/index/video?category=new_update",
    ): Document = Jsoup.parse(load(name), baseUrl)

    private fun absolute(url: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://91porna.com$url"
        else -> "https://91porna.com/$url"
    }

    @Test
    fun `listing reads video cards`() {
        val items = Porna91Parser.parseListing(document("listing.html"), ::absolute)

        items.shouldNotBeEmpty()
        items.size shouldBe 4
        items.forEach { it.url shouldContain "/comic/index/detail?video_key=" }
        items.forEach { it.title.isNotBlank() shouldBe true }
        items.forEach { it.thumbnailUrl.shouldNotBeNull() }
        // The lazy placeholder must never be mistaken for a cover.
        items.none { it.thumbnailUrl?.contains("poster_loading") == true } shouldBe true
    }

    @Test
    fun `listing follows the pager rel-next link`() {
        val next = Porna91Parser.findNextPageUrl(document("listing.html"), ::absolute)

        next.shouldNotBeNull()
        next shouldContain "category=new_update"
        next shouldContain "page=2"
    }

    @Test
    fun `details come from json-ld and the h1`() {
        val details = Porna91Parser.parseDetails(
            document("detail.html", "https://91porna.com/comic/index/detail?video_key=448151"),
        )

        details.title shouldContain "极品留学生"
        details.description.isNotBlank() shouldBe true
        details.dateUpload shouldBeGreaterThan 0L
        details.thumbnailUrl.shouldNotBeNull()
        details.genre.shouldNotBeNull()
        details.genre shouldContain "探花"
    }

    @Test
    fun `video id is read from the player element`() {
        val id = Porna91Parser.parseVideoId(
            document("detail.html", "https://91porna.com/comic/index/detail?video_key=448151"),
            "https://91porna.com/comic/index/detail?video_key=448151",
        )

        id shouldBe "448151"
    }

    @Test
    fun `playback reads the main stream`() {
        val body = """
            {"data":{"mode":1,"timestamp":1794157500,"manifest_method":"GET","id":"448151",
            "streams":{"hevc":"","main":"https://yd-jhph-long.hzjvlo.cn/videos5/abc/abc.m3u8?auth_key=1794157500-065.0.web.80c5cf77\u0026via_m=91pron_web"},
            "cdn":{"app":"065","authKey":"x-cdn-auth","authValue":"4aae16c3b20ec822f87518504478bb35","m3u8Enabled":true,"otherEnabled":false}},
            "msg":"","req_time":1794150180,"status":1}
        """.trimIndent()

        val playback = Porna91Parser.parsePlayback(body)

        playback.shouldNotBeNull()
        (playback as Porna91Parser.Playback.Success).url shouldStartWith "https://yd-jhph-long.hzjvlo.cn/"
        playback.url shouldContain ".m3u8"
        // \u0026 is JSON's escaping of &, and the signed URL needs it back as a literal ampersand.
        playback.url shouldContain "&via_m=91pron_web"
    }

    @Test
    fun `playback reports the site's own refusal`() {
        val playback = Porna91Parser.parsePlayback(
            """{"data":[],"msg":"播放地址暂不可用，请稍后重试","status":0}""",
        )

        playback shouldBe Porna91Parser.Playback.Unavailable("播放地址暂不可用，请稍后重试")
    }
}
