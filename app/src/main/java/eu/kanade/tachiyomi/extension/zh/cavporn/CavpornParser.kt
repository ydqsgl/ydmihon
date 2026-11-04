package eu.kanade.tachiyomi.extension.zh.cavporn

import org.jsoup.nodes.Document

/**
 * Parsing for CavPorn, kept free of networking so it can be tested against saved pages.
 *
 * The site runs Kernel Video Sharing (KT), so the markup is the familiar `.list-videos .item` card
 * with a lazy-loaded `data-original` thumbnail and a `Playerjs({ file: "..." })` player.
 */
internal object CavpornParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
        val duration: String?,
    )

    data class Details(
        val title: String,
        val description: String,
        val categories: List<String>,
        val tags: List<String>,
        val duration: String?,
        val coverUrl: String?,
    )

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select(".list-videos .item, .item").mapNotNull { item ->
            val anchor = item.selectFirst("a[href*=/video/]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (!VIDEO_PATH_REGEX.containsMatchIn(href)) return@mapNotNull null

            val title = item.selectFirst("strong.title")?.text()?.trim()
                ?: anchor.attr("title").trim()
            if (title.isNullOrBlank()) return@mapNotNull null

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = thumb(anchor)?.let(absoluteUrl),
                duration = item.selectFirst(".duration")?.text()?.trim(),
            )
        }.distinctBy { it.url }
    }

    /**
     * KT lazy-loads covers: `src` is a 1x1 GIF and the real URL is in `data-original` (WebP) or
     * `data-webp`. The WebP variant is preferred when present because it is smaller.
     */
    private fun thumb(anchor: org.jsoup.nodes.Element): String? {
        val img = anchor.selectFirst("img") ?: return null
        val raw = img.attr("data-webp").ifBlank { img.attr("data-original") }
        return raw.trim().takeIf { it.isNotEmpty() && !it.startsWith("data:") }
    }

    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        // KT renders the pager as `.pagination` with the forward link labelled 下一页/Next.
        val next = document.select(".pagination a, .pager a").firstOrNull {
            val text = it.text().trim()
            text == "下一页" || text == "Next" || text == "»"
        } ?: return null
        val href = next.absUrl("href").ifBlank { next.attr("href") }.trim()
        if (href.isEmpty()) return null
        return absoluteUrl(href)
    }

    fun parseDetails(document: Document, absoluteUrl: (String) -> String): Details {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" - ").trim()

        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?: document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()

        val categories = document.select("a[href*=/categories/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it != "分类" }
            .distinct()

        val tags = document.select("a[href*=/tags/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && it != "标签" }
            .distinct()

        val duration = document.selectFirst(".duration")?.text()?.trim()
        val cover = document.selectFirst("video[poster]")?.attr("poster")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        return Details(
            title = title,
            description = description,
            categories = categories,
            tags = tags,
            duration = duration,
            coverUrl = cover,
        )
    }

    /** Outcome of looking for a playable URL on a clip page. */
    sealed interface Playback {
        data class Success(val url: String) : Playback

        /** The player is replaced by a "登录 / 注册" panel; only some clips are public. */
        data object LoginRequired : Playback

        /** No player and no login panel: the clip was pulled. */
        data object Missing : Playback
    }

    /**
     * Reads the m3u8 out of the inline Playerjs config.
     *
     * KT embeds `new Playerjs({ ..., file:"https://.../index.m3u8?md5=...&expires=..." })`. The URL
     * is signed but long-lived (the expiry is weeks out), so it can be handed to the player as-is.
     *
     * Most clips on this site are members-only: the player block is replaced by `.no-player` with a
     * register prompt. Reporting that as "login required" is far more useful than a generic parse
     * failure, because retrying will never help.
     */
    fun parsePlayback(document: Document): Playback {
        val html = document.outerHtml()
        FILE_FIELD_REGEX.find(html)?.let { match ->
            val url = match.groupValues[1].replace("\\/", "/").trim()
            if (url.startsWith("http")) return Playback.Success(url)
        }
        // Some KT skins put the URL in a <source> tag instead.
        document.selectFirst("video source[src], source[src]")?.let { source ->
            val src = source.attr("src").trim()
            if (src.startsWith("http")) return Playback.Success(src)
        }

        if (document.selectFirst(".no-player") != null) return Playback.LoginRequired
        return Playback.Missing
    }

    /** Convenience for callers that only need the URL. */
    fun parseVideoUrl(document: Document): String? =
        (parsePlayback(document) as? Playback.Success)?.url

    private val VIDEO_PATH_REGEX = Regex("""/video/\d+/""")
    private val FILE_FIELD_REGEX = Regex("""file\s*:\s*["']([^"']+)["']""")
}
