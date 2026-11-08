package eu.kanade.tachiyomi.extension.zh.rou

import eu.kanade.tachiyomi.extension.zh.common.CategoryFilter
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 肉視頻 (rou.video) — a Next.js app-router site.
 *
 * The stream URL is not an attribute anywhere in the DOM: it is serialised into the RSC payload as
 * an obfuscated blob (see [RouParser.parseVideoUrl]). Playback then goes through `/api/hls/<id>`,
 * which 302s to a signed CDN URL — so that endpoint is what gets handed to ExoPlayer, keeping the
 * signature fresh on every play instead of baking an expiring URL into the chapter.
 */
class Rou : VideoSource(
    knownDomains = listOf(
        "https://rou.video",
        "https://www.rou.video",
    ),
) {

    override val name = "肉視頻"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean =
        html.contains("/v/") && (html.contains("aspect-16/10") || html.contains("肉視頻"))

    /**
     * `/` answers with an age gate ("18+ 🔞", ~5 kB, no cards), so the probe uses the library page.
     */
    override val probePath: String get() = "/v"

    /** `/v` is the browse-everything library, newest first. */
    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/v" else "$baseUrl/v?order=createdAt&page=$page"

    override fun searchUrlFor(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        return if (page <= 1) {
            "$baseUrl/search?q=$encoded"
        } else {
            "$baseUrl/search?q=$encoded&page=$page"
        }
    }

    /** Categories are tags (`/t/<name>`); the slugs are already percent-encoded. */
    override fun categoryUrl(category: String?, page: Int): String {
        if (category == null) return listingUrl(page)
        val base = "$baseUrl/t/$category"
        return if (page <= 1) base else "$base?order=createdAt&page=$page"
    }

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(LABELS, SLUGS))

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        RouParser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        RouParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = RouParser.parseDetails(document)
        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = details.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
            thumbnailUrl = details.coverUrl,
            dateUpload = VideoSourceSupport.parseDate(details.uploadDate),
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    /**
     * Returns the site's own HLS endpoint rather than the signed CDN URL it redirects to.
     *
     * `/api/hls/<id>` mints a fresh signature per request, so holding the endpoint keeps playback
     * working after the original signature would have expired.
     */
    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? {
        val path = RouParser.parseVideoUrl(document) ?: return null
        return absolute(path)
    }

    companion object {
        private val SLUGS = arrayOf(
            "",
            "%E4%BA%82%E5%80%AB",
            "%E4%B8%AD%E5%87%BA",
            "%E4%BA%BA%E5%A6%BB",
            "%E5%8F%A3%E4%BA%A4",
            "%E4%B9%B3%E4%BA%A4",
            "%E5%9C%8B%E7%94%A2AV",
            "%E5%85%A8%E5%9C%8B%E6%8E%A2%E8%8A%B1",
            "%E4%B8%AD%E6%96%87%E5%AD%97%E5%B9%95",
            "%E5%8D%88%E5%A4%9C%E5%B0%8B%E8%8A%B1",
            "%E5%8D%83%E4%BA%BA%E6%96%AC%E6%8E%A2%E8%8A%B1",
            "%E5%85%94%E5%AD%90%E5%85%88%E7%94%9F",
            "%E5%96%AE%E9%AB%94%E4%BD%9C%E5%93%81",
            "%E6%97%A5%E5%A5%88%E5%A8%87",
        )

        private val LABELS = arrayOf(
            "全部",
            "亂倫",
            "中出",
            "人妻",
            "口交",
            "乳交",
            "國產AV",
            "全國探花",
            "中文字幕",
            "午夜尋花",
            "千人斬探花",
            "兔子先生",
            "單體作品",
            "日奈嬌",
        )
    }
}
