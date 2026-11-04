package eu.kanade.tachiyomi.extension.zh.huangvideo

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parsing for 黄视频, kept free of networking so it can be tested against saved pages.
 *
 * The site is a Rails app with two content shapes:
 *
 * - **series** (`/series/<id>`): an anthology whose episodes are separate `/videos/<id>` pages;
 * - **standalone clips** (`/videos/<id>`): a single episode with no parent series.
 *
 * A series becomes one library entry with one chapter per episode; a standalone clip becomes a
 * one-chapter entry. Episodes are never mixed into the listing, which would otherwise show the same
 * series a dozen times.
 */
internal object HuangParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
        val subtitle: String?,
    )

    data class Episode(
        val url: String,
        val name: String,
    )

    data class SeriesDetails(
        val title: String,
        val description: String,
        val coverUrl: String?,
        val episodes: List<Episode>,
        val duration: String?,
    )

    /**
     * Reads series cards from the listing.
     *
     * The homepage interleaves carousel slides (which have no `article id="series_N"`), creator
     * shelves and episode links; only the card markup is a stable series entry.
     */
    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("article[id^=series_]").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/series/]") ?: return@mapNotNull null
            val href = link.attr("href").trim()
            if (!SERIES_PATH_REGEX.matches(href)) return@mapNotNull null

            val title = card.selectFirst("h3")?.text()?.trim()
                ?: link.attr("aria-label").trim()
            if (title.isNullOrBlank()) return@mapNotNull null

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = card.selectFirst("img[src]")?.attr("src")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(absoluteUrl),
                subtitle = card.selectFirst(".card-body p")?.text()?.trim(),
            )
        }.distinctBy { it.url }
    }

    /**
     * Reads standalone clips: `/videos/<id>` links that are *not* nested inside an episode list.
     *
     * On a series page the same URLs appear as episode rows, so callers must only use this on pages
     * that are known to be clip feeds.
     */
    fun parseClipListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("article[id^=video_]").mapNotNull { card ->
            val link = card.selectFirst("a[href^=/videos/]") ?: return@mapNotNull null
            val href = link.attr("href").trim()
            if (!VIDEO_PATH_REGEX.matches(href)) return@mapNotNull null
            val title = card.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = card.selectFirst("img[src]")?.attr("src")?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(absoluteUrl),
                subtitle = null,
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

    /** Series page: hero cover, description and the episode list. */
    fun parseSeries(document: Document, absoluteUrl: (String) -> String): SeriesDetails {
        val title = document.selectFirst("h1#series-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" | ").trim()

        val description = document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }

        // Episodes render as <ol> rows: a link plus a "第N集" label and a duration.
        val episodes = document.select("ol a[href^=/videos/]").mapNotNull { row ->
            val href = row.attr("href").trim()
            if (!VIDEO_PATH_REGEX.matches(href)) return@mapNotNull null
            val label = row.selectFirst("span")?.text()?.trim()
            Episode(
                url = absoluteUrl(href),
                name = label?.takeIf { it.isNotEmpty() } ?: "第${episodesCount(document)}集",
            )
        }.distinctBy { it.url }

        val duration = document.selectFirst("p:contains(全)")?.text()?.let { DURATION_HINT_REGEX.find(it)?.value }

        return SeriesDetails(
            title = title,
            description = description,
            coverUrl = cover,
            episodes = episodes,
            duration = duration,
        )
    }

    private fun episodesCount(document: Document): Int =
        document.select("ol a[href^=/videos/]").size.coerceAtLeast(1)

    /** Standalone clip page: the m3u8 sits in the player's data attributes. */
    fun parseVideoUrl(document: Document): String? {
        document.selectFirst("[data-xg-player-source-value]")?.let { player ->
            val src = player.attr("data-xg-player-source-value").trim()
            if (src.isNotEmpty()) return src
        }
        // Fallback: the JSON-LD VideoObject carries contentUrl on some templates.
        document.selectFirst("script[type=application/ld+json]")?.data()?.let { json ->
            CONTENT_URL_REGEX.find(json)?.let { return it.groupValues[1].replace("\\/", "/") }
        }
        return null
    }

    fun parseClipDetails(document: Document): SeriesDetails {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" | ").orEmpty()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        val cover = document.selectFirst("[data-xg-player-poster-value]")?.attr("data-xg-player-poster-value")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val duration = document.selectFirst("script[type=application/ld+json]")?.data()
            ?.let { DURATION_REGEX.find(it)?.groupValues?.get(1) }

        return SeriesDetails(
            title = title,
            description = description,
            coverUrl = cover,
            episodes = emptyList(),
            duration = duration,
        )
    }

    /** Cover for a card whose `<img>` may be lazy (`data-src`) rather than eager. */
    fun coverFrom(element: Element?): String? {
        if (element == null) return null
        val img = element.selectFirst("img") ?: return null
        return img.attr("src").ifBlank { img.attr("data-src") }.trim().takeIf { it.isNotEmpty() }
    }

    private val SERIES_PATH_REGEX = Regex("""^/series/\d+$""")
    private val VIDEO_PATH_REGEX = Regex("""^/videos/\d+$""")
    private val CONTENT_URL_REGEX = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
    private val DURATION_REGEX = Regex(""""duration"\s*:\s*"PT(\d+)S"""")
    private val DURATION_HINT_REGEX = Regex("""全\s*\d+\s*集""")
}
