package eu.kanade.tachiyomi.extension.zh.tt123av

import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Parsing for 123AV (123av.com/cn) — a JAV catalogue.
 *
 * The `/cn/new` feed and `/cn/search?keyword=` results render a `div.card` grid; pagination is a
 * `a.pager__nav[rel=next]` link. A details page carries the episode embed URL server-side inside an
 * Alpine `player(JSON.parse('[...]'))` payload, e.g.
 *
 * ```
 * https:\/\/sadie-shop.site\/e\/0K7285?poster=...
 * ```
 *
 * That embed host answers `GET /stream?id=<hash>` with `{media:{stream:"<m3u8>"}}`, which is the
 * URL ExoPlayer plays. The m3u8 CDN requires the embed origin (`https://sadie-shop.site/`) as the
 * Referer — see `VideoViewer` for the host-scoped override.
 */
internal object Tt123AvParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    /** `/cn/v/<slug>` cards in the grid. */
    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> =
        document.select("div.card").mapNotNull { card ->
            val link = card.selectFirst("a.card__cover[href]") ?: return@mapNotNull null
            val href = link.attr("href").trim()
            if (!href.startsWith("/cn/v/")) return@mapNotNull null

            val title = card.selectFirst("a.card__link")?.text()?.trim()
                ?.ifEmpty { card.selectFirst("h3.card__title")?.text()?.trim() }
                ?.ifEmpty { null }
                ?: return@mapNotNull null

            val thumb = card.selectFirst("img.card__img[src]")?.attr("src")?.trim()
                ?.takeIf { it.startsWith("http") }

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = thumb,
            )
        }.distinctBy { it.url }

    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val next = document.selectFirst("a[rel=next][href]") ?: return null
        val href = next.attr("href").trim()
        if (href.isEmpty()) return null
        return absoluteUrl(href)
    }

    fun parseDetails(document: Document): Details {
        val title = document.selectFirst("h1.watch__title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            .orEmpty()

        val description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.startsWith("http") }

        return Details(
            title = title,
            description = description,
            coverUrl = cover,
            embedHashId = extractEmbedHashId(document.outerHtml()),
        )
    }

    /** `sadie-shop.site/e/<hashId>` — the first embed source on the page. */
    private fun extractEmbedHashId(html: String): String? =
        EMBED_REGEX.find(html)?.groupValues?.get(1)

    /**
     * Search results URL for [page]. The site answers `?page=N` with the same 12-card grid, so the
     * page number must be present for paging past the first result set.
     */
    fun searchUrl(baseUrl: String, keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return if (page <= 1) {
            "$baseUrl/cn/search?keyword=$encoded"
        } else {
            "$baseUrl/cn/search?keyword=$encoded&page=$page"
        }
    }

    data class Details(
        val title: String,
        val description: String,
        val coverUrl: String?,
        val embedHashId: String?,
    )

    private val EMBED_REGEX = Regex("""sadie-shop\.site[\\/]+e[\\/]+([A-Za-z0-9_]+)""")
}
