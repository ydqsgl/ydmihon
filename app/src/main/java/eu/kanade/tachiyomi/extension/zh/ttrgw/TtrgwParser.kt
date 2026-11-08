package eu.kanade.tachiyomi.extension.zh.ttrgw

import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Parsing for 天天热瓜网, kept free of networking so it can be tested against saved pages.
 */
internal object TtrgwParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    data class Details(
        val title: String,
        val description: String,
        val genre: String?,
        val coverUrl: String?,
    )

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> =
        document.select("div.videoBox").mapNotNull { card ->
            val link = card.selectFirst("a.videoCardLink[href]") ?: return@mapNotNull null
            val href = link.attr("href")
            if (!href.contains("/archives/")) return@mapNotNull null

            val title = card.selectFirst(".videoTitle")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null

            val cover = card.selectFirst(".videoCover")?.attr("data-src")?.trim()
                ?.takeIf { it.isNotBlank() }

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = cover?.let(absoluteUrl),
            )
        }.distinctBy { it.url }

    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val next = document.selectFirst("a[rel=next][href]") ?: return null
        val href = next.attr("href").trim()
        if (href.isEmpty() || href.contains("urlRedirect")) return null
        return absoluteUrl(href)
    }

    fun parseDetails(document: Document): Details {
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" |")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim().orEmpty()

        val description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()

        val genre = document.selectFirst("meta[name=keywords]")?.attr("content")
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }

        // The details cover is a site-relative path served from the same ciphertext CDN.
        val cover = document.selectFirst(".dplayerVideo[data-cover]")?.attr("data-cover")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://webimg.rlkfus.cn/$it" }
            ?.let(::encodePath)

        return Details(
            title = title,
            description = description,
            genre = genre,
            coverUrl = cover,
        )
    }

    fun resolveVideoUrl(document: Document, m3u8Prefix: String): String? {
        val relative = document.selectFirst(".dplayerVideo[data-url]")?.attr("data-url")?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        return "$m3u8Prefix$relative"
    }

    /** Encodes the non-ASCII bytes of a path while keeping `/` and query separators intact. */
    private fun encodePath(url: String): String =
        URLEncoder.encode(url, "UTF-8")
            .replace("+", "%20")
            .replace("%2F", "/")
            .replace("%3F", "?")
            .replace("%3D", "=")
            .replace("%26", "&")
}
