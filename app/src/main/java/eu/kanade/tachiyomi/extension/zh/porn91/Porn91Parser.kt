package eu.kanade.tachiyomi.extension.zh.porn91

import eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream

/**
 * Parsing for 91porn, kept free of networking so it can be tested against saved pages.
 *
 * The site is an old Bootstrap 3 forum skin. Two details matter:
 *
 * - titles are double-encoded (`&amp;#039;`), so jsoup hands back the literal `&#039;` and one more
 *   pass is needed before the title is readable;
 * - the playable mp4 is not in a `<video>` tag but written by
 *   `document.write(strencode2("<percent-encoded source tag>"))`, so the tag has to be
 *   percent-decoded and then re-parsed out of the script.
 */
internal object Porn91Parser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    data class Details(
        val title: String,
        val description: String,
        val dateUpload: Long,
        val thumbnailUrl: String?,
        val author: String?,
    )

    fun isListing(html: String): Boolean =
        html.contains("view_video.php") && html.contains("well well-sm")

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("div.well.well-sm").mapNotNull { card ->
            val anchor = card.selectFirst("a[href*=\"view_video.php\"]") ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null

            val title = listingTitle(card, anchor) ?: return@mapNotNull null
            val thumb = card.selectFirst("img.img-responsive[src]")?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = thumb?.let(absoluteUrl),
            )
        }.distinctBy { it.url }
    }

    /**
     * Titles live in a `span.video-title` on both the homepage and the search page; a few cards
     * (the "related videos" strip) only carry the title in the anchor attribute.
     */
    private fun listingTitle(card: Element, anchor: Element): String? {
        return sequenceOf(
            card.selectFirst("span.video-title")?.text(),
            anchor.selectFirst("span.video-title")?.text(),
            anchor.attr("title"),
        )
            .map { cleanText(it.orEmpty()) }
            .firstOrNull { it.isNotEmpty() }
    }

    /**
     * The forward link.
     *
     * Category and search pages render a pager whose last entry is `»`; the homepage instead ends
     * with a single "More feature videos..." anchor that opens the first category page.
     */
    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        document.select("#paging a, .pagingnav a").forEach { anchor ->
            val text = anchor.text().trim()
            if (text.contains('»') || text.contains('›') || text == "下一页") {
                hrefOf(anchor)?.let { return absoluteUrl(it) }
            }
        }
        document.select("a[href]").forEach { anchor ->
            if (anchor.text().contains("More feature videos", ignoreCase = true)) {
                hrefOf(anchor)?.let { return absoluteUrl(it) }
            }
        }
        return null
    }

    fun parseDetails(document: Document): Details {
        val header = document.selectFirst("h4.login_register_header")?.text()
        val fallback = document.title().substringBefore(" - ")
        val title = cleanText(header.orEmpty()).ifEmpty { cleanText(fallback) }

        // The date is the first .title-yakov inside the details box; the second one holds the author.
        val dateUpload = VideoSourceSupport.parseDate(
            document.selectFirst("#videodetails-content span.title-yakov")?.text(),
        )
        val thumbnail = document.selectFirst("video[poster]")?.attr("poster")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val author = document.selectFirst("#videodetails-content a[href*=uprofile] span.title")
            ?.text()
            ?.let(::cleanText)
            ?.takeIf { it.isNotEmpty() }

        return Details(
            title = title,
            description = "",
            dateUpload = dateUpload,
            thumbnailUrl = thumbnail,
            author = author,
        )
    }

    /**
     * Pulls the mp4 out of `document.write(strencode2("..."))`.
     *
     * The payload is the whole `<source src='...' type='video/mp4'>` tag, percent-encoded, so it is
     * decoded first and then the `src` attribute is read off it.
     */
    fun parseVideoUrl(html: String): String? {
        STRENCODE_REGEX.find(html)?.let { match ->
            val decoded = percentDecode(match.groupValues[1])
            SOURCE_SRC_REGEX.find(decoded)?.groupValues?.get(1)?.let { src ->
                val url = src.replace("\\/", "/").trim()
                if (url.startsWith("http")) return url
            }
        }
        // Some skins put the tag straight into the markup instead of encoding it.
        PLAIN_SOURCE_REGEX.find(html)?.groupValues?.get(1)?.let { src ->
            val url = src.replace("\\/", "/").trim()
            if (url.startsWith("http")) return url
        }
        return null
    }

    /** Strips the zero-width markers the site sprinkles into obfuscated titles, then decodes. */
    private fun cleanText(raw: String): String =
        VideoSourceSupport.decodeEntities(ZERO_WIDTH.replace(raw, ""))
            .replace(WHITESPACE, " ")
            .trim()

    /**
     * Percent-decodes the `strencode2` payload byte by byte.
     *
     * `URLDecoder` would also turn a literal `+` into a space; the payload is fully escaped so that
     * cannot happen, but decoding explicitly keeps the behaviour obvious and testable.
     */
    private fun percentDecode(input: String): String {
        val out = ByteArrayOutputStream(input.length)
        var index = 0
        while (index < input.length) {
            val char = input[index]
            if (char == '%' && index + 2 < input.length) {
                val code = input.substring(index + 1, index + 3).toIntOrNull(16)
                if (code != null) {
                    out.write(code)
                    index += 3
                    continue
                }
            }
            out.write(char.code)
            index++
        }
        return out.toString("UTF-8")
    }

    private fun hrefOf(anchor: Element): String? {
        val resolved = anchor.absUrl("href").trim()
        if (resolved.isNotEmpty()) return resolved
        return anchor.attr("href").trim().takeIf { it.isNotEmpty() }
    }

    private val ZERO_WIDTH = Regex("[\\uFE00-\\uFE0F\\u200B-\\u200F\\u202A-\\u202E]")
    private val WHITESPACE = Regex("\\s+")
    private val STRENCODE_REGEX = Regex("""strencode2\(\s*["']([^"']+)["']\s*\)""")
    private val SOURCE_SRC_REGEX = Regex("""src\s*=\s*['"]([^'"]+)['"]""")
    private val PLAIN_SOURCE_REGEX = Regex("""<source[^>]+src\s*=\s*['"]([^'"]+\.mp4[^'"]*)['"]""")
}
