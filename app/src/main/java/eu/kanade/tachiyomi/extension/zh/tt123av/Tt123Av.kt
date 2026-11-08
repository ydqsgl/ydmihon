package eu.kanade.tachiyomi.extension.zh.tt123av

import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.extension.zh.common.VideoUnavailableException
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import org.jsoup.nodes.Document

/**
 * 123AV (123av.com/cn) — a JAV catalogue.
 *
 * The `/cn/new` feed (and `/cn/search?keyword=`) lists `/cn/v/<slug>` cards. A details page embeds
 * a third-party player URL (`sadie-shop.site/e/<hashId>`); that host's `GET /stream?id=<hashId>`
 * returns the HLS master playlist URL, which ExoPlayer plays. The playlist CDN requires the embed
 * origin as the Referer, which `VideoViewer` supplies via a host-scoped override.
 */
class Tt123Av : VideoSource(
    knownDomains = listOf("https://123av.com"),
) {

    override val name = "123AV"
    override val lang = "zh"
    override val supportsLatest = true

    override val probePath: String get() = "/cn/new"

    override fun isListingHtml(html: String): Boolean =
        html.contains("/cn/v/") && html.contains("class=\"card\"")

    /** `/cn/new` is the catalogue feed; page 2+ comes from the rendered `rel=next` link. */
    override fun listingUrl(page: Int): String = "$baseUrl/cn/new"

    /**
     * Search results paginate with the same `?page=N` scheme as the feed. The shared
     * [VideoSource] lookup only trusts a learned "next" link for keyword-less browsing, so the
     * page number has to be part of this URL for search to page past the first result set.
     */
    override fun searchUrlFor(keyword: String, page: Int): String =
        Tt123AvParser.searchUrl(baseUrl, keyword, page)

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        Tt123AvParser.parseListing(document, ::absolute).map {
            ListingItem(it.url, it.title, it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document): String? =
        Tt123AvParser.findNextPageUrl(document, ::absolute)

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = Tt123AvParser.parseDetails(document)
        return DetailsResult(
            title = details.title,
            description = details.description,
            thumbnailUrl = details.coverUrl,
            chapterName = "视频",
        )
    }

    /**
     * Resolves the HLS playlist through the embed host's `/stream?id=` API.
     *
     * When the embed hash is missing (e.g. the title needs login or the player is paywalled) the
     * source reports a targeted message instead of a generic error.
     */
    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? {
        val hashId = Tt123AvParser.parseDetails(document).embedHashId
            ?: throw VideoUnavailableException("该视频暂无可播放源")

        val request = okhttp3.Request.Builder()
            .url("https://sadie-shop.site/stream?id=$hashId")
            .headers(streamHeaders())
            .build()

        val body = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }

        return STREAM_URL_REGEX.find(body)?.groupValues?.get(1)?.takeIf { it.startsWith("http") }
    }

    private fun streamHeaders(): Headers = Headers.Builder()
        .add("User-Agent", eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport.USER_AGENT)
        .add("Accept", "application/json, text/plain, */*")
        .build()

    companion object {
        private val STREAM_URL_REGEX = Regex(""""stream"\s*:\s*"(https?://[^"]+)"""")
    }
}
