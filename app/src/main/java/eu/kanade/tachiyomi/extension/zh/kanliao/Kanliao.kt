package eu.kanade.tachiyomi.extension.zh.kanliao

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class Kanliao : HttpSource() {

    override val name = "今日看料"
    override val lang = "zh"
    override val supportsLatest = true

    /**
     * The domain discovery page (punycode: 回家.今日看料.com) that lists all current working domains.
     */
    private val domainDiscoveryUrl = "https://xn--zbsq6i.xn--wmqx96crga604d.com/"

    /**
     * Known domains, tried in order. These are updated from the discovery page when needed.
     */
    private val knownDomains = listOf(
        "https://kanliao30.com",
        "https://kanliao22.net",
        "https://kanliao20.org",
        "https://kanliao19.one",
        "https://kanliao23.cyou",
        "https://kanliao17.buzz",
    )

    @Volatile
    private var currentBaseUrl: String = knownDomains.first()

    override val baseUrl: String get() = currentBaseUrl

    private val domainLock = Mutex()

    @Volatile
    private var domainVerified: Boolean = false

    /**
     * Cache of "kind|page" -> absolute URL, discovered from the `.page-navigator` next link.
     * This is what makes pagination work without hardcoding every URL shape.
     */
    private val nextPageUrls = ConcurrentHashMap<String, String>()

    override val client get() = Injekt.get<NetworkHelper>().client

    /**
     * Built per request (instead of the lazily-cached [headers]) so the Referer always
     * matches the domain that is currently in use after a domain switch.
     */
    private fun reqHeaders(): Headers = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", USER_AGENT)
        add("Referer", "$baseUrl/")
    }

    // ============================== Domain Management ==============================

    private suspend fun ensureDomainWorks() {
        if (domainVerified) return
        domainLock.withLock {
            if (domainVerified) return
            if (pingDomain(currentBaseUrl)) {
                domainVerified = true
                return
            }
            for (domain in knownDomains) {
                if (pingDomain(domain)) {
                    currentBaseUrl = domain
                    domainVerified = true
                    return
                }
            }
            val newDomain = discoverNewDomain()
            if (newDomain != null) {
                currentBaseUrl = newDomain
                domainVerified = true
            }
        }
    }

    private suspend fun pingDomain(domain: String): Boolean {
        return try {
            val response = client.newCall(GET("$domain/ping", reqHeaders())).await()
            val body = response.body?.string()
            response.close()
            body?.trim() == "pong"
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun discoverNewDomain(): String? {
        return try {
            val response = client.newCall(GET(domainDiscoveryUrl, reqHeaders())).await()
            val html = response.body?.string() ?: ""
            response.close()
            val domainRegex = Regex("""https://[a-z0-9]+\.[a-z]+""")
            val domains = domainRegex.findAll(html).map { it.value }.distinct().toList()
            for (domain in domains) {
                if (pingDomain(domain)) return domain
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchWithDomainRecovery(urlBuilder: () -> String): okhttp3.Response {
        ensureDomainWorks()

        var url = urlBuilder()
        // Use await() instead of awaitSuccess() so we can check the status code ourselves
        // and retry with a different domain before throwing an error.
        var response = client.newCall(GET(url, reqHeaders())).await()

        if (response.code == 404 || response.code == 502 || response.code == 503) {
            response.close()
            domainVerified = false
            ensureDomainWorks()
            url = urlBuilder()
            response = client.newCall(GET(url, reqHeaders())).await()
        }

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw eu.kanade.tachiyomi.network.HttpException(code)
        }

        return response
    }

    // ============================== Popular / Latest / Search ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = resolveListUrl(KIND_POPULAR, page) { p ->
            if (p <= 1) "$baseUrl/" else "$baseUrl/page/$p/"
        }
        val document = fetchWithDomainRecovery { url }.asJsoup()
        return parseMangaList(document, KIND_POPULAR, page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = resolveListUrl(KIND_LATEST, page) { p ->
            if (p <= 1) "$baseUrl/category/dy/" else "$baseUrl/category/dy/$p/"
        }
        val document = fetchWithDomainRecovery { url }.asJsoup()
        return parseMangaList(document, KIND_LATEST, page)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()
        val category = categoryFilter
            ?.takeIf { it.state > 0 && it.state < it.values.size }
            ?.values?.get(categoryFilter.state)

        val kind = "$KIND_SEARCH|$query|${category ?: ""}"

        val url = resolveListUrl(kind, page) { p ->
            when {
                query.isNotEmpty() -> {
                    if (p <= 1) {
                        "$baseUrl/?s=${encodeQuery(query)}"
                    } else {
                        // Typecho search pagination: /search/<keyword>/<page>/
                        "$baseUrl/search/${encodeQuery(query)}/$p/"
                    }
                }
                category != null -> {
                    if (p <= 1) "$baseUrl/category/$category/" else "$baseUrl/category/$category/$p/"
                }
                else -> {
                    if (p <= 1) "$baseUrl/category/dy/" else "$baseUrl/category/dy/$p/"
                }
            }
        }

        val document = fetchWithDomainRecovery { url }.asJsoup()
        return parseMangaList(document, kind, page)
    }

    /**
     * Returns the absolute URL for [page] of a given list [kind].
     * Page 1 is always derived from the known URL shape; later pages prefer the "next" link
     * discovered while parsing the previous page, which is immune to URL-scheme changes.
     */
    private fun resolveListUrl(kind: String, page: Int, fallback: (Int) -> String): String {
        if (page > 1) {
            nextPageUrls["$kind|$page"]?.let { return normalizeDomain(it) }
        }
        return fallback(page)
    }

    private fun parseMangaList(document: Document, kind: String, page: Int): MangasPage {
        val mangas = document.select("article").mapNotNull { article ->
            val link = article.selectFirst("a[href*=/archives/]") ?: return@mapNotNull null
            val titleEl = article.selectFirst(".post-card-title")
            val rawTitle = titleEl?.text()
                ?.replace("热搜HOT", "")
                ?.replace("热搜", "")
                ?.trim()
            if (rawTitle.isNullOrBlank()) return@mapNotNull null

            val href = link.absUrl("href").ifBlank { link.attr("href") }
            if (href.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(normalizeDomain(href))
                title = rawTitle
                thumbnail_url = extractThumbnail(article)
                initialized = true
            }
        }.distinctBy { it.url }

        val nextUrl = findNextPageUrl(document, page)
        if (nextUrl != null) {
            nextPageUrls["$kind|${page + 1}"] = nextUrl
        } else {
            nextPageUrls.remove("$kind|${page + 1}")
        }

        return MangasPage(mangas, mangas.isNotEmpty() && nextUrl != null)
    }

    /**
     * Reads the "next page" anchor from the theme's pagination widget.
     * Returns null on the last page.
     */
    private fun findNextPageUrl(document: Document, currentPage: Int): String? {
        val anchor = document.selectFirst(".page-navigator li.next a[href]")
            ?: document.selectFirst(".page-navigator a.next[href]")
            ?: return null

        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        if (href.isBlank()) return null

        val absolute = when {
            href.startsWith("http://", true) || href.startsWith("https://", true) -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$baseUrl$href"
            else -> "$baseUrl/$href"
        }

        // Guard against a pagination widget that links back to the current page.
        val currentUrl = when (currentPage) {
            1 -> "$baseUrl/"
            else -> null
        }
        if (currentUrl != null && absolute.trimEnd('/') == currentUrl.trimEnd('/')) return null

        return normalizeDomain(absolute)
    }

    private fun extractThumbnail(article: Element): String? {
        val bgEl = article.selectFirst(".blog-background")
        if (bgEl != null) {
            val style = bgEl.attr("style")
            val match = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)
            if (match != null) {
                return fixUrl(match.groupValues[1])
            }
        }

        val script = article.selectFirst("script")
        if (script != null) {
            val scriptText = script.data()
            val match = Regex("""loadBannerDirect\(['"]([^'"]+)['"]""").find(scriptText)
            if (match != null) {
                val banner = match.groupValues[1]
                // Skip the rotating ad banners that some posts are padded with.
                if (!isAdDomain(banner)) return fixUrl(banner)
            }
        }

        val img = article.selectFirst("img")
        if (img != null) {
            val src = img.absUrl("src")
            if (src.isNotEmpty() && !src.contains("gravatar") && !src.contains("avatar") && !isAdDomain(src)) {
                return fixUrl(src)
            }
        }

        return null
    }

    // ============================== Manga Details ==============================

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = fetchWithDomainRecovery { getMangaUrl(manga) }
        if (isGoneRedirect(response)) {
            response.close()
            throw Exception("该内容已失效（文章已删除或跳转到广告页）")
        }
        val document = response.asJsoup()
        return SMangaUpdate(
            manga = parseMangaDetails(document, manga),
            chapters = parseChapterList(document, manga),
        )
    }

    private fun parseMangaDetails(document: Document, manga: SManga): SManga = SManga.create().apply {
        url = manga.url

        val docTitle = document.title().substringBefore(" - ")
        title = docTitle.ifBlank { manga.title }

        val contentEl = document.selectFirst(".post-content")
        val description = StringBuilder()

        val videoCount = extractVideoUrls(document).size
        val imageCount = extractImageUrls(document).size

        if (videoCount > 0) {
            description.append("共 $videoCount 个视频")
            if (imageCount > 0) description.append("，$imageCount 张图片")
            description.append("\n\n")
        }

        val paragraphs = contentEl?.select("p")?.map { it.text() }
            ?.filter { it.isNotBlank() }
            ?.filter {
                !it.contains("若遇到") &&
                    !it.contains("安全的") &&
                    !it.contains("修改使用安全") &&
                    !it.contains("DNS") &&
                    !it.contains("dns") &&
                    !it.contains("感谢大家")
            } ?: emptyList()
        if (paragraphs.isNotEmpty()) {
            description.append(paragraphs.take(5).joinToString("\n"))
        }

        this.description = description.toString().ifBlank { title }

        val tags = document.select(".tags a, .post-tags a").map { it.text().trim() }
            .filter { it.isNotBlank() }
        genre = if (tags.isNotEmpty()) tags.joinToString(", ") else null

        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE

        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
        val fixedOg = ogImage?.let { fixUrl(it) } ?: ""
        thumbnail_url = when {
            fixedOg.isNotBlank() -> fixedOg
            else -> extractImageUrls(document).firstOrNull() ?: manga.thumbnail_url
        }
    }

    // ============================== Chapter List ==============================

    /**
     * Extract all videos from the article page. Each video becomes a chapter.
     * If there are no videos but there are images, create a single "图片" chapter.
     * If there are both videos and images, create video chapters plus an image chapter.
     */
    private fun parseChapterList(document: Document, manga: SManga): List<SChapter> {
        val dateEl = document.selectFirst("meta[itemprop=datePublished], meta[itemprop=dateModified]")
        val dateStr = dateEl?.attr("content")
        val dateUpload = parseDate(dateStr)

        val chapters = mutableListOf<SChapter>()
        val videoUrls = extractVideoUrls(document)
        val imageUrls = extractImageUrls(document)

        videoUrls.forEachIndexed { index, _ ->
            chapters.add(
                SChapter.create().apply {
                    name = if (videoUrls.size > 1) "视频 ${index + 1}" else "视频"
                    // The fragment identifies which video of the article to play.
                    url = "${manga.url}#video=$index"
                    date_upload = dateUpload
                },
            )
        }

        // Only surface the image chapter when it actually carries content, otherwise a single
        // cover frame from the video would show up as a one-page "图片" chapter.
        if (imageUrls.isNotEmpty() && (videoUrls.isEmpty() || imageUrls.size > 1)) {
            chapters.add(
                SChapter.create().apply {
                    name = "图片"
                    url = "${manga.url}#images"
                    date_upload = dateUpload
                },
            )
        }

        // Fallback: if no videos and no images, create a default chapter
        if (chapters.isEmpty()) {
            chapters.add(
                SChapter.create().apply {
                    name = "内容"
                    url = manga.url
                    date_upload = dateUpload
                },
            )
        }

        return chapters
    }

    // ============================== Page List ==============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        // Chapter URL may contain a fragment like #video=0 or #images
        // Strip the fragment for the HTTP request
        val cleanUrl = chapter.url.substringBefore("#")
        return "$baseUrl$cleanUrl"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val fragment = chapter.url.substringAfter("#", "")

        val response = fetchWithDomainRecovery { getChapterUrl(chapter) }
        if (isGoneRedirect(response)) {
            response.close()
            throw Exception("该内容已失效（文章已删除或跳转到广告页）")
        }
        val document = response.asJsoup()

        if (document.selectFirst(".post-content") == null) {
            throw Exception("页面解析失败，内容可能已失效")
        }

        if (fragment == "images") {
            val pages = extractImageUrls(document).mapIndexed { index, url ->
                Page(index, imageUrl = url)
            }
            if (pages.isNotEmpty()) return pages
        }

        if (fragment.startsWith("video=")) {
            val videoIndex = fragment.substringAfter("video=").toIntOrNull() ?: 0
            val url = extractVideoUrls(document).getOrNull(videoIndex)
            if (!url.isNullOrBlank()) return listOf(toVideoPage(url))
        }

        // Default / fallback: all videos first, then images.
        val pages = mutableListOf<Page>()
        extractVideoUrls(document).forEach { pages.add(toVideoPage(it)) }
        extractImageUrls(document).forEach { pages.add(Page(pages.size, imageUrl = it)) }
        if (pages.isNotEmpty()) return pages

        // Last resort: return a placeholder page so the reader doesn't show "empty"
        return listOf(Page(0, imageUrl = ""))
    }

    private fun toVideoPage(url: String): Page = Page(0, imageUrl = url).apply {
        videoUrl = url
    }

    // ============================== Video / Image extraction ==============================

    /**
     * Collects every playable video URL of an article, in document order.
     *
     * The theme renders DPlayer as:
     * `<div class="dplayer" data-config='{"video":{"url":"https:\/\/host\/...\/index.m3u8"}}'>`
     * The escaped slashes inside the JSON are why a naive `[^\\"]+` regex only ever captured
     * the scheme ("https:") and produced a "source error" in the player.
     */
    private fun extractVideoUrls(document: Document): List<String> {
        val content = document.selectFirst(".post-content") ?: document.body() ?: return emptyList()
        val urls = LinkedHashSet<String>()

        // 1) DPlayer configs (and any other element exposing data-config).
        content.select(".dplayer, [data-config]").forEach { el ->
            val config = el.attr("data-config").ifBlank { el.attr("data-setup") }
            if (config.isNotBlank()) {
                extractUrlFromConfig(config)?.let { urls.add(it) }
            }
        }

        // 2) Plain HTML5 video tags.
        content.select("video[src], video source[src], source[src]").forEach { el ->
            val src = el.absUrl("src").ifBlank { el.attr("src") }
            if (src.isNotBlank()) urls.add(src)
        }

        // 3) Last resort: scan the raw markup for streaming URLs. Only used when the
        //    structured extraction found nothing, so ad banners are not picked up.
        if (urls.isEmpty()) {
            MEDIA_URL_REGEX.findAll(document.html()).forEach { match ->
                urls.add(match.groupValues[1])
            }
        }

        return urls.mapNotNull { normalizeVideoUrl(it) }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun extractImageUrls(document: Document): List<String> {
        val content = document.selectFirst(".post-content") ?: return emptyList()
        return content.select("img").mapNotNull { img ->
            val raw = img.absUrl("src").ifBlank {
                img.absUrl("data-src").ifBlank { img.absUrl("data-original") }
            }
            if (raw.isBlank()) return@mapNotNull null
            val src = fixUrl(raw)
            when {
                src.isBlank() -> null
                src.contains("gravatar") || src.contains("avatar") -> null
                isAdDomain(src) -> null
                else -> src
            }
        }.distinct()
    }

    /**
     * Pulls the media URL out of a DPlayer config blob.
     * Tries real JSON parsing first, then a regex that tolerates escaped slashes.
     */
    private fun extractUrlFromConfig(rawConfig: String): String? {
        val config = rawConfig
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
            .trim()

        // Preferred: parse it as JSON so nested objects/escapes are handled correctly.
        runCatching {
            val element = jsonParser.parseToJsonElement(config)
            findUrlInJson(element)
        }.getOrNull()?.let { return unescapeSlashes(it) }

        // Fallback: `"url":"..."` allowing backslash-escaped characters.
        CONFIG_URL_REGEX.find(config)?.let { match ->
            return unescapeSlashes(match.groupValues[1])
        }

        return null
    }

    private fun findUrlInJson(element: JsonElement): String? {
        when (element) {
            is JsonObject -> {
                // DPlayer: {"video":{"url":"..."}}
                (element["video"] as? JsonObject)?.let { video ->
                    (video["url"] as? JsonPrimitive)?.contentOrNull?.let { return it }
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

    private fun unescapeSlashes(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .trim()

    /**
     * Validates and normalizes a media URL coming from the page.
     * Rejects the garbage that used to reach the player (e.g. a bare "https:").
     */
    private fun normalizeVideoUrl(rawUrl: String): String? {
        val url = unescapeSlashes(rawUrl)
        if (url.isBlank()) return null

        val absolute = when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$baseUrl$url"
            else -> return null
        }

        // A usable media URL must have a host and either a media extension or a media path.
        val parsed = runCatching { absolute.toHttpUrl() }.getOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        if (!parsed.encodedPath.contains(".") && !MEDIA_URL_HINT.containsMatchIn(absolute)) return null

        return fixUrl(absolute)
    }

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("注意：文字搜索时分类过滤被忽略"),
        Filter.Separator(),
        CategoryFilter(),
    )

    // ============================== Helpers ==============================

    /**
     * The site answers with a 301 to `/go/xxxx/` for articles that were taken down.
     * OkHttp follows that redirect, so we have to look at the final request URL.
     */
    private fun isGoneRedirect(response: okhttp3.Response): Boolean {
        val path = response.request.url.encodedPath
        return path.startsWith("/go/")
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            val datePart = dateStr.substringBefore("T")
            java.time.LocalDate.parse(datePart, dateFormat)
                .atStartOfDay(siteZone)
                .toEpochSecond() * 1000
        } catch (_: Exception) {
            0L
        }
    }

    private fun isAdDomain(url: String): Boolean = AD_DOMAINS.any { url.contains(it, ignoreCase = true) }

    /**
     * Points a URL at the domain that currently works.
     *
     * Uploads and pages are mirrored across the rotating kanliao domains (older ones such as
     * `kanliao20.org` are dead but `kanliao30.com` still serves the same `/usr/uploads/...`
     * files), so kanliao hosts are rewritten. Real media CDNs are left untouched.
     */
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""

        if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
            return normalizeDomain(url)
        }

        // Protocol-relative URL (e.g. //cdn.example.com/video.m3u8)
        if (url.startsWith("//")) return "https:$url"
        // Relative path - prepend base URL
        if (url.startsWith("/")) return "$baseUrl$url"
        return "$baseUrl/$url"
    }

    private fun normalizeDomain(url: String): String {
        if (url.isBlank()) return url
        val scheme = when {
            url.startsWith("https://", true) -> "https"
            url.startsWith("http://", true) -> "http"
            else -> return url
        }
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore("/").substringBefore(":")
        if (host.isBlank()) return url

        val currentHost = currentBaseUrl.substringAfter("://").substringBefore("/")
        if (host.equals(currentHost, ignoreCase = true)) return url

        // Only rewrite the rotating mirror hosts; CDN/media hosts must keep their own domain.
        val isMirrorHost = KANLIAO_HOST_REGEX.matches(host)
        if (!isMirrorHost) return url

        val pathAndQuery = afterScheme.substringAfter("/", "")
        return if (pathAndQuery.isNotEmpty()) "$scheme://$currentHost/$pathAndQuery" else "$scheme://$currentHost"
    }

    private fun encodeQuery(query: String): String =
        URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val siteZone = ZoneId.of("Asia/Shanghai")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val KIND_POPULAR = "popular"
        private const val KIND_LATEST = "latest"
        private const val KIND_SEARCH = "search"

        /** Rotating mirrors of the site itself (not the media CDN). */
        private val KANLIAO_HOST_REGEX = Regex("""^(?:[a-z0-9-]+\.)?kanliao[0-9a-z-]*\.(?:com|net|org|one|cyou|buzz|top|xyz|cc|vip|info)$""", RegexOption.IGNORE_CASE)

        private val AD_DOMAINS = listOf(
            "jingcaitu",
            "sstdozb",
            "nibuaideren",
            "mgm",
            "0919-0918",
            "gxg3rjt",
        )

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** `"url":"..."` tolerating escaped slashes and quotes. */
        private val CONFIG_URL_REGEX = Regex("""["]url["]\s*:\s*["]((?:[^"\\]|\\.)*)["]""")

        /** Direct links to a media file, escaped or not. */
        private val MEDIA_URL_REGEX = Regex(
            """((?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4|webm|mkv|mov|m4v)(?:\?[^\s"'<>\\]*)?)""",
            RegexOption.IGNORE_CASE,
        )

        private val MEDIA_URL_HINT = Regex("""\.(m3u8|mp4|webm|mkv|mov|m4v)\b|/(hls|video|videos|stream)/""", RegexOption.IGNORE_CASE)
    }
}
