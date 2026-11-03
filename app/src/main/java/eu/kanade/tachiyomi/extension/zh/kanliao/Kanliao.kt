package eu.kanade.tachiyomi.extension.zh.kanliao

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    // Override client to avoid injectLazy() issues with direct constructor instantiation
    override val client get() = Injekt.get<NetworkHelper>().client

    // Do NOT call super.headersBuilder() because it uses `network.defaultUserAgentProvider()`
    // which relies on injectLazy() and fails when Kanliao is directly instantiated (not via DI).
    override fun headersBuilder(): okhttp3.Headers.Builder = okhttp3.Headers.Builder().apply {
        add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        add("Referer", baseUrl)
    }

    /**
     * Verify that the current domain is working. Called before every request.
     * If the current domain fails the ping check, tries all known domains
     * and falls back to the discovery page to find new ones.
     */
    private suspend fun ensureDomainWorks() {
        if (domainVerified) return
        domainLock.withLock {
            if (domainVerified) return
            // Ping the current domain
            if (pingDomain(currentBaseUrl)) {
                domainVerified = true
                return
            }
            // Current domain is down - try all known domains
            for (domain in knownDomains) {
                if (pingDomain(domain)) {
                    currentBaseUrl = domain
                    domainVerified = true
                    return
                }
            }
            // All known domains failed - fetch from discovery page
            val newDomain = discoverNewDomain()
            if (newDomain != null) {
                currentBaseUrl = newDomain
                domainVerified = true
            }
        }
    }

    /**
     * Ping a domain to check if it's alive. Returns true if the domain responds with "pong".
     */
    private suspend fun pingDomain(domain: String): Boolean {
        return try {
            val response = client.newCall(GET("$domain/ping", headers)).awaitSuccess()
            val body = response.body?.string()
            response.close()
            body?.trim() == "pong"
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fetch the discovery page and extract new domains from its JavaScript.
     */
    private suspend fun discoverNewDomain(): String? {
        return try {
            val response = client.newCall(GET(domainDiscoveryUrl, headers)).awaitSuccess()
            val html = response.body?.string() ?: ""
            response.close()
            // Extract domains from JavaScript array: "https://kanliaoXX.xxx"
            val domainRegex = Regex("""https://[a-z0-9]+\.[a-z]+""")
            val domains = domainRegex.findAll(html).map { it.value }.distinct().toList()
            for (domain in domains) {
                if (pingDomain(domain)) {
                    return domain
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch a URL with automatic domain recovery.
     * First ensures the domain works, then makes the request.
     * If the request still fails with 404/502/503, tries switching domains and retries.
     */
    private suspend fun fetchWithDomainRecovery(urlBuilder: () -> String): okhttp3.Response {
        ensureDomainWorks()

        var url = urlBuilder()
        var response = client.newCall(GET(url, headers)).awaitSuccess()

        if (response.code == 404 || response.code == 502 || response.code == 503) {
            response.close()
            // Domain might have just gone down - reset verification and try again
            domainVerified = false
            ensureDomainWorks()
            url = urlBuilder()
            response = client.newCall(GET(url, headers)).awaitSuccess()
        }

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw eu.kanade.tachiyomi.network.HttpException(code)
        }

        return response
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = fetchWithDomainRecovery { "$baseUrl/" }
        val document = response.asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = fetchWithDomainRecovery { "$baseUrl/category/dy/" }
        val document = response.asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()

        val url = when {
            query.isNotEmpty() -> {
                "$baseUrl/".toHttpUrl().newBuilder()
                    .addQueryParameter("s", query)
                    .build()
            }
            categoryFilter != null && categoryFilter.state > 0 -> {
                val category = categoryFilter.values[categoryFilter.state]
                "$baseUrl/category/$category/".toHttpUrl()
            }
            else -> {
                "$baseUrl/category/dy/".toHttpUrl()
            }
        }

        val response = fetchWithDomainRecovery { url.toString() }
        val document = response.asJsoup()
        return parseMangaList(document)
    }

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("article").mapNotNull { article ->
            val link = article.selectFirst("a[href*=/archives/]") ?: return@mapNotNull null
            val titleEl = article.selectFirst(".post-card-title")
            val rawTitle = titleEl?.text()?.replace("热搜HOT", "")?.trim()
            if (rawTitle.isNullOrBlank()) return@mapNotNull null

            val href = link.absUrl("href")
            val thumbnail = extractThumbnail(article)

            SManga.create().apply {
                setUrlWithoutDomain(href)
                title = rawTitle
                thumbnail_url = thumbnail
                initialized = true
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, false)
    }

    private fun extractThumbnail(article: Element): String? {
        val bgEl = article.selectFirst(".blog-background")
        if (bgEl != null) {
            val style = bgEl.attr("style")
            val match = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(style)
            if (match != null) {
                val url = match.groupValues[1]
                return fixUrl(url)
            }
        }

        val script = article.selectFirst("script")
        if (script != null) {
            val scriptText = script.data()
            val match = Regex("""loadBannerDirect\(['"]([^'"]+)['"]""").find(scriptText)
            if (match != null) {
                return fixUrl(match.groupValues[1])
            }
        }

        val img = article.selectFirst("img")
        if (img != null) {
            val src = img.absUrl("src")
            if (src.isNotEmpty() && !src.contains("gravatar") && !src.contains("avatar")) return src
        }

        return null
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = fetchWithDomainRecovery { getMangaUrl(manga) }
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

        val dplayer = contentEl?.selectFirst(".dplayer")
        var videoUrl: String? = null
        if (dplayer != null) {
            val configJson = dplayer.attr("data-config")
            val videoUrlMatch = Regex(""""url"\s*:\s*\\?"([^\\"]+)\\?"*""").find(configJson)
            if (videoUrlMatch != null) {
                videoUrl = videoUrlMatch.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\u002F", "/")
                description.append("视频链接: $videoUrl\n\n")
            }
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
            if (description.isNotEmpty()) description.append("\n")
            description.append(paragraphs.take(5).joinToString("\n"))
        }

        this.description = description.toString().ifBlank { title }

        val tags = document.select(".tags a, .post-tags a").map { it.text().trim() }
            .filter { it.isNotBlank() }
        genre = if (tags.isNotEmpty()) tags.joinToString(", ") else null

        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE

        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
        thumbnail_url = ogImage ?: manga.thumbnail_url
    }

    private fun parseChapterList(document: Document, manga: SManga): List<SChapter> {
        val dateEl = document.selectFirst("meta[itemprop=datePublished], meta[itemprop=dateModified]")
        val dateStr = dateEl?.attr("content")
        val dateUpload = parseDate(dateStr)

        return listOf(
            SChapter.create().apply {
                name = "内容"
                url = manga.url
                date_upload = dateUpload
            },
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = fetchWithDomainRecovery { getChapterUrl(chapter) }
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()

        val contentEl = document.selectFirst(".post-content")
        if (contentEl != null) {
            // Extract video URL from DPlayer config
            val dplayer = contentEl.selectFirst(".dplayer")
            if (dplayer != null) {
                val configJson = dplayer.attr("data-config")
                val videoUrlMatch = Regex(""""url"\s*:\s*\\?"([^\\"]+)\\?"*""").find(configJson)
                if (videoUrlMatch != null) {
                    var rawVideoUrl = videoUrlMatch.groupValues[1]
                        .replace("\\/", "/")
                        .replace("\\u002F", "/")
                    // Ensure video URL has a proper scheme
                    val videoUrl = fixUrl(rawVideoUrl)
                    if (videoUrl.isNotBlank()) {
                        // Use video URL as imageUrl so it survives cache serialization
                        val page = Page(0, imageUrl = videoUrl)
                        page.videoUrl = videoUrl
                        pages.add(page)
                    }
                }
            }

            // Also extract body images as additional pages
            val imgs = contentEl.select("img")
            imgs.forEach { img ->
                var src = img.absUrl("src").ifBlank { img.absUrl("data-src") }
                src = fixUrl(src)
                if (src.isNotEmpty() &&
                    !src.contains("gravatar") &&
                    !src.contains("avatar") &&
                    !src.contains("ad") &&
                    !src.contains("banner") &&
                    !isAdDomain(src)
                ) {
                    pages.add(Page(pages.size, imageUrl = src))
                }
            }
        }

        if (pages.isEmpty()) {
            var ogImage = document.selectFirst("meta[property=og:image]")?.attr("content") ?: ""
            ogImage = fixUrl(ogImage)
            if (ogImage.isNotBlank()) {
                pages.add(Page(0, imageUrl = ogImage))
            }
        }

        return pages
    }

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("注意：文字搜索时分类过滤被忽略"),
        Filter.Separator(),
        CategoryFilter(),
    )

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

    private fun isAdDomain(url: String): Boolean {
        val adDomains = listOf(
            "jingcaitu",
            "sstdozb",
            "nibuaideren",
            "mgm",
            "0919-0918",
            "gxg3rjt",
            "henxihuan",
        )
        return adDomains.any { url.contains(it) }
    }

    /**
     * Fix a URL to ensure it uses the current working domain.
     * - Replaces any known stale kanliao domain (or subdomain containing "kanliao") with the current baseUrl.
     * - Handles protocol-relative URLs (//example.com/path) and relative paths.
     */
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""

        // Already an absolute URL — check if its host contains "kanliao"
        if (url.startsWith("http://") || url.startsWith("https://")) {
            val hostPart = try {
                val afterScheme = url.substringAfter("://")
                afterScheme.substringBefore("/").substringBefore(":")
            } catch (_: Exception) { "" }

            val currentHost = currentBaseUrl.substringAfter("://").substringBefore("/")

            // If the host contains "kanliao" and is NOT the current domain, replace it
            if (hostPart != currentHost && hostPart.contains("kanliao", ignoreCase = true)) {
                val pathAndQuery = url.substringAfter("://").substringAfter("/", "")
                return if (pathAndQuery.isNotEmpty()) {
                    "$baseUrl/$pathAndQuery"
                } else {
                    baseUrl
                }
            }
            return url
        }

        // Protocol-relative URL (e.g. //cdn.example.com/video.m3u8)
        if (url.startsWith("//")) return "https:$url"
        // Relative path - prepend base URL
        if (url.startsWith("/")) return "$baseUrl$url"
        return "$baseUrl/$url"
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val siteZone = ZoneId.of("Asia/Shanghai")
}
