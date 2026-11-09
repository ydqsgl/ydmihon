package eu.kanade.tachiyomi.extension.zh.porna91

import eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parsing for 91porna, kept free of networking so it can be tested against saved pages.
 *
 * The site is a Tailwind single-page build whose listing and details pages are plain HTML but whose
 * player is fetched over `POST /api/videos/<id>/playback`. Metadata is published as JSON-LD, which
 * is more reliable than the visible markup (there are three `dx-title` blocks on a details page, only
 * one of which is the clip).
 */
internal object Porna91Parser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    data class Details(
        val title: String,
        val description: String,
        val genre: String?,
        val thumbnailUrl: String?,
        val dateUpload: Long,
    )

    /** Outcome of asking the playback endpoint for a URL. */
    sealed interface Playback {
        data class Success(val url: String) : Playback

        /** The endpoint answered but has nothing to hand out for this clip. */
        data class Unavailable(val message: String) : Playback
    }

    fun isListing(html: String): Boolean =
        html.contains("video-item") && html.contains("/comic/index/detail")

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("div.video-item").mapNotNull { item ->
            val anchor = item.selectFirst("a[href*=\"/comic/index/detail\"]") ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null

            val title = (item.selectFirst("div.line-clamp-2")?.text() ?: anchor.attr("title"))
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = cover(item)?.let(absoluteUrl),
            )
        }.distinctBy { it.url }
    }

    /**
     * Covers are lazy-loaded: `src` is a shared placeholder and the real URL sits in `data-src`
     * behind a signed `auth_key`, so the attribute has to be read rather than resolved.
     */
    private fun cover(item: Element): String? {
        val img = item.selectFirst("img[data-src]") ?: item.selectFirst("img[src]")
        val raw = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }.orEmpty().trim()
        return raw.takeIf { it.startsWith("http") && !it.contains("poster_loading") }
    }

    /** The pager renders the forward link with `rel="next"`; the numeric links do not. */
    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val anchor = document.selectFirst("ul.pager a[rel=next], .pager a[rel=next]")
            ?: document.select(".pager a, ul.pager a").firstOrNull {
                it.text().trim() == "下一页" || it.text().trim() == "下一頁"
            }
            ?: return null

        val resolved = anchor.absUrl("href").trim()
        val href = resolved.ifEmpty { anchor.attr("href").trim() }
        return href.takeIf { it.isNotEmpty() }?.let(absoluteUrl)
    }

    fun parseDetails(document: Document): Details {
        val title = document.selectFirst("h1.dx-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" A片")
                ?.trim()
                .orEmpty()

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            .orEmpty()

        // The details page publishes `og:image` on a host that answers `200` with an empty body, so
        // using it would replace a working cover with a blank one. Returning null lets the caller
        // keep the thumbnail the listing already carried.
        val thumbnail = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.contains(DEAD_COVER_HOST) }

        val jsonLd = jsonLdText(document)

        return Details(
            title = title,
            description = description,
            genre = keywords(jsonLd),
            thumbnailUrl = thumbnail,
            dateUpload = VideoSourceSupport.parseDate(UPLOAD_DATE_REGEX.find(jsonLd)?.groupValues?.get(1)),
        )
    }

    /**
     * The clip id, used to ask the playback endpoint.
     *
     * The player element carries it (`#mse[data-video]`), the page also embeds it in the player
     * bootstrap call, and the detail URL has it as `video_key`. Any of the three will do.
     */
    fun parseVideoId(document: Document, chapterUrl: String): String? {
        document.selectFirst("#mse[data-video]")?.attr("data-video")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        document.selectFirst("[data-video_id]")?.attr("data-video_id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        PLAYBACK_CALL_REGEX.find(document.outerHtml())?.groupValues?.get(1)?.let { return it }
        return VIDEO_KEY_REGEX.find(chapterUrl)?.groupValues?.get(1)
    }

    /**
     * Reads `{"status":1,"data":{"streams":{"main":"...","hevc":"..."}}}`.
     *
     * A refusal comes back as `{"data":[],"msg":"播放地址暂不可用，请稍后重试"}`, which carries the
     * site's own explanation — passing that on beats a generic playback error.
     */
    fun parsePlayback(body: String): Playback {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val status = (root["status"] as? JsonPrimitive)?.intOrNull ?: 0
            if (status != 1) {
                val message = (root["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                return Playback.Unavailable(message.ifBlank { "播放地址暂不可用" })
            }

            val streams = root["data"]?.jsonObject?.get("streams")?.jsonObject
            val main = (streams?.get("main") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val hevc = (streams?.get("hevc") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val url = main.ifBlank { hevc }.trim()

            if (url.isEmpty()) Playback.Unavailable("播放地址为空") else Playback.Success(url)
        } catch (_: Exception) {
            Playback.Unavailable("播放地址解析失败")
        }
    }

    private fun jsonLdText(document: Document): String =
        document.select("script[type=application/ld+json]").joinToString("\n") { it.data() }

    private fun keywords(jsonLd: String): String? {
        val block = KEYWORDS_REGEX.find(jsonLd)?.groupValues?.get(1) ?: return null
        val tags = KEYWORD_REGEX.findAll(block).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }
        return tags.joinToString(", ").takeIf { it.isNotEmpty() }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val UPLOAD_DATE_REGEX = Regex(""""uploadDate"\s*:\s*"([^"]+)"""")
    private const val DEAD_COVER_HOST = "expose.eisees.com"

    private val KEYWORDS_REGEX = Regex(""""keywords"\s*:\s*\[([^\]]*)]""")
    private val KEYWORD_REGEX = Regex(""""([^"]+)"""")
    private val PLAYBACK_CALL_REGEX = Regex("""/api/videos/(\d+)/playback""")
    private val VIDEO_KEY_REGEX = Regex("""video_key=(\d+)""")
}
