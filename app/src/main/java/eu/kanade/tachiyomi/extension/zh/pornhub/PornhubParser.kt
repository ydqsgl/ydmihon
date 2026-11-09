package eu.kanade.tachiyomi.extension.zh.pornhub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parsing for Pornhub, kept free of networking so it can be tested against saved pages.
 *
 * Pornhub renders its catalogue server-side: the raw HTML already contains every card and the
 * player's `flashvars` object, so no JavaScript execution is needed. The pieces we read:
 *
 * - **Listing cards** live in `li.videoBox` (also `.pcVideoListItem`), each with an
 *   `a[href*="view_video.php"][title]` link and an `img[src]` thumbnail. The title attribute is the
 *   most stable title source; the thumbnail is a signed CDN URL that needs no Referer.
 * - **Details** come from the same page: `h1.title` for the title, `meta[property=og:image]` for
 *   the cover, and the `var flashvars_<id> = {...};` script for everything else.
 * - **Playback** picks the highest-quality HLS definition from `flashvars.mediaDefinitions`. The
 *   URL is a signed `master.m3u8` that expires after ~2h; re-reading the details page yields a
 *   fresh one, so the source never stores it across sessions.
 */
internal object PornhubParser {

    /** A card on a listing page. */
    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    /** Metadata of a details page. */
    data class Details(
        val title: String,
        val description: String = "",
        val genre: String? = null,
        val coverUrl: String? = null,
        val dateUpload: Long = 0L,
        val m3u8Url: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses listing cards.
     *
     * Pornhub's browse page marks every card with `data-video-vkey` on the outer `li`, and its own
     * script config (`videoBoxSelector: '.videoWrapper'`) names the inner block, so those two are
     * the dependable containers. The card holds **two** `view_video.php` anchors — the thumbnail
     * link (`a.imageLink`, no text) and the title link inside `div.title` — which is why reading
     * the title off the first anchor found is wrong: it is empty and the card gets dropped.
     *
     * The title is therefore looked up on the card, in order of reliability: the `div.title`
     * link's text, the anchor's `title` attribute (older layouts), and finally the thumbnail's
     * `alt`, which Pornhub always fills with the video title.
     */
    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        val containers = document.select("li[data-video-vkey]")
            .ifEmpty { document.select("div.videoWrapper") }
            .ifEmpty { document.select("a[href*=\"view_video.php\"]") }

        return containers
            .mapNotNull { parseCard(it, absoluteUrl) }
            // Pornhub repeats cards (one per quality/preview row); keep the first of each URL.
            .distinctBy { it.url }
    }

    /** Reads one card, or null when it carries no link or no usable title. */
    private fun parseCard(card: Element, absoluteUrl: (String) -> String): ListingItem? {
        val anchor = if (card.tagName() == "a") {
            card.takeIf { it.attr("href").contains("view_video.php") }
        } else {
            card.selectFirst("a[href*=\"view_video.php\"]")
        } ?: return null

        val href = anchor.attr("href").trim()
        if (href.isEmpty()) return null

        val title = sequenceOf(
            card.selectFirst("div.title a[href*=\"view_video.php\"], .title a, .title")?.text(),
            anchor.attr("title"),
            card.selectFirst("img[alt]")?.attr("alt"),
            anchor.text(),
        ).map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null

        val thumbnail = (card.selectFirst("img.videoThumb[src]") ?: card.selectFirst("img[src]"))
            ?.attr("src")?.trim()?.takeIf { it.isNotBlank() }

        return ListingItem(
            url = absoluteUrl(href),
            title = title,
            thumbnailUrl = thumbnail,
        )
    }

    /**
     * Finds the "next page" link in a listing document.
     *
     * The browse pager renders numbered `?page=N` links plus a trailing empty link that jumps to
     * the next page. We follow the lowest page number greater than the current one, or the link
     * marked as next when present.
     */
    fun findNextPageUrl(document: Document, currentPage: Int, absoluteUrl: (String) -> String): String? {
        // A rel=next link wins when the site supplies one.
        document.selectFirst("a[rel=\"next\"][href]")?.let { next ->
            val href = next.attr("href").trim()
            if (href.isNotEmpty()) return absoluteUrl(href)
        }

        val pageAnchors = document.select("a[href*=\"page=\"]")
        var best: String? = null
        var bestPage = Int.MAX_VALUE
        for (anchor in pageAnchors) {
            val href = anchor.attr("href")
            val page = PAGE_REGEX.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (page > currentPage && page < bestPage) {
                bestPage = page
                best = href
            }
        }
        return best?.let(absoluteUrl)
    }

