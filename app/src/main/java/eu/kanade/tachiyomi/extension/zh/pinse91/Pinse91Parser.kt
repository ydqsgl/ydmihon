package eu.kanade.tachiyomi.extension.zh.pinse91

import eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.nodes.Document

/**
 * Parsing for 91PinSe, kept free of networking so it can be tested against saved pages.
 *
 * The listing is a plain `article.video-card` grid. The details page is more useful than it looks:
 * it embeds a `#watch-history-item` JSON blob holding the id, title, cover and author, plus a
 * JSON-LD `VideoObject` with the upload date — both far steadier than the visible markup.
 */
internal object Pinse91Parser {

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

        data object Unavailable : Playback
    }

    fun isListing(html: String): Boolean =
        html.contains("video-card") && html.contains("/v/")

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("article.video-card").mapNotNull { card ->
            val titleAnchor = card.selectFirst("a.video-card-title[href]")
                ?: card.selectFirst("a[href^=\"/v/\"]")
                ?: return@mapNotNull null

            val href = titleAnchor.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null

            val title = (titleAnchor.attr("title").trim().ifEmpty { titleAnchor.text().trim() })
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            val thumb = card.selectFirst("img.video-thumb-img")?.attr("src")
                ?.trim()
                ?.takeIf { it.startsWith("http") }

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = thumb,
            )
        }.distinctBy { it.url }
    }

    /**
     * The pager's forward link is a `?page=N` query string, so it has to be resolved against the
     * page that was fetched (a category page and its second page share the same path).
     */
    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val anchor = document.select("a[href]").firstOrNull {
            val text = it.text().trim()
            text == "下一页" || text == "下一頁" || text == "Next"
        } ?: return null

        val resolved = anchor.absUrl("href").trim()
        val href = resolved.ifEmpty { anchor.attr("href").trim() }
        return href.takeIf { it.isNotEmpty() }?.let(absoluteUrl)
    }

    fun parseDetails(document: Document): Details {
        val history = historyItem(document)

        val title = document.selectFirst("h1.watch-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: (history?.get("title") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" - ").trim()

        val thumbnail = document.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: (history?.get("image") as? JsonPrimitive)?.contentOrNull

        val author = (history?.get("author") as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val jsonLd = jsonLdText(document)

        return Details(
            title = title,
            description = author?.let { "作者: $it" }.orEmpty(),
            genre = author,
            thumbnailUrl = thumbnail,
            dateUpload = VideoSourceSupport.parseDate(UPLOAD_DATE_REGEX.find(jsonLd)?.groupValues?.get(1)),
        )
    }

    /** The clip id used by the playback endpoint. */
    fun parseVideoId(document: Document, chapterUrl: String): String? {
        (historyItem(document)?.get("id") as? JsonPrimitive)?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return VIDEO_PATH_REGEX.find(chapterUrl)?.groupValues?.get(1)
    }

    /** Reads `{"url":"https://.../pl.m3u8","fallback_url":""}`. */
    fun parsePlayback(body: String): Playback {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val url = (root["url"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            if (url.isEmpty()) Playback.Unavailable else Playback.Success(url)
        } catch (_: Exception) {
            Playback.Unavailable
        }
    }

    private fun historyItem(document: Document): JsonObject? {
        val raw = document.selectFirst("script#watch-history-item")?.data()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return try {
            json.parseToJsonElement(raw).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonLdText(document: Document): String =
        document.select("script[type=application/ld+json]").joinToString("\n") { it.data() }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val UPLOAD_DATE_REGEX = Regex(""""uploadDate"\s*:\s*"([^"]+)"""")
    private val VIDEO_PATH_REGEX = Regex("""/v/(\d+)""")
}
