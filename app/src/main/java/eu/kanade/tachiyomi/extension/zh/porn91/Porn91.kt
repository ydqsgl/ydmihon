package eu.kanade.tachiyomi.extension.zh.porn91

import eu.kanade.tachiyomi.extension.zh.common.CategoryFilter
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 91porn — the classic Bootstrap-3 video forum, still serving the same markup it has for years.
 *
 * Two quirks drive the shape of this class:
 *
 * - listing pages other than `index.php` are only served to clients that already hold the
 *   `CLIPSHARE` cookie, which `index.php` hands out. Probing the mirror on `index.php` therefore
 *   both validates it and primes the cookie jar, after which every later page just works;
 * - the playable mp4 never appears in the markup as a URL — it is written into the page by
 *   `document.write(strencode2(...))`, so playback decodes that script (see
 *   [Porn91Parser.parseVideoUrl]).
 */
class Porn91 : VideoSource(
    knownDomains = listOf(
        "https://www.91porn.com",
        "https://91porn.com",
    ),
) {

    override val name = "91Porn"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean = Porn91Parser.isListing(html)

    /** The one page that answers without the `CLIPSHARE` cookie, and the one that sets it. */
    override val probePath: String get() = "/index.php"

    override fun listingUrl(page: Int): String =
        if (page <= 1) {
            "$baseUrl/index.php"
        } else {
            "$baseUrl/v.php?category=rf&viewtype=basic&page=$page"
        }

    override fun categoryUrl(category: String?, page: Int): String {
        val base = "$baseUrl/v.php?category=${category ?: "rf"}&viewtype=basic"
        return if (page <= 1) base else "$base&page=$page"
    }

    override fun searchUrlFor(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        return "$baseUrl/search_result.php?viewtype=basic&page=$page" +
            "&search_type=search_videos&search_id=$encoded&search_key=&min_duration=0"
    }

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(LABELS, SLUGS))

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        Porn91Parser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        Porn91Parser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = Porn91Parser.parseDetails(document)
        val description = details.author?.let { "作者: $it" }.orEmpty()

        return DetailsResult(
            title = details.title,
            description = description,
            genre = null,
            thumbnailUrl = details.thumbnailUrl,
            dateUpload = details.dateUpload,
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? =
        Porn91Parser.parseVideoUrl(document.outerHtml())

    companion object {
        /**
         * Slugs are what the site's own nav links carry; the labels are their Chinese names.
         * `top` appears twice on the site (this week / last week) and only the weekly one is kept.
         */
        private val SLUGS = arrayOf(
            "",
            "ori",
            "hot",
            "top",
            "long",
            "longer",
            "tf",
            "rf",
            "hd",
            "md",
            "mf",
        )

        private val LABELS = arrayOf(
            "全部",
            "91原创",
            "当前最热",
            "本周最佳",
            "10分钟以上",
            "20分钟以上",
            "收藏最多",
            "最近加精",
            "高清",
            "评论最多",
            "本月收藏",
        )
    }
}
