package eu.kanade.tachiyomi.extension.zh.xxcosplay

import org.jsoup.nodes.Document

/**
 * Parsing for xxcosplay, kept free of networking so it can be tested against saved pages.
 *
 * This is a **gallery** site, not a video site: an album page embeds every photo at once, each in an
 * `<a href>` pointing at the full-size WebP with the same URL repeated in `data-src`. There is no
 * per-image pagination, so one chapter holds the whole set.
 */
internal object XxcosplayParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
        val artist: String?,
    )

    data class AlbumDetails(
        val title: String,
        val description: String,
        val tags: List<String>,
        val artist: String?,
        val coverUrl: String?,
        val imageUrls: List<String>,
    )

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("a[href^=/albums/]").mapNotNull { link ->
            val href = link.attr("href").trim()
            if (!ALBUM_PATH_REGEX.matches(href)) return@mapNotNull null

            val title = link.selectFirst("h3")?.text()?.trim()
                ?: link.selectFirst("img[alt]")?.attr("alt")?.trim()
            if (title.isNullOrBlank()) return@mapNotNull null

            // The artist name is printed directly above the title, outside the <h3>.
            val artist = link.select("p").lastOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = link.selectFirst("img[src]")?.attr("src")?.trim()
                    ?.takeIf { it.startsWith("http") },
                artist = artist,
            )
        }.distinctBy { it.url }
    }

    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val next = document.selectFirst("a[rel=next][href]")
            ?: document.select("a[href]").firstOrNull { it.text().trim() == "下一页" }
            ?: return null
        val href = next.attr("href").trim()
        if (href.isEmpty()) return null
        return absoluteUrl(href)
    }

    fun parseAlbum(document: Document, absoluteUrl: (String) -> String): AlbumDetails {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" | ").trim()

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()

        val tags = document.select("a[href^=/tags/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val artist = document.select("a[href^=/artists/]")
            .map { it.text().trim() }
            .firstOrNull { it.isNotEmpty() }

        val imageUrls = extractImageUrls(document, absoluteUrl)

        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: imageUrls.firstOrNull()

        return AlbumDetails(
            title = title,
            description = description,
            tags = tags,
            artist = artist,
            coverUrl = cover,
            imageUrls = imageUrls,
        )
    }

    /**
     * Collects the album photos in order.
     *
     * The lightbox wrapper repeats the full-size URL in `href` and `data-src`; the nested `<img src>`
     * is a scaled copy on the same CDN. Only the media host is accepted so that unrelated images
     * (ads, avatars, sprite sheets) never leak into the reader.
     */
    fun extractImageUrls(document: Document, absoluteUrl: (String) -> String): List<String> {
        val fromLightbox = document.select("a[data-src]").mapNotNull { anchor ->
            anchor.attr("data-src").trim().takeIf { isAlbumImage(it) }
        }
        if (fromLightbox.isNotEmpty()) return fromLightbox.distinct().map(absoluteUrl)

        return document.select("div[data-controller=lightbox] img[src]").mapNotNull { img ->
            img.attr("src").trim().takeIf { isAlbumImage(it) }
        }.distinct().map(absoluteUrl)
    }

    private fun isAlbumImage(url: String): Boolean =
        url.startsWith("https://media.xxcosplay.com/") || url.startsWith("http://media.xxcosplay.com/")

    private val ALBUM_PATH_REGEX = Regex("""^/albums/[A-Za-z0-9\-_]+$""")
}
