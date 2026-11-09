package eu.kanade.tachiyomi.extension.zh.porna91

import eu.kanade.tachiyomi.extension.zh.common.CategoryFilter
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.extension.zh.common.VideoUnavailableException
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 91porna — a Tailwind build of the same catalogue, with the listing at `/comic/index/video`.
 *
 * The player is not in the markup: the page boots `dxPlayback` against
 * `POST /api/videos/<id>/playback`, which answers with an HLS URL. That endpoint rejects requests
 * that do not carry an `Origin` header, so playback is resolved here rather than by scraping
 * (see [resolveVideoUrl]).
 */
class Porna91 : VideoSource(
    knownDomains = listOf(
        "https://91porna.com",
    ),
) {

    override val name = "91Porna"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean = Porna91Parser.isListing(html)

    override val probePath: String get() = "/comic/index/video?category=new_update"

    override fun listingUrl(page: Int): String {
        val base = "$baseUrl/comic/index/video?category=new_update"
        return if (page <= 1) base else "$base&page=$page"
    }

    override fun categoryUrl(category: String?, page: Int): String {
        val base = "$baseUrl/comic/index/video?category=${category ?: "new_update"}"
        return if (page <= 1) base else "$base&page=$page"
    }

    /**
     * Search is single-page: the results page has no pager, and asking for `&page=2` answers 404,
     * so the page number is deliberately not appended.
     */
    override fun searchUrlFor(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        return "$baseUrl/comic/index/search?keyword=$encoded"
    }

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(LABELS, SLUGS))

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        Porna91Parser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        Porna91Parser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = Porna91Parser.parseDetails(document)
        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = details.genre,
            thumbnailUrl = details.thumbnailUrl,
            dateUpload = details.dateUpload,
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? {
        val id = Porna91Parser.parseVideoId(document, chapterUrl) ?: return null

        val request = okhttp3.Request.Builder()
            .url("$baseUrl/api/videos/$id/playback")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .headers(
                requestHeaders().newBuilder()
                    .set("Content-Type", "application/json")
                    .set("Accept", "application/json, text/plain, */*")
                    .set("Origin", baseUrl)
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Referer", chapterUrl)
                    .build(),
            )
            .build()

        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }

        return when (val playback = Porna91Parser.parsePlayback(text)) {
            is Porna91Parser.Playback.Success -> playback.url
            is Porna91Parser.Playback.Unavailable -> throw VideoUnavailableException(playback.message)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val SLUGS = arrayOf(
            "",
            "new_update",
            "now_month_hot",
            "play",
            "original",
        )

        private val LABELS = arrayOf(
            "全部",
            "最近更新",
            "本月热播",
            "随机推荐",
            "原创自拍",
        )
    }
}
