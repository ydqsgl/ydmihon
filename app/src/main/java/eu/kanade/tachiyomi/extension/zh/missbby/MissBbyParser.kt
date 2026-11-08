package eu.kanade.tachiyomi.extension.zh.missbby

import org.jsoup.nodes.Document

/**
 * Parsing for MissBby (missbby.cc) — a Next.js photo-collection site.
 *
 * The home feed lists photo collections as cards `/NNNN-<slug>-photos` (pagination `/?page=N`,
 * "Next" link). A collection detail page renders its images server-side as
 * `img[src^="https://media.missbby.asia/img/"]` and paginates the gallery with `?page=N`.
 *
 * Video cards (`/video/...`) load their stream purely client-side, so this source lists only the
 * photo collections, which are the bulk of the site and parse reliably.
 */
internal object MissBbyParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> =
        document.select("a[href]").mapNotNull { card ->
            val href = card.attr("href").trim()
            if (!isCollectionPath(href)) return@mapNotNull null

            val title = card.attr("title").ifBlank { card.text() }.trim()
            if (title.isEmpty()) return@mapNotNull null

            val cover = card.parent()?.selectFirst("img[src]")?.attr("src")?.trim()
                ?.takeIf { it.startsWith("http") }

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = cover,
            )
        }.distinctBy { it.url }

    private fun isCollectionPath(href: String): Boolean {
        if (!href.startsWith("/") || href.contains("//")) return false
        val path = href.substringBefore("?")
        // `/19258-188-photos` — a numeric id followed by a slug ending in `-photos`.
        val rest = path.removePrefix("/")
        return rest.matches(Regex("\\d+-.+photos"))
    }

    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val next = document.select("a[href]").firstOrNull { it.text() == "Next" && it.attr("href").contains("?page=") }
            ?: return null
        val href = next.attr("href").trim()
        if (href.isEmpty()) return null
        return absoluteUrl(href)
    }

    fun parseDetails(document: Document): Details {
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" - MissBby.cc")?.substringBefore(" | MissBby.cc")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim().orEmpty()

        val description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?.takeIf { it.startsWith("http") }

        return Details(
            title = title,
            description = description,
            coverUrl = cover,
        )
    }

    data class Details(
        val title: String,
        val description: String,
        val coverUrl: String?,
    )

    /** All server-rendered gallery images on [document]. */
    fun galleryImages(document: Document): List<String> =
        document.select("img[src]")
            .map { it.attr("src").trim() }
            .filter { it.startsWith("https://media.missbby.asia/img/") && it.endsWith(".jpg") }

    /**
     * The gallery's "next page" URL for [currentPage], following the rendered `?page=N` links.
     * Null on the last page.
     */
    fun findGalleryNextUrl(document: Document, currentPage: Int): String? {
        val pages = document.select("a[href]").mapNotNull { a ->
            val href = a.attr("href").trim()
            val n = pageNumber(href) ?: return@mapNotNull null
            if (n > currentPage) href to n else null
        }
        val next = pages.minByOrNull { it.second } ?: return null
        return next.first
    }

    fun pageNumber(url: String): Int? {
        val q = url.substringAfter("?", "").substringBefore("#")
        val m = Regex("[?&]page=(\\d+)").find("?$q") ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it > 0 }
    }
}
