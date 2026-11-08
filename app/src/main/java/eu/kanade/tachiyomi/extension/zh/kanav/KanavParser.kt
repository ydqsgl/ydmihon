package eu.kanade.tachiyomi.extension.zh.kanav

import org.jsoup.nodes.Document

/**
 * Parsing for KanAV, kept free of networking so it can be tested against saved pages.
 */
internal object KanavParser {

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
        document.select("a[href]").mapNotNull { card ->
            val href = card.attr("href").trim()
            if (!isVideoPath(href)) return@mapNotNull null

            val title = card.selectFirst("h2")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null

            val code = href.substringAfterLast("/").substringBefore("?")
            val cover = card.selectFirst("img[src]")?.attr("src")?.trim()
                ?.takeIf { it.startsWith("http") }

            ListingItem(
                url = absoluteUrl(href),
                title = if (code.isNotEmpty()) "$code · $title" else title,
                thumbnailUrl = cover,
            )
        }.distinctBy { it.url }

    private fun isVideoPath(href: String): Boolean {
        if (!href.startsWith("/videos/")) return false
        val rest = href.removePrefix("/videos/")
        // A video code is uppercase alphanumeric with a hyphen (DLDSS-561); skip index/actress/… pages.
        return rest.isNotEmpty() && !rest.contains("/") && rest[0].isUpperCase()
    }

    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val next = document.selectFirst("a[rel=next][href]") ?: return null
        val href = next.attr("href").trim()
        if (href.isEmpty()) return null
        return absoluteUrl(href)
    }

    fun parseDetails(document: Document): Details {
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" · KanAV")?.substringBefore(" | KanAV")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?.substringBefore(" - ")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim().orEmpty()

        val description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()

        val cover = document.selectFirst("video[poster]")?.attr("poster")?.trim()
            ?.takeIf { it.startsWith("http") }

        val genre = document.select("a[href*=/categories/], a[href*=/actresses/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        return Details(
            title = title,
            description = description,
            genre = genre,
            coverUrl = cover,
        )
    }

    fun resolveVideoUrl(document: Document): String? =
        document.select("button[data-player-target=source][data-url]")
            .mapNotNull { it.attr("data-url").trim().takeIf { s -> s.startsWith("http") } }
            .firstOrNull()
}
