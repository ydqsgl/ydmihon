package eu.kanade.tachiyomi.extension.zh.koreanporn

import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document

/**
 * KoreanPornMovie — a WordPress catalogue of full-length Korean erotic movies.
 *
 * The site is plain WordPress: an `.loop-video` card grid, `/page/N/` pagination, and one film per
 * post whose playable URL sits in the JSON-LD VideoObject as `meta[itemprop=contentUrl]`. The MP4
 * is served from `koreanporn.stream` and plays with an ordinary mobile UA + Referer, so no extra
 * player headers are needed.
 */
class KoreanPornMovie : VideoSource(
    knownDomains = listOf(
        "https://koreanpornmovie.com",
        "https://www.koreanpornmovie.com",
    ),
) {

    override val name = "Korean Porn Movie"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean =
        html.contains("loop-video") && html.contains("video-main-thumb")

    /** `/` is the latest feed; pagination is the WordPress `/page/N/` scheme. */
    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        KoreanPornMovieParser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        KoreanPornMovieParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = KoreanPornMovieParser.parseDetails(document)
        val genre = details.categories
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = genre,
            thumbnailUrl = details.coverUrl,
            chapterName = "电影",
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? =
        KoreanPornMovieParser.parseVideoUrl(document)
}
