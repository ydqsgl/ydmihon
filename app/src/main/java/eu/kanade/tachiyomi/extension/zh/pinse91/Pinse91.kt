package eu.kanade.tachiyomi.extension.zh.pinse91

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
 * 91PinSe — a Next.js/Tailwind front end with only two feeds: 最新视频 (`/v/`) and 热门视频 (`/v/hot/`).
 *
 * The player is filled in by script from `POST /api/videos/<id>/playback`, which refuses requests
 * without an `Origin` header. The answer is a plain m3u8 (segments carry their own signed keys), so
 * it is handed to the player as-is; see [resolveVideoUrl].
 */
class Pinse91 : VideoSource(
    knownDomains = listOf(
        "https://91pinse.com",
    ),
) {

    override val name = "91PinSe"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean = Pinse91Parser.isListing(html)

    override val probePath: String get() = "/v/hot/"

    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/v/" else "$baseUrl/v/?page=$page"

    override fun categoryUrl(category: String?, page: Int): String {
        val path = when (category) {
            null, "", "new" -> "/v/"
            else -> "/v/$category/"
        }
        val base = "$baseUrl$path"
        return if (page <= 1) base else "$base?page=$page"
    }

    override fun searchUrlFor(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        return "$baseUrl/v/search?keyword=$encoded"
    }

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(LABELS, SLUGS))

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        Pinse91Parser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        Pinse91Parser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = Pinse91Parser.parseDetails(document)
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
        val id = Pinse91Parser.parseVideoId(document, chapterUrl) ?: return null

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

        return when (val playback = Pinse91Parser.parsePlayback(text)) {
            is Pinse91Parser.Playback.Success -> playback.url
            Pinse91Parser.Playback.Unavailable -> throw VideoUnavailableException("该视频暂时没有可用的播放地址")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val SLUGS = arrayOf(
            "",
            "new",
            "hot",
        )

        private val LABELS = arrayOf(
            "全部",
            "最新视频",
            "热门视频",
        )
    }
}
