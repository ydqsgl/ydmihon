package eu.kanade.tachiyomi.extension.zh.huangvideo

import eu.kanade.tachiyomi.extension.zh.common.CategoryFilter
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 黄视频 — a Rails site organised as series (`/series/<id>`) with episodes (`/videos/<id>`).
 *
 * A series is exposed as one library entry whose chapters are its episodes, because the listing
 * would otherwise repeat the same series once per episode.
 *
 * Playback needs a `https://huang.video/` Referer; the CDN answers 403 without one. The reader
 * already sends the chapter page's origin as the Referer, which satisfies that check.
 */
class HuangVideo : VideoSource(
    knownDomains = listOf(
        "https://huang.video",
        "https://www.huang.video",
    ),
) {

    override val name = "黄视频"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean =
        html.contains("series_") && html.contains("/series/")

    /** The homepage is the only full feed; `/series` 404s, so later pages use the paged home. */
    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/?page=$page"

    override fun searchUrlFor(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        return "$baseUrl/search?q=$encoded"
    }

    override fun categoryUrl(category: String?, page: Int): String {
        if (category == null) return listingUrl(page)
        return if (page <= 1) "$baseUrl/c/$category" else "$baseUrl/c/$category?page=$page"
    }

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(LABELS, SLUGS))

    // ============================== Listing ==============================

    /**
     * Series feeds (`/`, `/c/shorts`) carry series cards; the creator shelves (`/c/tangxin`, ...)
     * are single-clip grids instead. Both shapes are accepted so a category never looks empty.
     */
    override fun parseListing(document: Document, page: Int): List<ListingItem> {
        val series = HuangParser.parseListing(document, ::absolute)
        val clips = if (series.isEmpty()) HuangParser.parseClipListing(document, ::absolute) else emptyList()

        return (series.ifEmpty { clips }).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }
    }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        HuangParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    /**
     * A series page lists its episodes, so the chapter list comes from there; a standalone clip
     * page has no episode list and falls back to a single chapter pointing at itself.
     */
    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val isSeries = manga.url.startsWith("/series/") || document.selectFirst("h1#series-title") != null
        val details = if (isSeries) {
            HuangParser.parseSeries(document, ::absolute)
        } else {
            HuangParser.parseClipDetails(document)
        }

        return DetailsResult(
            title = details.title,
            description = details.description,
            thumbnailUrl = details.coverUrl,
            dateUpload = 0L,
            chapterName = "视频",
            chapters = details.episodes.map { ChapterSpec(it.url, it.name) },
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? =
        HuangParser.parseVideoUrl(document)

    companion object {
        /** The site's creator shelves; each is a stable `/c/<name>` feed. */
        private val SLUGS = arrayOf(
            "",
            "shorts",
            "tangxin",
            "tanhua",
            "influencers",
            "pmv",
            "asmr",
        )

        private val LABELS = arrayOf(
            "全部",
            "短剧",
            "糖心",
            "探花",
            "网红",
            "PMV",
            "ASMR",
        )
    }
}
