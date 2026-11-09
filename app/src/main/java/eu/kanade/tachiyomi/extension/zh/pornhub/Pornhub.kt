package eu.kanade.tachiyomi.extension.zh.pornhub

import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document

/**
 * Pornhub (www.pornhub.com) — a large international tube site.
 *
 * Pornhub renders its catalogue and the player's `flashvars` object server-side, so the raw HTML
 * already carries everything the source needs: listing cards with their title attribute and signed
 * thumbnail, and the details page's `mediaDefinitions` with a signed HLS `master.m3u8`.
 *
 * Two Pornhub specifics drive the design:
 *
 * - **The playlist URL expires (~2h).** The reader must re-read the details page to get a fresh
 *   signature, so [resolveVideoUrl] always reads the page again instead of trusting a cached value.
 * - **The WAF answers datacenter IPs with a maintenance stub.** The app runs on a device with a
 *   residential/mobile IP and a real browser UA, which Pornhub serves normally; requests carry the
 *   mobile UA plus the catalogue origin as Referer.
 *
 * The user browses the official `www.pornhub.com`; the `cn.` subdomain resolves to unrelated
 * IPs (a Dropbox range on public resolvers) and is not a dependable Pornhub host, so the source
 * pins `www`. The browse feed is `/recommended`, which is the page Pornhub personalises for a
 * signed-in account — `/video?o=cm` is a global "most viewed" list that ignores the session, so a
 * user who logs in through the WebView would see no difference there. Pagination is `?page=N`;
 * search goes through `/video/search?search=<q>`. There is no category filter here: Pornhub's
 * taxonomy is deep and its browse pages are driven by the same feed, so the source keeps the
 * recommendation feed and search.
 */
class Pornhub : VideoSource(
    knownDomains = listOf("https://www.pornhub.com"),
) {

    override val name = "Pornhub"
    override val lang = "zh"
    override val supportsLatest = true

    public override fun isListingHtml(html: String): Boolean =
        html.contains("view_video.php") &&
            (html.contains("videoWrapper") || html.contains("videoThumb") || html.contains("videoBox"))

    /**
     * Probe the recommendation feed itself: Pornhub's homepage is a JS shell with no cards, and
     * `/recommended` is the page the listing actually loads.
     */
    override val probePath: String get() = "/recommended"

    /** `/recommended` is the personalised browse feed; pagination is the `?page=N` scheme. */
    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/recommended" else "$baseUrl/recommended?page=$page"

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        PornhubParser.parseListing(document, ::absolute).map {
            ListingItem(it.url, it.title, it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        PornhubParser.findNextPageUrl(document, currentPage, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = PornhubParser.parseDetails(document, ::absolute)
        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = details.genre,
            thumbnailUrl = details.coverUrl,
            dateUpload = details.dateUpload,
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? =
        PornhubParser.parseDetails(document, ::absolute).m3u8Url

    // ============================== Search ==============================

    override fun searchUrlFor(keyword: String, page: Int): String =
        if (page <= 1) "$baseUrl/video/search?search=${keyword.encodeURL()}"
        else "$baseUrl/video/search?search=${keyword.encodeURL()}&page=$page"

    private fun String.encodeURL(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
