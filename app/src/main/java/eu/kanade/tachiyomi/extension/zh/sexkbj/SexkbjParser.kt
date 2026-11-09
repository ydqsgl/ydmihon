package eu.kanade.tachiyomi.extension.zh.sexkbj

import eu.kanade.tachiyomi.extension.zh.common.JsPacker
import eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport
import org.jsoup.nodes.Document

/**
 * Parsing for SexKBJ, kept free of networking so it can be tested on saved pages.
 *
 * The WordPress side is ordinary: `article.loop-video` cards on the listing, `h1.entry-title` and
 * `og:image` on the details page. Playback is the interesting part — the details page only carries
 * an `<IFRAME SRC="https://sexkbj.top/embed/<id>">`, and that embed page builds the player from a
 * packer-obfuscated script in which the stream lives as `links = {"hls2": …, "hls3": …}`.
 */
internal object SexkbjParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    data class Details(
        val title: String,
        val description: String,
        val genre: String?,
        val thumbnailUrl: String?,
        val dateUpload: Long,
    )

    fun isListing(html: String): Boolean = html.contains("loop-video")

    // ============================== Listing ==============================

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        val cards = document.select("article.loop-video")
            .ifEmpty { document.select("article.thumb-block") }

        return cards.mapNotNull { card ->
            val anchor = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null

            val image = card.selectFirst("img[data-src]") ?: card.selectFirst("img[src]")
            val title = sequenceOf(
                card.selectFirst("header.entry-header")?.text(),
                anchor.attr("title"),
                image?.attr("alt"),
            ).map { it?.trim().orEmpty() }
                .firstOrNull { it.isNotEmpty() }
                ?: return@mapNotNull null

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = image?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                    ?.trim()
                    ?.takeIf { it.startsWith("http") },
            )
        }.distinctBy { it.url }
    }

    /**
     * WordPress prints the forward link in the head (`<link rel="next">`) and repeats it in the
     * pager, so either anchor is enough; the numeric link is the fallback for odd themes.
     */
    fun findNextPageUrl(document: Document, currentPage: Int, absoluteUrl: (String) -> String): String? {
        val anchor = document.selectFirst("link[rel=next][href]")
            ?: document.selectFirst("a[rel=next][href]")
            ?: document.selectFirst("a.next[href]")
            ?: return null

        val href = anchor.absUrl("href").ifEmpty { anchor.attr("href") }.trim()
        return href.takeIf { it.isNotEmpty() }?.let(absoluteUrl)
    }

    // ============================== Details ==============================

    fun parseDetails(document: Document): Details {
        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()

        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != title }
            .orEmpty()

        val genre = sequenceOf(
            document.selectFirst("meta[property=article:section]")?.attr("content"),
            document.select("a[href*=\"/tag/\"]").joinToString(", ") { it.text().trim() }
                .takeIf { it.isNotEmpty() },
        ).map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() }

        return Details(
            title = title,
            description = description,
            genre = genre,
            thumbnailUrl = document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            dateUpload = VideoSourceSupport.parseDate(
                document.selectFirst("meta[property=article:published_time]")?.attr("content")
                    ?: document.selectFirst("meta[itemprop=uploadDate]")?.attr("content"),
            ),
        )
    }

    /** The embed iframe the player is mounted in, resolved to an absolute URL. */
    fun parseEmbedUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val iframe = document.selectFirst("iframe[src]") ?: return null
        val src = iframe.attr("src").trim()
        return src.takeIf { it.isNotEmpty() }?.let(absoluteUrl)
    }

    // ============================== Playback ==============================

    /**
     * Picks a playlist out of the embed page.
     *
     * The player itself prefers `hls4 || hls3 || hls2`, but `hls3` is served as `.txt` — a playlist
     * ExoPlayer will not treat as HLS — so a `.m3u8` wins when both exist.
     */
    fun parseStream(embedHtml: String): String? {
        val unpacked = JsPacker.unpack(embedHtml) ?: embedHtml
        val block = LINKS_BLOCK.find(unpacked)?.groupValues?.get(1)
        val urls = if (block != null) {
            LINK_URL.findAll(block).map { it.groupValues[1] }.toList()
        } else {
            LINK_URL.findAll(unpacked).map { it.groupValues[1] }.filter { it.contains("://") }.toList()
        }
        return urls.firstOrNull { it.contains(".m3u8") } ?: urls.firstOrNull { it.contains("/master") }
    }

    private val LINKS_BLOCK = Regex("""links\s*=\s*\{([^}]*)}""")
    private val LINK_URL = Regex(""""([^"]+?)"""")
}
