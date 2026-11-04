package eu.kanade.tachiyomi.extension.zh.mrds

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64

/**
 * Pure HTML parsing for the 每日大赛 layout, kept free of Android and networking so it can be
 * unit-tested against saved pages.
 *
 * The theme is the same Mirages/Typecho theme as 51吃瓜, but it lazy-loads media differently:
 * covers come from a `loadBannerDirect(...)` inline script and article images from a
 * `data-xkrkllgl` attribute whose `src` is a placeholder.
 */
internal object MrdsParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    data class ArticleDetails(
        val title: String,
        val description: String,
        val tags: List<String>,
        val dateUpload: Long,
        val videoUrls: List<String>,
        val imageUrls: List<String>,
    )

    /**
     * The entrance pages answer with a whole HTML document wrapped in
     * `document.write(Base64.decode('...'))`, which jsoup would otherwise see as an empty body.
     * Decodes it once when that wrapper is present.
     */
    fun decodeIfWrapped(html: String): String {
        if (!html.contains("document.write")) return html
        val match = BASE64_WRITE_REGEX.find(html) ?: return html
        return runCatching {
            Base64.getDecoder().decode(match.groupValues[1]).toString(Charsets.UTF_8)
        }.getOrDefault(html)
    }

    /** True when the document is a working listing page rather than an entrance stub. */
    fun isListing(document: Document): Boolean = document.selectFirst("article .post-card-title") != null

    /**
     * Reads every article card of a listing page, in document order.
     * Cards without a title are advertisement slots and are skipped.
     */
    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("article").mapNotNull { article ->
            val link = article.selectFirst("a[href*=\"/archives/\"]") ?: return@mapNotNull null
            val rawTitle = article.selectFirst(".post-card-title")
                ?.text()
                ?.replace("热搜HOT", "")
                ?.replace("热搜 HOT", "")
                ?.replace("热搜", "")
                ?.trim()
            if (rawTitle.isNullOrBlank()) return@mapNotNull null

            val href = absoluteUrl(link.absUrl("href").ifBlank { link.attr("href") })
            if (href.isBlank()) return@mapNotNull null

            ListingItem(
                url = href,
                title = rawTitle,
                thumbnailUrl = extractThumbnail(article),
            )
        }.distinctBy { it.url }
    }

    /** Reads the "next page" link of the theme's pagination widget, or null on the last page. */
    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val anchor = document.selectFirst(".page-navigator li.next a[href]")
            ?: document.selectFirst(".page-navigator a.next[href]")
            ?: return null
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        if (href.isBlank()) return null
        return absoluteUrl(href)
    }

    /**
     * Covers are injected by an inline script rather than a loader attribute:
     * `loadBannerDirect('https://pic.../x.jpeg', '', document.querySelector('#post-card-1'), ...)`.
     */
    private fun extractThumbnail(article: Element): String? {
        BANNER_CALL_REGEX.find(article.html())?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        article.selectFirst(".blog-background")?.let { bg ->
            bg.attr("z-bg-loader-url")
                .ifBlank { bg.attr("x-bg-loader-url") }
                .ifBlank { bg.attr("data-bg") }
                .takeIf { it.isNotBlank() }
                ?.let { return it }

            val style = bg.attr("style")
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.let { return it.groupValues[1] }
        }

        return null
    }

    /**
     * Reads an article page: title, summary, tags, date and every media URL.
     */
    fun parseArticle(
        document: Document,
        fallbackTitle: String,
        normalizeUrl: (String) -> String,
        dateParser: (String?) -> Long,
    ): ArticleDetails {
        val content = document.selectFirst(".post-content")

        val h1 = document.selectFirst("h1.post-title, article h1, h1")?.text()?.trim()
        val title = when {
            !h1.isNullOrBlank() -> h1
            else -> document.title().substringBefore(" - ").ifBlank { fallbackTitle }
        }

        val videoUrls = extractVideoUrls(document, normalizeUrl)
        val imageUrls = extractImageUrls(document) { normalizeUrl(it) }

        val summary = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val paragraphs = content?.select("p")
            ?.map { it.text().trim() }
            ?.filter { it.length > 15 }
            ?.filterNot { isBoilerplate(it) }
            .orEmpty()

        val body = when {
            !summary.isNullOrBlank() -> summary
            paragraphs.isNotEmpty() -> paragraphs.take(4).joinToString("\n")
            else -> title
        }

        val header = buildString {
            if (videoUrls.isNotEmpty()) {
                append("共 ${videoUrls.size} 个视频")
                if (imageUrls.isNotEmpty()) append("，${imageUrls.size} 张图片")
                append("\n\n")
            }
        }

        val tags = document.select(".tags a, .post-tags a, a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val dateEl = document.selectFirst("meta[itemprop=datePublished], meta[itemprop=dateModified]")

        return ArticleDetails(
            title = title,
            description = (header + body).trim().ifBlank { title },
            tags = tags,
            dateUpload = dateParser(dateEl?.attr("content")),
            videoUrls = videoUrls,
            imageUrls = imageUrls,
        )
    }

    /**
     * Collects every playable video URL, in document order.
     *
     * DPlayer is rendered as `data-config='{"video":{"url":"https:\/\/...\/x.m3u8"}}'`; the
     * escaped slashes are why a naive regex only captures the scheme.
     */
    fun extractVideoUrls(document: Document, normalizeUrl: (String) -> String): List<String> {
        val content = document.selectFirst(".post-content") ?: return emptyList()
        val urls = LinkedHashSet<String>()

        content.select(".dplayer, [data-config]").forEach { el ->
            val config = el.attr("data-config").ifBlank { el.attr("data-setup") }
            if (config.isNotBlank()) {
                extractUrlFromConfig(config)?.let { urls.add(it) }
            }
        }

        content.select("video[src], video source[src], source[src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }
            if (src.isNotBlank()) urls.add(src)
        }

        // Only when nothing structured was found, so ad banners are not picked up.
        if (urls.isEmpty()) {
            MEDIA_URL_REGEX.findAll(content.html()).forEach { match ->
                urls.add(match.groupValues[1])
            }
        }

        return urls.mapNotNull { normalizeMediaUrl(it, normalizeUrl) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * Collects the gallery images. This theme keeps the real file in `data-xkrkllgl` and puts a
     * placeholder (`/usr/plugins/tbxw/zw.png`) in `src`.
     */
    fun extractImageUrls(document: Document, normalizeUrl: (String) -> String): List<String> {
        val content = document.selectFirst(".post-content") ?: return emptyList()
        return content.select("img").mapNotNull { img ->
            val raw = img.absUrl("data-xkrkllgl").ifBlank {
                img.absUrl("z-image-loader-url").ifBlank {
                    img.absUrl("x-image-loader-url").ifBlank {
                        img.absUrl("data-src").ifBlank {
                            img.absUrl("data-original").ifBlank { img.absUrl("src") }
                        }
                    }
                }
            }
            if (raw.isBlank()) return@mapNotNull null

            when {
                isAdImage(raw) -> null
                isPlaceholder(raw) -> null
                raw.contains("gravatar") || raw.contains("avatar") -> null
                img.hasAttr("no-zoom") -> null
                else -> normalizeUrl(raw).takeIf { it.isNotBlank() }
            }
        }.distinct()
    }

    private fun extractUrlFromConfig(rawConfig: String): String? {
        val config = rawConfig
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
            .trim()

        runCatching {
            val element = jsonParser.parseToJsonElement(config)
            findUrlInJson(element)
        }.getOrNull()?.let { return unescapeSlashes(it) }

        CONFIG_URL_REGEX.find(config)?.let { match ->
            return unescapeSlashes(match.groupValues[1])
        }

        return null
    }

    private fun findUrlInJson(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                (element["video"] as? JsonObject)?.let { video ->
                    (video["url"] as? JsonPrimitive)
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let { return it }
                }
                (element["url"] as? JsonPrimitive)?.contentOrNull?.let { return it }
                for ((_, value) in element) {
                    findUrlInJson(value)?.let { return it }
                }
            }
            is JsonArray -> {
                for (value in element) {
                    findUrlInJson(value)?.let { return it }
                }
            }
            is JsonPrimitive -> {
                val text = element.contentOrNull ?: return null
                if (MEDIA_URL_HINT.containsMatchIn(text)) return text
            }
        }
        return null
    }

    private fun normalizeMediaUrl(rawUrl: String, normalizeUrl: (String) -> String): String? {
        val url = unescapeSlashes(rawUrl)
        if (url.isBlank()) return null

        val absolute = when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> normalizeUrl(url)
            else -> return null
        }

        val parsed = runCatching { absolute.toHttpUrl() }.getOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        if (!parsed.encodedPath.contains(".") && !MEDIA_URL_HINT.containsMatchIn(absolute)) return null

        return normalizeUrl(absolute)
    }

    fun unescapeSlashes(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .trim()

    fun isBoilerplate(text: String): Boolean = BOILERPLATE_MARKERS.any { text.contains(it) }

    fun isAdImage(url: String): Boolean {
        val lower = url.lowercase()
        return AD_HOST_MARKERS.any { lower.contains(it) } || AD_PATH_MARKERS.any { lower.contains(it) }
    }

    /** The theme's 1x1 lazy-load placeholder, which is not a real image. */
    fun isPlaceholder(url: String): Boolean = PLACEHOLDER_MARKERS.any { url.contains(it) }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val BASE64_WRITE_REGEX = Regex("""document\.write\(Base64\.decode\('([^']+)'\)\)""")

    /** `loadBannerDirect('https://...', ...)` used for listing covers. */
    private val BANNER_CALL_REGEX = Regex("""loadBannerDirect\(\s*['"]([^'"]+)['"]""")

    private val BOILERPLATE_MARKERS = listOf(
        "若遇到",
        "安全的",
        "修改使用安全",
        "DNS",
        "dns",
        "感谢大家",
        "最新地址",
        "永久地址",
        "邮箱：",
        "收藏",
        "浏览器",
        "复制",
    )

    private val AD_HOST_MARKERS = listOf(
        "hc237",
        "tjtagmanager",
    )

    private val AD_PATH_MARKERS = listOf(
        "/usr/themes/",
        "/upload-images/",
        "download.png",
        "chigua.png",
    )

    /** Lazy-load stand-ins that must never reach the reader. */
    private val PLACEHOLDER_MARKERS = listOf(
        "/usr/plugins/tbxw/zw.png",
        "zw.png",
        "data:image/gif;base64,R0lGOD",
        "blank.gif",
        "spacer.gif",
    )

    private val CONFIG_URL_REGEX = Regex("""["]url["]\s*:\s*["]((?:[^"\\]|\\.)*)["]""")

    private val MEDIA_URL_REGEX = Regex(
        """((?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4|webm|mkv|mov|m4v)(?:\?[^\s"'<>\\]*)?)""",
        RegexOption.IGNORE_CASE,
    )

    private val MEDIA_URL_HINT = Regex(
        """\.(m3u8|mp4|webm|mkv|mov|m4v)\b|/(hls|video|videos|stream)/""",
        RegexOption.IGNORE_CASE,
    )
}
