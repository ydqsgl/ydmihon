package eu.kanade.tachiyomi.extension.zh.hsck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

/**
 * Parsing for 黄色仓库, kept free of networking so it can be tested against saved pages.
 *
 * The site is AppleCMS on the `stui` theme, so the card markup is shared with thousands of other
 * MacCMS skins — but this particular installation interleaves paid placements (links to foreign
 * hosts) into every listing, which is why the href has to match the site's own `/v5/` shape.
 */
internal object HsckParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    data class Details(
        val title: String,
        val description: String,
        val keywords: List<String>,
        val dateText: String?,
        val coverUrl: String?,
    )

    /** The play parameters embedded in the anti-bot countdown script. */
    data class PlayToken(
        val id: String,
        val sid: String,
        val nid: String,
        val token: String,
    )

    fun isListing(html: String): Boolean =
        html.contains("stui-vodlist") && html.contains("/v5/")

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("li").mapNotNull { li ->
            val link = li.selectFirst("a.stui-vodlist__thumb[href]") ?: return@mapNotNull null
            val href = link.attr("href").trim()
            if (!SITE_ITEM_REGEX.matches(href)) return@mapNotNull null

            val title = li.selectFirst(".stui-vodlist__detail .title a")?.text()
                ?: li.selectFirst(".stui-vodlist__detail .title")?.text()
            if (title.isNullOrBlank()) return@mapNotNull null

            ListingItem(
                url = absoluteUrl(href),
                title = title.trim(),
                thumbnailUrl = lazyImage(li.selectFirst("a.stui-vodlist__thumb"))?.let(absoluteUrl),
            )
        }.distinctBy { it.url }
    }

    /**
     * AppleCMS lazy-loads covers in `data-original`; `src` holds a 1px placeholder and the inline
     * `background-image` repeats the lazy URL for the fade-in, so only `data-original` is usable.
     */
    fun lazyImage(element: Element?): String? {
        if (element == null) return null
        val raw = element.attr("data-original").ifBlank { element.attr("data-src") }
        return raw.trim().takeIf { it.isNotEmpty() }
    }

    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val next = document.select("ul.stui-page li a")
            .firstOrNull { it.text().contains("下一页") } ?: return null
        val href = next.attr("href").trim()
        if (href.isEmpty() || href.startsWith("javascript")) return null
        return absoluteUrl(href)
    }

    fun parseDetails(document: Document, absoluteUrl: (String) -> String): Details {
        // The first `h3.title` on every page is the site's "目录" navigation header, so the clip
        // title is the first heading that is not one of the fixed menu labels.
        val title = document.select(".stui-pannel__head h3.title")
            .map { it.text().trim() }
            .firstOrNull { it.isNotEmpty() && it !in MENU_HEADINGS }
            ?: document.title().substringBefore(" - ").trim()

        val description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
        val keywords = document.selectFirst("meta[name=keywords]")?.attr("content").orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("黄色仓库") }

        val dateText = document.selectFirst(".stui-player__foot")?.text()
            ?.let { DATE_REGEX.find(it)?.groupValues?.get(1)?.trim() }

        // The details page has no dedicated poster; the "related" strip carries real covers.
        val cover = document.select("ul.stui-vodlist li a.stui-vodlist__thumb[data-original]")
            .mapNotNull { lazyImage(it) }
            .map(absoluteUrl)
            .firstOrNull { it.contains("/images/") || it.contains("/upload") }

        return Details(
            title = title,
            description = description,
            keywords = keywords,
            dateText = dateText,
            coverUrl = cover,
        )
    }

    /**
     * Extracts `AID` / `ASID` / `ANID` / `AK` from the inline countdown script.
     *
     * Those four values are everything `POST /static/count.php` needs; the rest of the script is
     * anti-bot theatre (a countdown and a gesture check).
     */
    fun parsePlayToken(document: Document): PlayToken? {
        // The endpoint is written as `['st','atic','/','count','.php'].join('')` specifically to
        // defeat substring checks, so the script is identified by its token variable instead.
        val script = document.select("script")
            .map { it.data() }
            .firstOrNull { it.contains("AK=") && it.contains("mountPlayer") } ?: return null

        val id = ID_REGEX.find(script)?.groupValues?.get(1) ?: return null
        val token = TOKEN_REGEX.find(script)?.groupValues?.get(1) ?: return null

        return PlayToken(
            id = id,
            sid = SID_REGEX.find(script)?.groupValues?.get(1) ?: "1",
            nid = NID_REGEX.find(script)?.groupValues?.get(1) ?: "1",
            token = token,
        )
    }

    /**
     * Reads the Base64 m3u8 out of the `count.php` JSON answer.
     *
     * The response is `{"ok":true,"u":"<base64>"}`; `u` is the full URL, not a path.
     */
    fun parsePlayResponse(body: String): String? {
        val encoded = BASE64_FIELD_REGEX.find(body)?.groupValues?.get(1) ?: return null
        val decoded = runCatching {
            Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
        }.getOrNull() ?: return null
        return decoded.takeIf { it.startsWith("http") && it.contains(".m3u8") }
    }

    /** The error text the endpoint returns when it refuses, if any. */
    fun errorMessage(body: String): String? = runCatching {
        Json.parseToJsonElement(body).jsonObject
    }.getOrNull()
        ?.get("err")
        ?.jsonPrimitive
        ?.content
        ?.takeIf { it.isNotBlank() }

    private val MENU_HEADINGS = setOf("目录", "精选内容", "猜你喜欢", "相关推荐")

    private val SITE_ITEM_REGEX = Regex("""^/v5/\d+-\d+-\d+\.html$""")
    private val ID_REGEX = Regex("""AID\s*=\s*['"]?(\d+)""")
    private val SID_REGEX = Regex("""ASID\s*=\s*['"]?(\d+)""")
    private val NID_REGEX = Regex("""ANID\s*=\s*['"]?(\d+)""")
    private val TOKEN_REGEX = Regex("""AK\s*=\s*['"]([a-f0-9]{16,})['"]""")
    private val BASE64_FIELD_REGEX = Regex(""""u"\s*:\s*"([A-Za-z0-9+/=]+)"""")
    private val DATE_REGEX = Regex("""时间：\s*([0-9\-: ]+)""")
}
