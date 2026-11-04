package eu.kanade.tachiyomi.extension.zh.xxcosplay

import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * xxcosplay — a Cosplay photo gallery.
 *
 * This is the one gallery in the bundle: an album page embeds every photo at once, so the reader
 * pages through images instead of playing a video ([parseGalleryPages] instead of a video URL).
 * Videos appear on some albums as a bonus, but the album's stills are the primary content and the
 * images are what the site's own viewer shows.
 */
class Xxcosplay : VideoSource(
    knownDomains = listOf(
        "https://xxcosplay.com",
        "https://www.xxcosplay.com",
    ),
) {

    override val name = "xxcosplay"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean =
        html.contains("/albums/") && (html.contains("break-inside-avoid") || html.contains("xxcosplay"))

    /** The homepage is a newest-first grid; later pages use the same `?page=N`. */
    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/?page=$page"

    override fun searchUrlFor(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        return if (page <= 1) {
            "$baseUrl/search?q=$encoded"
        } else {
            "$baseUrl/search?q=$encoded&page=$page"
        }
    }

    override fun categoryUrl(category: String?, page: Int): String = listingUrl(page)

    override fun getFilterList(): FilterList = FilterList()

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        XxcosplayParser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document): String? =
        XxcosplayParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val album = XxcosplayParser.parseAlbum(document, ::absolute)

        val description = buildString {
            album.artist?.let { append("模特：").append(it).append('\n') }
            if (album.tags.isNotEmpty()) append("标签：").append(album.tags.joinToString(" / "))
            if (album.description.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(album.description)
            }
            if (album.imageUrls.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("\n共 ").append(album.imageUrls.size).append(" 张")
            }
        }

        return DetailsResult(
            title = album.title,
            description = description,
            genre = (listOfNotNull(album.artist) + album.tags)
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", "),
            thumbnailUrl = album.coverUrl,
            chapterName = "全部图片",
        )
    }

    // ============================== Pages ==============================

    /** The album's photos, in the order the page embeds them. */
    override suspend fun parseGalleryPages(document: Document): List<String> =
        XxcosplayParser.extractImageUrls(document, ::absolute)
}
