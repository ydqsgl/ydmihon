package eu.kanade.tachiyomi.extension.zh

import eu.kanade.tachiyomi.extension.zh.cavporn.CavpornParser
import eu.kanade.tachiyomi.extension.zh.hsck.HsckParser
import eu.kanade.tachiyomi.extension.zh.huangvideo.HuangParser
import eu.kanade.tachiyomi.extension.zh.rou.RouParser
import eu.kanade.tachiyomi.extension.zh.taiav.TaiavParser
import eu.kanade.tachiyomi.extension.zh.tangxin.TangxinParser
import eu.kanade.tachiyomi.extension.zh.xxcosplay.XxcosplayParser
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live smoke test: runs every parser against freshly fetched pages.
 *
 * Disabled by default (it needs the real network, and two of these hosts are unreachable from some
 * regions). Run with `LIVE_SOURCES=true ./gradlew test` to check for selector drift before a release.
 */
@EnabledIfEnvironmentVariable(named = "LIVE_SOURCES", matches = "true")
class LiveSourcesSmokeTest {

    private fun fetch(url: String): String {
        val client = okhttp3.OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", 7897)))
            .build()
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport.USER_AGENT)
            .build()
        return client.newCall(request).execute().use { it.body.string() }
    }

    private fun doc(url: String) = Jsoup.parse(fetch(url), url)

    private val identity: (String) -> String = { it }

    @Test
    fun `cavporn listing still parses`() {
        val items = CavpornParser.parseListing(doc("https://cavporn.com/newvideo/"), identity)
        println("cavporn listing=${items.size} first=${items.firstOrNull()?.title}")
        check(items.size > 10) { "cavporn listing shrank to ${items.size}" }
    }

    @Test
    fun `rou listing still parses`() {
        val items = RouParser.parseListing(doc("https://rou.video/v?order=createdAt&page=3"), identity)
        println("rou listing=${items.size} first=${items.firstOrNull()?.title}")
        check(items.size > 10) { "rou listing shrank to ${items.size}" }
    }

    @Test
    fun `taiav listing still parses`() {
        val items = TaiavParser.parseListing(doc("https://taiav.com/cn/hots"), identity)
        println("taiav listing=${items.size} first=${items.firstOrNull()?.title}")
        check(items.size > 10) { "taiav listing shrank to ${items.size}" }
    }

    @Test
    fun `tangxin listing still parses`() {
        val items = TangxinParser.parseListing(doc("https://tangxinvlog.app/"), identity)
        println("tangxin listing=${items.size} first=${items.firstOrNull()?.title}")
        check(items.size > 10) { "tangxin listing shrank to ${items.size}" }
    }

    @Test
    fun `xxcosplay listing still parses`() {
        val items = XxcosplayParser.parseListing(doc("https://xxcosplay.com/?page=3"), identity)
        println("xxcosplay listing=${items.size} first=${items.firstOrNull()?.title}")
        check(items.size > 10) { "xxcosplay listing shrank to ${items.size}" }
    }

    @Test
    fun `huang listing still parses`() {
        val items = HuangParser.parseListing(doc("https://huang.video/"), identity)
        println("huang listing=${items.size} first=${items.firstOrNull()?.title}")
        check(items.isNotEmpty()) { "huang listing is empty" }
    }

    @Test
    fun `hsck listing still parses`() {
        val items = HsckParser.parseListing(doc("https://333.aiock.cc/vodtype/1.html"), identity)
        println("hsck listing=${items.size} first=${items.firstOrNull()?.title}")
        check(items.size > 10) { "hsck listing shrank to ${items.size}" }
    }
}
