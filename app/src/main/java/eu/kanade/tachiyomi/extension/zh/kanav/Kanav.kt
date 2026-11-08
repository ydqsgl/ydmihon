package eu.kanade.tachiyomi.extension.zh.kanav

import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document

/**
 * KanAV (kanav.online) — an AV catalogue (Next.js + Rails/Pagy) listing `/videos/<code>/` entries.
 *
 * Each page embeds an `<video>` plus one or more "line" buttons
 * `button[data-player-target=source][data-url]`, whose `data-url` is a plain HLS master playlist on
 * `xbv2608.com` with no encryption and no Referer check — ExoPlayer plays it as-is.
 *
 * The listing lives at `/videos` (24 cards) and paginates with Pagy's `?page=N` scheme; the source
 * follows the rendered `a[rel=next]` link rather than assuming a page formula.
 */
class Kanav : VideoSource(
    knownDomains = listOf("https://kanav.online"),
) {

    override val name = "KanAV"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean =
        html.contains("/videos/") && html.contains("data-player-target")

    /** `/videos` is the latest feed; the page-2+ URL is learned from the rendered pager. */
    override fun listingUrl(page: Int): String = "$baseUrl/videos"

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        KanavParser.parseListing(document, ::absolute).map {
            ListingItem(it.url, it.title, it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document): String? =
        KanavParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = KanavParser.parseDetails(document)
        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = details.genre,
            thumbnailUrl = details.coverUrl,
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? =
        KanavParser.resolveVideoUrl(document)
}