    /**
     * Parses the details page.
     *
     * The flashvars script carries the authoritative title, cover, upload date and media
     * definitions. When it is missing (an age gate or a removed video), we fall back to the DOM.
     */
    fun parseDetails(document: Document, absoluteUrl: (String) -> String): Details {
        val flashvars = extractFlashvars(document)

        val title = flashvars.string("video_title")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1.title, h1")?.text()?.trim().orEmpty()

        val cover = flashvars?.string("image_url")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }

        val description = document.selectFirst("meta[name=\"description\"]")?.attr("content").orEmpty()

        val genre = flashvars?.string("actionTags")?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.mapNotNull { it.substringBefore(':').trim().ifEmpty { null } }
            ?.distinct()
            ?.joinToString(", ")
            ?.takeIf { it.isNotBlank() }

        val dateUpload = flashvars?.string("uploadDate")
            ?.let(::parseUploadDate)?.takeIf { it > 0L }
            ?: jsonLdUploadDate(document)

        val m3u8Url = flashvars?.let(::bestHlsUrl)

        return Details(
            title = title,
            description = description,
            genre = genre,
            coverUrl = cover,
            dateUpload = dateUpload,
            m3u8Url = m3u8Url,
        )
    }

    /**
     * Returns the highest-quality HLS playlist URL from the media definitions.
     *
     * Pornhub lists a few HLS qualities and one MP4 fallback. The MP4 entry is served through a
     * `get_media?s=<token>` endpoint that can be slow and is often unavailable outside the player,
     * so we prefer the top HLS master. The signed URL expires; callers must re-read the page to
     * refresh it rather than caching it.
     */
    fun bestHlsUrl(flashvars: JsonObject): String? {
        val element = flashvars["mediaDefinitions"] ?: return null
        val definitions = element.jsonArrayOrNull() ?: return null
        var best: JsonObject? = null
        var bestHeight = -1
        for (defElement in definitions) {
            val def = defElement.jsonObjectOrNull() ?: continue
            if (def.string("format") != "hls") continue
            val url = def.string("videoUrl")?.trim().orEmpty()
            if (url.isEmpty()) continue
            val height = def.int("height") ?: 0
            if (height > bestHeight) {
                bestHeight = height
                best = def
            }
        }
        return best?.string("videoUrl")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Extracts the `var flashvars_<id> = {...};` object from the page.
     *
     * The object sits in a `<script>` block and is a well-formed JSON literal. jsoup keeps the
     * script text verbatim, so we locate the marker and read a brace-balanced object. Balancing
     * matters: the JSON contains nested objects and strings with braces inside.
     */
    fun extractFlashvars(document: Document): JsonObject? {
        for (script in document.select("script")) {
            val text = script.data()
            val marker = FLASHVARS_MARKER.find(text) ?: continue
            val start = text.indexOf('{', marker.range.last + 1)
            if (start < 0) continue
            val end = findMatchingBrace(text, start) ?: continue
            val payload = text.substring(start, end + 1)
            return runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
        }
        return null
    }

    // ============================== Helpers ==============================

    /**
     * Returns the index of the brace matching the opening brace at [start], or null if unbalanced.
     *
     * Tracks string literals and escapes so a `{` or `}` inside a string does not miscount.
     */
    private fun findMatchingBrace(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                    else -> {}
                }
            }
            i++
        }
        return null
    }

    /** Parses Pornhub's ISO timestamp (`2023-08-10T08:55:28+00:00`) to epoch millis. */
    private fun parseUploadDate(raw: String): Long = runCatching {
        java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    /**
     * Reads `uploadDate` out of the page's JSON-LD block.
     *
     * `flashvars` no longer carries a date, but the schema.org block next to it does, and that is
     * the only place the publish time still appears in the server-rendered HTML.
     */
    private fun jsonLdUploadDate(document: Document): Long {
        for (script in document.select("script[type=\"application/ld+json\"]")) {
            val element = runCatching { json.parseToJsonElement(script.data()) }.getOrNull() ?: continue
            val objects = if (element is JsonArray) element.toList() else listOf(element)
            for (candidate in objects) {
                val raw = (candidate as? JsonObject)?.string("uploadDate") ?: continue
                val parsed = parseUploadDate(raw)
                if (parsed > 0L) return parsed
            }
        }
        return 0L
    }

    // ============================== Json helpers ==============================

    private fun JsonObject?.string(key: String): String? =
        this?.get(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject?.int(key: String): Int? =
        this?.get(key)?.jsonPrimitive?.intOrNull

    private fun JsonObject?.json(key: String): JsonObject? =
        this?.get(key)?.jsonObjectOrNull()

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        if (this is JsonObject) this else null

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): JsonArray? =
        if (this is JsonArray) this else null

    private val PAGE_REGEX = Regex("""[?&]page=(\d+)""")
    private val FLASHVARS_MARKER = Regex("""var\s+flashvars_\d+\s*=\s*""")
}
