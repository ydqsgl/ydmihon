package eu.kanade.tachiyomi.extension.zh.sexkbj

import eu.kanade.tachiyomi.extension.zh.common.CategoryFilter
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.extension.zh.common.VideoUnavailableException
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * SexKBJ (sexkbj.com) — a WordPress site of Korean BJ cam recordings.
 *
 * The catalogue is plain WordPress: `/page/N/` pagination, `?s=<q>` search and `/category/<slug>/`
 * archives. The clip is not hosted here — the post carries a single
 * `<IFRAME SRC="https://sexkbj.top/embed/<id>">` and the playlist only appears after the embed
 * page's packer-obfuscated player script is unpacked (see [SexkbjParser]).
 */
class Sexkbj : VideoSource(
    knownDomains = listOf(
        "https://sexkbj.com",
    ),
) {

    override val name = "SexKBJ"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean = SexkbjParser.isListing(html)

    override val probePath: String get() = "/"

    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    override fun categoryUrl(category: String?, page: Int): String {
        val slug = category?.trim().orEmpty()
        if (slug.isEmpty()) return listingUrl(page)
        val path = "$baseUrl/category/$slug/"
        return if (page <= 1) path else "$baseUrl/page/$page/category/$slug/"
    }

    /** WordPress keeps `?s=` on every page and takes the page number from the path. */
    override fun searchUrlFor(keyword: String, page: Int): String {
        val query = "?s=${keyword.encodeURL()}"
        return if (page <= 1) "$baseUrl/$query" else "$baseUrl/page/$page/$query"
    }

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(LABELS, SLUGS))

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        SexkbjParser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        SexkbjParser.findNextPageUrl(document, currentPage, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = SexkbjParser.parseDetails(document)
        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = details.genre,
            thumbnailUrl = details.thumbnailUrl,
            dateUpload = details.dateUpload,
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? {
        val embedUrl = SexkbjParser.parseEmbedUrl(document, ::absolute) ?: return null

        val embedHtml = client.newCall(GET(embedUrl, requestHeaders())).await().use { it.body.string() }

        return SexkbjParser.parseStream(embedHtml)
            ?: throw VideoUnavailableException("播放地址解析失败")
    }

    private fun String.encodeURL(): String =
        URLEncoder.encode(this, "UTF-8").replace("+", "%20")

    companion object {
        private val SLUGS = arrayOf(
            "",
            "korean-bj",
            "premium",
            "afreecatv",
            "korean-amateur",
        )

        private val LABELS = arrayOf(
            "全部",
            "韩国BJ",
            "Premium",
            "AfreecaTV",
            "韩国素人",
        )
    }
}
