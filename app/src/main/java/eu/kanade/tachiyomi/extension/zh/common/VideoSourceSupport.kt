package eu.kanade.tachiyomi.extension.zh.common

import android.net.Uri
import eu.kanade.tachiyomi.source.model.SManga
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Shared helpers for the adult-video sources in this bundle.
 *
 * The seven sites look very different on the surface (AppleCMS, KT, Rails, Astro, Next.js), but the
 * source contract is always the same: a listing of "titles", a details page and a playable URL. Only
 * the selectors differ, so the plumbing that has nothing to do with selectors lives here.
 */
internal object VideoSourceSupport {

    /** Every site is Chinese-language video content and reports dates in Beijing time. */
    val SITE_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** Desktop UA for sites whose mobile layout hides the player or the listing. */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    private val FULL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val SLASH_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT)

    /**
     * Parses the date formats these sites actually print.
     *
     * Accepts ISO-ish timestamps (`2026-08-26T10:11:58`), plain dates in either separator style, and
     * the "3月前" / "5天前" relative labels the Rails sites use. Unknown input yields 0, which Mihon
     * treats as "no date" rather than inventing one.
     */
    fun parseDate(raw: String?, now: Long = System.currentTimeMillis()): Long {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return 0L

        // ISO timestamps and plain dates.
        val datePart = text.substringBefore('T').substringBefore(' ')
        runCatching {
            LocalDate.parse(datePart, if (datePart.contains('/')) SLASH_DATE else FULL_DATE)
                .atStartOfDay(SITE_ZONE)
                .toEpochSecond() * 1000
        }.onSuccess { return it }

        // Relative labels: 昨天 / 3天前 / 2周前 / 5个月前 / 1年前.
        RELATIVE_REGEX.find(text)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return@let
            val unitSeconds = when (match.groupValues[2]) {
                "分", "分钟" -> 60L
                "小时" -> 3_600L
                "天", "日" -> 86_400L
                "周", "星期" -> 604_800L
                "月" -> 2_592_000L
                "年" -> 31_536_000L
                else -> return@let
            }
            return (now / 1000 - amount * unitSeconds) * 1000
        }
        if (text.contains("昨天")) return ((now / 1000) - 86_400L) * 1000
        if (text.contains("前天")) return ((now / 1000) - 172_800L) * 1000

        return 0L
    }

    /**
     * Turns a relative href into an absolute URL against [baseUrl].
     *
     * Protocol-relative and root-relative links are common on these sites, and some selectors return
     * the raw attribute rather than jsoup's resolved `absUrl`.
     */
    fun resolveUrl(baseUrl: String, href: String): String {
        val raw = href.trim()
        if (raw.isEmpty()) return ""
        return when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> baseUrl.trimEnd('/') + raw
            else -> "${baseUrl.trimEnd('/')}/$raw"
        }
    }

    /**
     * Unwraps a whole-document Base64 stub.
     *
     * Several of these sites answer an entrance URL with
     * `document.write(Base64.decode('...'))`, which jsoup would otherwise parse as an empty body.
     * Only the wrapper is decoded; ordinary pages are returned unchanged.
     */
    fun decodeWrappedPage(html: String): String {
        if (!html.contains("document.write")) return html
        val match = BASE64_WRITE_REGEX.find(html) ?: return html
        return runCatching {
            Base64.getDecoder().decode(match.groupValues[1].trim()).toString(Charsets.UTF_8)
        }.getOrDefault(html)
    }

    private val BASE64_WRITE_REGEX = Regex(
        """document\.write\s*\(\s*Base64\.decode\s*\(\s*['"]([A-Za-z0-9+/=\s]+)['"]\s*\)\s*\)""",
    )

    /**
     * Decodes an entity that the site encoded twice.
     *
     * 91porn prints titles as `&amp;#039;`, which jsoup decodes once to the literal `&#039;`.
     * Showing that to the user is worse than one more pass, so numeric and the handful of named
     * entities these sites use are decoded here.
     */
    fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var result = text
        // Numeric entities: &#39; / &#x27;
        result = NUMERIC_ENTITY.replace(result) { match ->
            val value = match.groupValues[1]
            val code = if (value.startsWith("x", ignoreCase = true)) {
                value.drop(1).toIntOrNull(16)
            } else {
                value.toIntOrNull()
            }
            code?.takeIf { it in 0x20..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
        }
        // The few named entities that survive jsoup on these pages.
        NAMED_ENTITIES.forEach { (entity, char) -> result = result.replace(entity, char) }
        return result
    }

    private val NUMERIC_ENTITY = Regex("""&#(x?[0-9A-Fa-f]+);""")

    private val NAMED_ENTITIES = listOf(
        "&quot;" to "\"",
        "&apos;" to "'",
        "&nbsp;" to " ",
        "&lt;" to "<",
        "&gt;" to ">",
        "&amp;" to "&",
    )

    /** Strips the scheme and host so the URL can be stored relative to the source's base URL. */
    fun withoutDomain(url: String): String {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val path = parsed.encodedPath ?: return url
        val query = parsed.encodedQuery
        return buildString {
            append(path)
            if (!query.isNullOrEmpty()) append('?').append(query)
        }
    }

    /**
     * Builds the single "video" chapter every source in this bundle exposes.
     *
     * These sites publish one clip per page (or one episode per page), so there is no meaningful
     * chapter tree; a single entry keeps the reader's chapter switcher out of the way.
     */
    fun singleChapter(
        mangaUrl: String,
        name: String,
        dateUpload: Long = 0L,
    ): eu.kanade.tachiyomi.source.model.SChapter =
        eu.kanade.tachiyomi.source.model.SChapter.create().apply {
            url = mangaUrl
            this.name = name
            this.date_upload = dateUpload
        }

    /** A completed, single-video entry. */
    fun videoEntry(
        url: String,
        title: String,
        thumbnailUrl: String?,
        description: String = "",
        genre: String? = null,
        dateUpload: Long = 0L,
    ): SManga = SManga.create().apply {
        this.url = url
        this.title = title
        this.thumbnail_url = thumbnailUrl
        this.description = description
        this.genre = genre
        this.status = SManga.COMPLETED
        this.initialized = true
        this.update_strategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ONLY_FETCH_ONCE
    }

    private val RELATIVE_REGEX = Regex("""(\d+)\s*(分钟|分|小时|天|日|周|星期|月|年)前""")
}
