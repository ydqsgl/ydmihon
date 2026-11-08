package eu.kanade.tachiyomi.extension.zh.missbby

import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document

/**
 * MissBby (missbby.cc) — an Asian photo-collection site (Next.js).
 *
 * The home feed lists photo collections (`/NNNN-<slug>-photos`); a collection detail page renders
 * all its images server-side under `https://media.missbby.asia/img/...` (the path is an obfuscated
 * name, not an encrypted payload — the CDN returns a normal JPEG). This source is a pure gallery:
 * video cards load their stream client-side and are not listed.
 */
class MissBby : VideoSource(
    knownDomains = listOf("https://missbby.cc"),
) {

    override val name = "MissBby"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean =
        html.contains("-photos") && html.contains("?page=")

    /** `/` is the feed; page 2+ is learned from the rendered "Next" link. */
    override fun listingUrl(page: Int): String = if (page == 1) "$baseUrl/" else "$baseUrl/?page=$page"

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        MissBbyParser.parseListing(document, ::absolute).map {
            ListingItem(it.url, it.title, it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        MissBbyParser.findNextPageUrl(document, ::absolute)

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = MissBbyParser.parseDetails(document)
        return DetailsResult(
            title = details.title,
            description = details.description,
            thumbnailUrl = details.coverUrl,
            chapterName = "图集",
        )
    }

    /**
     * Photo collection: images are served server-side, so the whole gallery is read by following
     * the rendered `?page=N` links until the last page.
     */
    override suspend fun parseGalleryPages(document: Document): List<String>? {
        val images = LinkedHashSet<String>()
        var current = document
        var pageNum = MissBbyParser.pageNumber(current.location() ?: "") ?: 1

        collect(current, images)
        var guard = 0
        while (guard < MAX_GALLERY_PAGES) {
            val next = MissBbyParser.findGalleryNextUrl(current, pageNum) ?: break
            val nextDoc = fetchDocument(absolute(next))
            val before = images.size
            collect(nextDoc, images)
            current = nextDoc
            pageNum = MissBbyParser.pageNumber(next) ?: (pageNum + 1)
            guard++
            if (images.size == before) break
        }

        return images.toList()
    }

    private fun collect(document: Document, into: MutableCollection<String>) {
        into.addAll(MissBbyParser.galleryImages(document))
    }

    companion object {
        private const val MAX_GALLERY_PAGES = 60
    }
}
