package eu.kanade.tachiyomi.extension.zh

import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Walks the full source contract against the live sites: listing -> details -> chapters -> pages.
 *
 * The unit tests check parsing of saved pages; this checks the *plumbing* between them, which is
 * where "opens and immediately errors" bugs live. Enabled with `SOURCE_WALK=true`.
 */
@EnabledIfEnvironmentVariable(named = "SOURCE_WALK", matches = "true")
class SourceWalkTest {

    private val proxy = System.getenv("WALK_PROXY")?.let {
        Proxy(Proxy.Type.HTTP, InetSocketAddress(it.substringBefore(":"), it.substringAfter(":").toInt()))
    }

    private fun walk(source: VideoSource, label: String) = runBlocking {
        println("=== $label (${source.name})")
        try {
            val client = OkHttpClient.Builder()
                .proxy(proxy)
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .build()

            // 1. listing
            val page = source.getPopularManga(1)
            println("  listing: ${page.mangas.size} items, hasNext=${page.hasNextPage}")
            page.mangas.take(2).forEach { println("    - ${it.title.take(40)} | ${it.url}") }

            val first = page.mangas.firstOrNull() ?: run { println("  EMPTY LISTING"); return@runBlocking }

            // 2. details + chapters
            val update = source.getMangaUpdate(first, emptyList(), true, true)
            println("  details: '${update.manga.title.take(40)}' cover=${update.manga.thumbnail_url?.take(50)}")
            println("  chapters: ${update.chapters.size}")
            update.chapters.take(3).forEach { println("    * ${it.name} -> ${it.url}") }

            val chapter = update.chapters.firstOrNull() ?: run {
                println("  NO CHAPTERS")
                return@runBlocking
            }

            // 3. pages
            val pages = source.getPageList(chapter)
            println("  pages: ${pages.size}")
            pages.take(2).forEach { println("    > video=${it.videoUrl?.take(90)}") }

            // 4. the media URL must actually be fetchable
            val videoUrl = pages.firstOrNull()?.videoUrl
            if (videoUrl != null) {
                val req = okhttp3.Request.Builder()
                    .url(videoUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7)")
                    .header("Referer", "https://" + source.getChapterUrl(chapter).substringAfter("://").substringBefore("/") + "/")
                    .build()
                client.newCall(req).execute().use { r ->
                    println("  media fetch: HTTP ${r.code}, ${r.body.contentLength()} bytes")
                }
            }
        } catch (e: Throwable) {
            println("  !! FAILED: ${e.javaClass.name}: ${e.message}")
            e.stackTrace.take(6).forEach { println("       at $it") }
        }
    }

    @Test
    fun `walk tangxin`() = walk(
        eu.kanade.tachiyomi.extension.zh.tangxin.Tangxin(),
        "tangxin",
    )

    @Test
    fun `walk rou`() = walk(
        eu.kanade.tachiyomi.extension.zh.rou.Rou(),
        "rou",
    )
}
