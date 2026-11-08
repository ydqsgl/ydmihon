package eu.kanade.tachiyomi.extension.zh.taiav

import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.extension.zh.common.VideoUnavailableException
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Taiav — a UIkit tube with a login-gated player.
 *
 * The m3u8 is never in the HTML: `GET /api/getmovie?type=<height>&id=<id>` returns it. Only the
 * page's own default height (`1280`) is served to anonymous visitors, so that is what is requested;
 * other heights answer "该视频分辨率对应的用户组未设置".
 *
 * The listing has no pagination at all — the homepage and `/hots` are single fixed pages — so
 * browsing is shallow and search is the real way in.
 */
class Taiav : VideoSource(
    knownDomains = listOf(
        "https://taiav.com",
        "https://www.taiav.com",
    ),
) {

    override val name = "Taiav"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean =
        html.contains("/movie/") && (html.contains("movie-card") || html.contains("videos-box"))

    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/cn" else "$baseUrl/cn?page=$page"

    override fun searchUrlFor(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        return if (page <= 1) {
            "$baseUrl/cn/search?q=$encoded"
        } else {
            "$baseUrl/cn/search?q=$encoded&page=$page"
        }
    }

    override fun categoryUrl(category: String?, page: Int): String {
        if (category == null) return listingUrl(page)
        // Categories are tags, which do paginate.
        return if (page <= 1) {
            "$baseUrl/cn/tag/$category"
        } else {
            "$baseUrl/cn/tag/$category?page=$page"
        }
    }

    override fun getFilterList(): eu.kanade.tachiyomi.source.model.FilterList =
        eu.kanade.tachiyomi.source.model.FilterList(
            eu.kanade.tachiyomi.extension.zh.common.CategoryFilter(LABELS, SLUGS),
        )

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        TaiavParser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        TaiavParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = TaiavParser.parseDetails(document, ::absolute)
        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = details.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
            thumbnailUrl = details.coverUrl,
            dateUpload = eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport
                .parseDate(details.uploadDate),
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    /**
     * Asks the player API for the default encode.
     *
     * A refusal is surfaced as [VideoUnavailableException] carrying the site's own message, so the
     * user sees "需要登录/积分" rather than a generic source error.
     */
    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? {
        val id = TaiavParser.movieId(document, chapterUrl) ?: return null

        val request = okhttp3.Request.Builder()
            .url("$baseUrl/api/getmovie?type=1280&id=$id")
            .headers(requestHeaders().newBuilder().set("Referer", chapterUrl).build())
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }

        return when (val result = TaiavParser.parsePlayback(body, ::absolute)) {
            is TaiavParser.PlaybackResult.Success -> result.url
            is TaiavParser.PlaybackResult.Unavailable -> throw VideoUnavailableException(result.reason)
        }
    }

    companion object {
        private val SLUGS = arrayOf(
            "",
            "中出",
            "口交",
            "Cosplay",
            "Onlyfans",
            "FC2",
            "3P",
            "不倫",
            "OL",
            "中国",
        )

        private val LABELS = arrayOf(
            "全部",
            "中出",
            "口交",
            "Cosplay",
            "Onlyfans",
            "FC2",
            "3P",
            "不倫",
            "OL",
            "中国",
        )
    }
}
