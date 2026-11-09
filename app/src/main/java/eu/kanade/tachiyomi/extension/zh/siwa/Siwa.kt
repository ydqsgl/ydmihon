package eu.kanade.tachiyomi.extension.zh.siwa

import eu.kanade.tachiyomi.extension.zh.common.CategoryFilter
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.extension.zh.common.VideoUnavailableException
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 丝袜天堂 (siwa.cam) — a Laravel site for stocking/leg content.
 *
 * Listing and details are plain HTML, but the clip is not: the details page only boots
 * `loadPlayer(..., "https://<embed host>/e/<code>", "iframe")`, and the stream URL lives behind
 * `GET https://<embed host>/api/videos/<code>` as an AES-256-GCM blob (see [SiwaParser]).
 *
 * Categories are the site's own tags (`/tags/<tag>`), which paginate exactly like the home feed.
 */
class Siwa : VideoSource(
    knownDomains = listOf(
        "https://siwa.cam",
    ),
) {

    override val name = "丝袜天堂"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean = SiwaParser.isListing(html)

    override val probePath: String get() = "/"

    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/?page=$page"

    /** Tag pages carry the same grid as the home feed, so a tag is just another listing URL. */
    override fun categoryUrl(category: String?, page: Int): String {
        val tag = category?.trim().orEmpty()
        if (tag.isEmpty()) return listingUrl(page)
        val path = "$baseUrl/tags/${tag.encodeURL()}"
        return if (page <= 1) path else "$path?page=$page"
    }

    override fun searchUrlFor(keyword: String, page: Int): String {
        val base = "$baseUrl/?kw=${keyword.encodeURL()}"
        return if (page <= 1) base else "$base?page=$page"
    }

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(LABELS, SLUGS))

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        SiwaParser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        SiwaParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = SiwaParser.parseDetails(document)
        return DetailsResult(
            title = details.title,
            genre = details.genre,
            thumbnailUrl = details.thumbnailUrl,
            dateUpload = details.dateUpload,
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? {
        val player = SiwaParser.parsePlayer(document) ?: return null

        // Some clips are served straight from the site as HLS; nothing to unwrap.
        if (player.kind.equals("hls", ignoreCase = true)) return player.url

        val embedUrl = absolute(player.url)
        val embedHtml = fetchText(embedUrl)

        // VidHide-style embeds publish the playlist inline, so try that before the API route.
        SiwaParser.parseEmbedLinks(embedHtml)?.let { return it }

        val code = SiwaParser.parseEmbedCode(embedUrl)
            ?: throw VideoUnavailableException("播放地址解析失败")
        val origin = embedUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }
            ?: throw VideoUnavailableException("播放地址解析失败")

        val apiUrl = "$origin/api/videos/$code"
        val body = fetchText(apiUrl)

        return when (val stream = SiwaParser.decryptPlayback(body)) {
            is SiwaParser.Stream.Success -> stream.url
            is SiwaParser.Stream.Failure -> throw VideoUnavailableException(stream.message)
        }
    }

    private suspend fun fetchText(url: String): String {
        val response = client.newCall(GET(url, requestHeaders())).await()
        return response.use { it.body.string() }
    }

    private fun String.encodeURL(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        /** Tag slugs the site actually publishes; the first entry means "the whole feed". */
        private val SLUGS = arrayOf(
            "",
            "丝袜",
            "连身袜",
            "足交",
            "榨精",
            "白丝",
            "手丝",
            "黑丝",
        )

        private val LABELS = arrayOf(
            "全部",
            "丝袜",
            "连身袜",
            "足交",
            "榨精",
            "白丝",
            "手丝",
            "黑丝",
        )
    }
}
