package eu.kanade.tachiyomi.extension.zh.rou

import org.jsoup.nodes.Document
import java.util.Base64

/**
 * Parsing for 肉視頻, kept free of networking so it can be tested against saved pages.
 *
 * The site is a Next.js app-router build, so the markup is the output of React server components:
 * predictable class names for the cards, and a serialised RSC payload for data that is not visible
 * in the DOM — including the stream URL.
 */
internal object RouParser {

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
        return document.select("a[href^=/v/]").mapNotNull { anchor ->
            val href = anchor.attr("href").trim()
            if (!VIDEO_PATH_REGEX.matches(href)) return@mapNotNull null
            val title = anchor.selectFirst("h3")?.text()?.trim()
            if (title.isNullOrBlank()) return@mapNotNull null

            // The first `img` is a blurred backdrop of the same artwork; either is a valid cover.
            val thumb = anchor.selectFirst("img[src]")?.attr("src")?.trim()

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = thumb?.takeIf { it.isNotEmpty() }?.let(absoluteUrl),
            )
        }.distinctBy { it.url }
    }

    /**
     * The pager marks its forward link with `rel="next"`.
     *
     * Text matching would be fragile here: the same nav also renders the last page number, which is
     * not a forward step.
     */
    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val next = document.selectFirst("a[rel=next][href]") ?: return null
        val href = next.attr("href").trim()
        if (href.isEmpty()) return null
        return absoluteUrl(href)
    }

    fun parseDetails(document: Document): Details {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ").orEmpty()

        val description = document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()

        // The poster attribute is the real artwork; og:image points at the same file with a CDN
        // transform applied, so prefer the player's own URL.
        val cover = document.selectFirst("video[poster]")?.attr("poster")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = document.select("a[href^=/t/]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val uploadDate = document.selectFirst("script[type=application/ld+json]")
            ?.data()
            ?.let { UPLOAD_DATE_REGEX.find(it)?.groupValues?.get(1) }

        return Details(
            title = title,
            description = description,
            tags = tags,
            coverUrl = cover,
            uploadDate = uploadDate,
        )
    }

    /**
     * Extracts the stream URL from the RSC payload.
     *
     * The page serialises `ev:{d:"<base64>",k:<int>}`, where `d` decodes to JSON whose characters
     * were each shifted up by `k`. Subtracting `k` from every byte recovers
     * `{"videoUrl":"...","thumbVTTUrl":"..."}`.
     *
     * Returns null when the payload is absent (e.g. the clip was removed), so the caller can raise
     * a proper "unavailable" error instead of emitting a bogus URL.
     */
    fun parseVideoUrl(document: Document): String? {
        val html = document.outerHtml()
        val match = PLAYBACK_REGEX.find(html) ?: return null
        val json = decodePayload(match.groupValues[1], match.groupValues[2].toIntOrNull() ?: return null)
            ?: return null
        return VIDEO_URL_REGEX.find(json)?.groupValues?.get(1)
    }

    /** Base64 → per-byte shift → plain JSON text. Null when the payload is not what it claims to be. */
    fun decodePayload(encoded: String, shift: Int): String? {
        val normalized = encoded.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull() ?: return null

        val builder = StringBuilder(bytes.size)
        for (byte in bytes) {
            val shifted = (byte.toInt() and 0xFF) - shift
            // A negative result means the shift is wrong; refuse rather than emit mojibake.
            if (shifted < 0) return null
            builder.append(shifted.toChar())
        }
        return builder.toString().takeIf { it.startsWith("{") }
    }

    private val VIDEO_PATH_REGEX = Regex("""^/v/[A-Za-z0-9]+$""")

    /**
     * `$R[NN]` is a live React server-component slot reference, hence the character class around
     * `R`: a plain `$R` would be read as a Kotlin string template.
     */
    private val PLAYBACK_REGEX = Regex("""ev:\$[R]\[\d+\]=\{d:"([^"]+)",k:(\d+)\}""")
    private val VIDEO_URL_REGEX = Regex(""""videoUrl"\s*:\s*"([^"]+)"""")
    private val UPLOAD_DATE_REGEX = Regex(""""uploadDate"\s*:\s*"([^"]+)"""")
}
