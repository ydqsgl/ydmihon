package eu.kanade.tachiyomi.extension.zh.taiav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document

/**
 * Parsing for Taiav, kept free of networking so it can be tested against saved pages.
 *
 * The site is UIkit-based. Its player has no URL in the HTML: `Clappr` is initialised with a
 * variable that is filled by `GET /api/getmovie?type=<height>&id=<movieId>`, which answers with a
 * signed m3u8 path. The height decides which encode is offered, and only the page's own default
 * (`1280`) is available to anonymous visitors — other heights report "用户组未设置".
 */
internal object TaiavParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    data class Details(
        val title: String,
        val description: String,
        val tags: List<String>,
        val coverUrl: String?,
        val uploadDate: String?,
    )

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select(".movie-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=/movie/]") ?: return@mapNotNull null
            val href = link.attr("href").trim()
            if (!MOVIE_PATH_REGEX.containsMatchIn(href)) return@mapNotNull null

            // The card's img alt carries the full title; the visible caption is often truncated.
            val img = card.selectFirst("img[alt]")
            val title = img?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
                ?: card.selectFirst(".uk-card-title, h4, h3")?.text()?.trim()
            if (title.isNullOrBlank()) return@mapNotNull null

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = (img?.attr("src") ?: card.selectFirst("img[data-src]")?.attr("data-src"))
                    ?.trim()
                    ?.takeIf { it.startsWith("http") },
            )
        }.distinctBy { it.url }
    }

    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        // Search and tag pages paginate with `?page=N`; the forward link is the one after "current".
        val links = document.select("ul.uk-pagination a[href]")
        if (links.isEmpty()) {
            val next = document.selectFirst("a[rel=next][href]") ?: return null
            return absoluteUrl(next.attr("href").trim())
        }
        val current = links.indexOfFirst { it.parent()?.hasClass("uk-active") == true }
            .takeIf { it >= 0 } ?: links.indexOfFirst { it.text().trim().toIntOrNull() != null }
        val candidate = links.getOrNull(current + 1) ?: return null
        if (candidate.text().trim().toIntOrNull() == null) return null
        return absoluteUrl(candidate.attr("href").trim())
    }

    fun parseDetails(document: Document, absoluteUrl: (String) -> String): Details {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" 在线观看").trim()

        val description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val tags = document.select("a[href*=/tag/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        // The hero image is the poster the player would use.
        val cover = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".movie-poster img[src]")?.attr("src")?.trim()

        // The page stamps "本站于 YYYY 年收录", and the upload date appears in <time> markup.
        val uploadDate = document.selectFirst("time[datetime]")?.attr("datetime")
            ?: DATE_REGEX.find(document.text())?.value

        return Details(
            title = title,
            description = description,
            tags = tags,
            coverUrl = cover,
            uploadDate = uploadDate,
        )
    }

    /** The movie id used by `/api/getmovie`, taken from the canonical URL. */
    fun movieId(document: Document, chapterUrl: String): String? {
        document.selectFirst("link[rel=canonical]")?.attr("href")?.let { canonical ->
            MOVIE_ID_REGEX.find(canonical)?.let { return it.groupValues[1] }
        }
        return MOVIE_ID_REGEX.find(chapterUrl)?.groupValues?.get(1)
    }

    /**
     * Reads the m3u8 out of the `getmovie` answer.
     *
     * A successful answer is `{"m3u8":"/videos/.../index.m3u8?random=..&timestamp=..&key=.."}`; the
     * path is site-relative and must be resolved against the host. The `message` field is the site's
     * way of saying the encode is not available to this visitor.
     */
    fun parsePlayback(body: String, absoluteUrl: (String) -> String): PlaybackResult {
        val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return PlaybackResult.Unavailable("播放接口返回了非 JSON 内容")

        val path = json["m3u8"]?.jsonPrimitive?.content
        if (!path.isNullOrBlank()) return PlaybackResult.Success(absoluteUrl(path))

        val message = json["message"]?.jsonPrimitive?.content
        return PlaybackResult.Unavailable(message?.takeIf { it.isNotBlank() } ?: "该视频暂不可播放")
    }

    sealed interface PlaybackResult {
        data class Success(val url: String) : PlaybackResult
        data class Unavailable(val reason: String) : PlaybackResult
    }

    private val MOVIE_PATH_REGEX = Regex("""/movie/[a-f0-9]{16,}""")
    private val MOVIE_ID_REGEX = Regex("""/movie/([a-f0-9]{16,})""")
    private val DATE_REGEX = Regex("""(\d{4}-\d{2}-\d{2})""")
}
