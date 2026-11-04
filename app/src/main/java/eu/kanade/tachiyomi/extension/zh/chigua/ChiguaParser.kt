package eu.kanade.tachiyomi.extension.zh.chigua

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parsing for the 51吃瓜 layout, kept free of Android and networking so it can be
 * unit-tested against saved pages.
 *
 * Only selectors and normalization live here; URL rewriting that depends on the live mirror
 * stays in [Chigua].
 */
internal object ChiguaParser {

    /** Result of reading one article detail page. */
    data class ArticleDetails(
        val title: String,
        val description: String,
        val tags: List<String>,
        val dateUpload: Long,
        val videoUrls: List<String>,
        val imageUrls: List<String>,
    )

    /**
     * Reads every article card of a listing page, in document order.
     * Cards without a title or an `/archives/` link (ad slots) are skipped.
     */
    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("article").mapNotNull { article ->
            val link = article.selectFirst("a[href*=\"/archives/\"]") ?: return@mapNotNull null
            val rawTitle = article.selectFirst(".post-card-title")
                ?.text()
                ?.replace("热搜HOT", "")
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

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    /**
     * Reads the "next page" link of the theme's pagination widget, or null on the last page.
     */
    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val anchor = document.selectFirst(".page-navigator li.next a[href]")
            ?: document.selectFirst(".page-navigator a.next[href]")
            ?: return null
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        if (href.isBlank()) return null
        return absoluteUrl(href)
    }

    private fun extractThumbnail(article: Element): String? {
        article.selectFirst(".blog-background")?.let { bg ->
            // Cards lazy-load the cover: `src` is a placeholder, the loader attribute is real.
            bg.attr("z-bg-loader-url")
                .ifBlank { bg.attr("x-bg-loader-url") }
                .ifBlank { bg.attr("data-bg") }
                .takeIf { it.isNotBlank() }
                ?.let { return it }

            val style = bg.attr("style")
            Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)?.let { return it.groupValues[1] }
        }

        article.selectFirst("img")?.let { img ->
            val src = img.absUrl("z-image-loader-url").ifBlank {
                img.absUrl("x-image-loader-url").ifBlank {
                    img.absUrl("data-src").ifBlank { img.absUrl("src") }
                }
            }
            if (src.isNotBlank() && !src.contains("gravatar") && !src.contains("avatar")) {
                return src
            }
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
        val imageUrls = extractImageUrls(document, normalizeUrl)

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
     * Collects the gallery images. The theme lazy-loads them, so `src` is a placeholder and the
     * real file lives in a loader attribute.
     */
    fun extractImageUrls(document: Document, normalizeUrl: (String) -> String): List<String> {
        val content = document.selectFirst(".post-content") ?: return emptyList()
        return content.select("img").mapNotNull { img ->
            val raw = img.absUrl("z-image-loader-url").ifBlank {
                img.absUrl("x-image-loader-url").ifBlank {
                    img.absUrl("data-src").ifBlank {
                        img.absUrl("data-original").ifBlank { img.absUrl("src") }
                    }
                }
            }
            if (raw.isBlank()) return@mapNotNull null

            when {
                isAdImage(raw) -> null
                raw.contains("gravatar") || raw.contains("avatar") -> null
                // Theme chrome (download badges, app promos) is marked no-zoom.
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

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

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

    /** Ad creatives are served from the site's own theme folder. */
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
