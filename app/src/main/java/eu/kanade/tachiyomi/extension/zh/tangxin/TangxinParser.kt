package eu.kanade.tachiyomi.extension.zh.tangxin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

/**
 * Parsing for 糖心Vlog, kept free of networking so it can be tested against saved pages.
 *
 * The site is a static Astro build:
 *
 * - listing pages are pre-rendered with a fixed set of cards and **no pagination** (`?page=2`
 *   returns the same cards);
 * - search is client-side, backed by a single `search-index.json` holding every clip.
 *
 * That index is the only way to browse beyond the first page, so it is used both for search and for
 * paging through the whole catalogue.
 */
internal object TangxinParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
        val duration: String?,
    )

    data class IndexEntry(
        val id: String,
        val title: String,
        val actor: String?,
        val tags: List<String>,
        val duration: String?,
        val description: String?,
    )

    data class Details(
        val title: String,
        val description: String,
        val tags: List<String>,
        val coverUrl: String?,
        val uploadDate: String?,
    )

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("article.card").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/v/]") ?: return@mapNotNull null
            val href = link.attr("href").trim()
            if (!VIDEO_PATH_REGEX.matches(href)) return@mapNotNull null

            val title = card.selectFirst("h3.title")?.text()?.trim()
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()
            if (title.isNullOrBlank()) return@mapNotNull null

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = card.selectFirst("img[src]")?.attr("src")?.trim()
                    ?.takeIf { it.startsWith("http") },
                duration = card.selectFirst("span.duration")?.text()?.trim(),
            )
        }.distinctBy { it.url }
    }

    /**
     * Parses the client search index.
     *
     * Each entry is `[id, title, actor, tags[], duration, description]`. Malformed rows are skipped
     * rather than aborting the whole parse: the index is 10k+ entries and one bad row should not
     * empty the source.
     */
    fun parseIndex(json: String): List<IndexEntry> {
        val root = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return emptyList()
        return root.mapNotNull { element -> runCatching { toEntry(element.jsonArray) }.getOrNull() }
    }

    private fun toEntry(row: JsonArray): IndexEntry? {
        val id = row.getOrNull(0)?.jsonPrimitive?.contentOrNullSafe() ?: return null
        val title = row.getOrNull(1)?.jsonPrimitive?.contentOrNullSafe() ?: return null
        if (id.isBlank() || title.isBlank()) return null

        val actor = row.getOrNull(2)?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
        val tags = runCatching {
            row.getOrNull(3)?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
        }.getOrNull().orEmpty()
        val duration = row.getOrNull(4)?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
        val description = row.getOrNull(5)?.jsonPrimitive?.contentOrNullSafe()?.takeIf { it.isNotBlank() }

        return IndexEntry(
            id = id,
            title = title,
            actor = actor,
            tags = tags,
            duration = duration,
            description = description,
        )
    }

    /**
     * Filters the index the same way the site's search does: a case-insensitive substring match on
     * the title, the actor or any tag.
     */
    fun search(entries: List<IndexEntry>, keyword: String): List<IndexEntry> {
        val needle = keyword.trim()
        if (needle.isEmpty()) return entries
        val lowered = needle.lowercase()
        return entries.filter { entry ->
            entry.title.lowercase().contains(lowered) ||
                entry.actor?.lowercase()?.contains(lowered) == true ||
                entry.tags.any { it.lowercase().contains(lowered) }
        }
    }

    fun parseDetails(document: Document, absoluteUrl: (String) -> String): Details {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ").orEmpty()

        // The clip description is often the only prose on the page.
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content").orEmpty()

        val tags = document.select("a[href^=/tag/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val cover = document.selectFirst("video[poster]")?.attr("poster")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val uploadDate = document.selectFirst("time[datetime]")?.attr("datetime")
            ?: document.selectFirst("script[type=application/ld+json]")?.data()
                ?.let { UPLOAD_DATE_REGEX.find(it)?.groupValues?.get(1) }

        return Details(title = title, description = description, tags = tags, coverUrl = cover, uploadDate = uploadDate)
    }

    /**
     * The player is a plain `<video>` whose `src` is filled from `index.m3u8` on the CDN.
     *
     * Because the tag ships without a `src`, the URL is read from the page's inline script; the CDN
     * requires `https://tangxinvlog.app/` as Referer, which the reader already sends.
     */
    fun parseVideoUrl(document: Document): String? {
        document.selectFirst("video[src]")?.let { video ->
            val src = video.attr("src").trim()
            if (src.startsWith("http")) return src
            if (src.startsWith("/")) return src
        }
        val html = document.outerHtml()
        M3U8_REGEX.find(html)?.let { return it.groupValues[1].replace("\\/", "/") }
        return null
    }

    /**
     * Turns a listing card's absolute URL into the site-relative path Mihon stores.
     *
     * Kept here (rather than inline in the source) so it can be tested: the same operation is easy
     * to get wrong inside an `apply` block, where a bare `url` resolves to the *target's* lateinit
     * property instead of the card's field.
     */
    fun relativePath(absoluteUrl: String, baseUrl: String): String {
        return absoluteUrl.removePrefix(baseUrl).ifBlank { absoluteUrl }
    }

    /** Path for a catalogue-index row. */
    fun indexPath(id: String): String = "/v/$id/"

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()?.takeIf { it != "null" }

    private val VIDEO_PATH_REGEX = Regex("""^/v/[a-z0-9]+/?$""")
    private val M3U8_REGEX = Regex("""(https?://[^"'\s<>]+?\.m3u8)""")
    private val UPLOAD_DATE_REGEX = Regex(""""uploadDate"\s*:\s*"([^"]+)"""")
}
