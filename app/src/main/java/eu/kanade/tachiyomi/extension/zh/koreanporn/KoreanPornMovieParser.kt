package eu.kanade.tachiyomi.extension.zh.koreanporn

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parsing for KoreanPornMovie, kept free of networking so it can be tested against saved pages.
 *
 * The listing is an `.loop-video` article grid; the playable URL is the JSON-LD VideoObject
 * `contentUrl`, which the site only emits on the details page.
 */
internal object KoreanPornMovieParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    data class Details(
        val title: String,
        val description: String,
        val categories: List<String>,
        val coverUrl: String?,
    )

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("article.loop-video, .loop-video").mapNotNull { card ->
            val anchor = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (!href.startsWith("http") && !href.startsWith("/")) return@mapNotNull null

            val title = cardTitle(card, anchor) ?: return@mapNotNull null
            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = thumbnail(card, anchor)?.let(absoluteUrl),
            )
        }.distinctBy { it.url }
    }

    private fun cardTitle(card: Element, anchor: Element): String? {
        val titled = anchor.attr("title").trim().ifBlank { card.attr("title").trim() }
        if (titled.isNotEmpty()) return titled
        return anchor.selectFirst(".entry-header span, h2, h3")?.text()?.trim()
    }

    private fun thumbnail(card: Element, anchor: Element): String? {
        // `data-main-thumb` carries the full-size cover; the `<img>` is the same URL.
        val data = card.attr("data-main-thumb").trim()
        if (data.isNotEmpty() && !data.startsWith("data:")) return data
        return anchor.selectFirst("img.video-main-thumb, img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }.trim()
                .takeIf { it.isNotEmpty() && !it.startsWith("data:") }
        }
    }

    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        // WordPress emits `<link rel="next" href=".../page/2/">`; prefer it over the anchor pager.
        document.selectFirst("link[rel=next]")?.let { link ->
            val href = link.attr("href").trim()
            if (href.isNotEmpty()) return absoluteUrl(href)
        }
        val next = document.select("a[href*=/page/]").firstOrNull { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val text = link.text().trim()
            text == "Next" || text == "下一页" || text == "»" ||
                (href.endsWith("/page/2/") && text.isNotEmpty())
        } ?: document.select("a[href*=/page/]").firstOrNull { link ->
            link.attr("href").contains("/page/2/")
        } ?: return null

        val href = next.absUrl("href").ifBlank { next.attr("href") }.trim()
        if (href.isEmpty() || !href.contains("/page/")) return null
        return absoluteUrl(href)
    }

    fun parseDetails(document: Document): Details {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().substringBefore(" | ").trim()

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()

        val categories = document.select("a[href*=/category/], .entry-category a, .tags a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val coverUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }

        return Details(
            title = title,
            description = description,
            categories = categories,
            coverUrl = coverUrl,
        )
    }

    /**
     * Reads the MP4 out of the JSON-LD VideoObject `contentUrl`.
     *
     * The site emits a `meta[itemprop=contentUrl]` inside a `<script type="application/ld+json">`
     * block; the same field is what the embedded player streams from.
     */
    fun parseVideoUrl(document: Document): String? {
        // `meta[itemprop=contentUrl]` is the VideoObject's playable URL; the page also embeds image
        // contentUrl entries in JSON-LD, so only accept something that looks like a media file.
        document.select("meta[itemprop=contentUrl]").forEach { meta ->
            val url = meta.attr("content").trim()
            if (isPlayableUrl(url)) return url
        }
        // Fall back to the JSON-LD block for pages that inline the VideoObject as JSON.
        document.select("script[type=application/ld+json]").forEach { script ->
            CONTENT_URL_REGEX.find(script.data())?.let { match ->
                val url = match.groupValues[1].replace("\\/", "/").trim()
                if (isPlayableUrl(url)) return url
            }
        }
        return null
    }

    private fun isPlayableUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".webm") ||
            url.contains("koreanporn.stream")
    }

    private val CONTENT_URL_REGEX = Regex("""["']contentUrl["']\s*:\s*["']([^"']+)["']""")
}
