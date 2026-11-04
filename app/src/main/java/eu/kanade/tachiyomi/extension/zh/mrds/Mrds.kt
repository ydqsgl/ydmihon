package eu.kanade.tachiyomi.extension.zh.mrds

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
import okhttp3.Headers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 每日大赛 (mrds) — another Typecho + DPlayer site on the same Mirages theme as 51吃瓜.
 *
 * What is different from the other two sources:
 * - the advertised domains (`mrds1.club`, `mrdsk.com`) are Base64-wrapped "回到首页" stubs, so a
 *   probe has to decode the wrapper before it can tell a real mirror from a landing page;
 * - listing covers come from an inline `loadBannerDirect(...)` call;
 * - article images are lazy-loaded through `data-xkrkllgl`, with `src` pointing at a placeholder.
 */
class Mrds : HttpSource() {

    override val name = "每日大赛"
    override val lang = "zh"
    override val supportsLatest = true

    /**
     * Known hosts, tried in order.
     *
     * `mrds66.com` is the host the entrance pages themselves link to as the real site. The bare
     * host is preferred over `www.` because the `www` front end intermittently answers 502.
     */
    private val knownDomains = listOf(
        "https://mrds66.com",
        "https://www.mrds66.com",
        "https://mrds1.club",
        "https://mrdsk.com",
    )

    @Volatile
    private var currentBaseUrl: String = knownDomains.first()

    override val baseUrl: String get() = currentBaseUrl

    private val domainLock = Mutex()

    @Volatile
    private var domainVerified: Boolean = false

    /** Cache of "kind|page" -> absolute URL, learned from the `.page-navigator` next link. */
    private val nextPageUrls = ConcurrentHashMap<String, String>()

    override val client get() = Injekt.get<NetworkHelper>().client

    /** Built per request so the Referer matches the domain currently in use. */
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
        }
    }

    /**
     * A host is usable when its home page (after unwrapping the Base64 stub the entrance domains
     * serve) really contains article cards.
     *
     * The entrance domains decode to a small link hub rather than the site itself, so one hop to
     * a linked host is followed before giving up on the domain.
     */
    private suspend fun pingDomain(domain: String): Boolean {
        return try {
            val html = MrdsParser.decodeIfWrapped(fetchHtml("$domain/"))
            if (isListingPage(html)) return true

            val hop = linkedHost(html, domain) ?: return false
            val hopped = MrdsParser.decodeIfWrapped(fetchHtml("$hop/"))
            if (!isListingPage(hopped)) return false

            currentBaseUrl = hop
            true
        } catch (_: Exception) {
            false
        }
    }

    /** First absolute link to a different mrds host found on a link-hub page. */
    private fun linkedHost(html: String, current: String): String? {
        val currentHost = current.substringAfter("://").substringBefore("/")
        return HOST_LINK_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .map { "https://${it.substringAfter("://").substringBefore("/").substringBefore(":")}" }
            .firstOrNull { it.substringAfter("://") != currentHost && MIRROR_HOST_REGEX.matches(it.substringAfter("://")) }
    }

    private suspend fun fetchHtml(url: String): String {
        val response = client.newCall(GET(url, reqHeaders())).await()
        val body = response.body.string()
        response.close()
        return body
    }

    /** Real pages carry article cards; entrance stubs and ad landings do not. */
    private fun isListingPage(html: String): Boolean {
        if (html.length < MIN_USABLE_PAGE_LENGTH) return false
        return html.contains("post-card") && html.contains("/archives/")
    }

    private suspend fun fetchWithDomainRecovery(urlBuilder: () -> String): okhttp3.Response {
        ensureDomainWorks()

        var url = urlBuilder()
        var response = client.newCall(GET(url, reqHeaders())).await()

        // A 404 means the article is gone, not that the domain moved; only retry on server-side
        // failures, otherwise a deleted post would cycle every mirror before failing.
        if (response.code == 502 || response.code == 503) {
            response.close()
            domainVerified = false
            ensureDomainWorks()
            url = urlBuilder()
            response = client.newCall(GET(url, reqHeaders())).await()
        }

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 404) throw ContentGoneException()
            throw eu.kanade.tachiyomi.network.HttpException(code)
        }

        return response
    }

    /**
     * Parses a page body, unwrapping the Base64 `document.write` wrapper some hosts serve for
     * every URL, not just the home page.
     */
    private fun parseDocument(html: String): Document =
        Jsoup.parse(MrdsParser.decodeIfWrapped(html), baseUrl)

    private suspend fun fetchDocument(urlBuilder: () -> String): Document {
        val response = fetchWithDomainRecovery(urlBuilder)
        val body = response.body.string()
        response.close()
        return parseDocument(body)
    }

    // ============================== Popular / Latest / Search ==============================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = resolveListUrl(KIND_POPULAR, page) { p ->
            if (p <= 1) "$baseUrl/" else "$baseUrl/page/$p/"
        }
        val document = fetchDocument { url }
        return parseMangaList(document, KIND_POPULAR, page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = resolveListUrl(KIND_LATEST, page) { p ->
            if (p <= 1) "$baseUrl/category/mrds/" else "$baseUrl/category/mrds/$p/"
        }
        val document = fetchDocument { url }
        return parseMangaList(document, KIND_LATEST, page)
    }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.slug
        val keyword = query.trim()
        val kind = "$KIND_SEARCH|$keyword|${category ?: ""}"

        val url = resolveListUrl(kind, page) { p ->
            when {
                keyword.isNotEmpty() -> {
                    if (p <= 1) {
                        "$baseUrl/?s=${encodeQuery(keyword)}"
                    } else {
                        // Typecho search pagination: /search/<keyword>/<page>/
                        "$baseUrl/search/${encodeQuery(keyword)}/$p/"
                    }
                }
                category != null -> {
                    if (p <= 1) "$baseUrl/category/$category/" else "$baseUrl/category/$category/$p/"
                }
                else -> {
                    if (p <= 1) "$baseUrl/category/mrds/" else "$baseUrl/category/mrds/$p/"
                }
            }
        }

        val document = fetchDocument { url }
        return parseMangaList(document, kind, page)
    }

    private fun resolveListUrl(kind: String, page: Int, fallback: (Int) -> String): String {
        val learned = nextPageUrls["$kind|$page"]
        if (page > 1 && learned != null) return normalizeDomain(learned)
        return fallback(page)
    }

    private fun parseMangaList(document: Document, kind: String, page: Int): MangasPage {
        val mangas = MrdsParser.parseListing(document) { normalizeDomain(fixUrl(it)) }
            .map { item ->
                SManga.create().apply {
                    setUrlWithoutDomain(item.url)
                    title = item.title
                    thumbnail_url = item.thumbnailUrl?.let { normalizeDomain(fixUrl(it)) }
                    initialized = true
                }
            }

        val nextUrl = MrdsParser.findNextPageUrl(document) { normalizeDomain(fixUrl(it)) }
        if (nextUrl != null) {
            nextPageUrls["$kind|${page + 1}"] = nextUrl
        } else {
            nextPageUrls.remove("$kind|${page + 1}")
        }

        return MangasPage(mangas, mangas.isNotEmpty() && nextUrl != null)
    }

    // ============================== Manga Details ==============================

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = fetchDocument { getMangaUrl(manga) }

        if (document.selectFirst(".post-content") == null) {
            throw ContentGoneException()
        }

        val parsed = MrdsParser.parseArticle(
            document = document,
            fallbackTitle = manga.title,
            normalizeUrl = { normalizeDomain(fixUrl(it)) },
            dateParser = ::parseDate,
        )

        val details = SManga.create().apply {
            url = manga.url
            title = parsed.title
            description = parsed.description
            genre = parsed.tags.takeIf { it.isNotEmpty() }?.joinToString(", ")
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            thumbnail_url = parsed.imageUrls.firstOrNull() ?: manga.thumbnail_url
        }

        return SMangaUpdate(
            manga = details,
            chapters = buildChapterList(manga, parsed),
        )
    }

    // ============================== Chapter List ==============================

    /**
     * Each video becomes its own chapter, and the gallery becomes an extra "图片" chapter when it
     * carries more than a single cover frame.
     */
    private fun buildChapterList(manga: SManga, parsed: MrdsParser.ArticleDetails): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        parsed.videoUrls.forEachIndexed { index, _ ->
            chapters.add(
                SChapter.create().apply {
                    name = if (parsed.videoUrls.size > 1) "视频 ${index + 1}" else "视频"
                    // The fragment identifies which video of the article to play.
                    url = "${manga.url}#video=$index"
                    date_upload = parsed.dateUpload
                },
            )
        }

        if (parsed.imageUrls.isNotEmpty() && (parsed.videoUrls.isEmpty() || parsed.imageUrls.size > 1)) {
            chapters.add(
                SChapter.create().apply {
                    name = "图片"
                    url = "${manga.url}#images"
                    date_upload = parsed.dateUpload
                },
            )
        }

        if (chapters.isEmpty()) {
            chapters.add(
                SChapter.create().apply {
                    name = "内容"
                    url = manga.url
                    date_upload = parsed.dateUpload
                },
            )
        }

        return chapters
    }

    // ============================== Page List ==============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        // Chapter URLs may carry a fragment like #video=0 or #images.
        val cleanUrl = chapter.url.substringBefore("#")
        return "$baseUrl$cleanUrl"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val fragment = chapter.url.substringAfter("#", "")

        val document = fetchDocument { getChapterUrl(chapter) }

        if (document.selectFirst(".post-content") == null) {
            throw ContentGoneException()
        }

        if (fragment == "images") {
            val pages = MrdsParser.extractImageUrls(document) { normalizeDomain(fixUrl(it)) }
                .mapIndexed { index, url -> Page(index, imageUrl = url) }
            if (pages.isNotEmpty()) return pages
        }

        if (fragment.startsWith("video=")) {
            val videoIndex = fragment.substringAfter("video=").toIntOrNull() ?: 0
            val url = MrdsParser.extractVideoUrls(document) { normalizeDomain(fixUrl(it)) }
                .getOrNull(videoIndex)
            if (!url.isNullOrBlank()) return listOf(toVideoPage(0, url))
        }

        // Default / fallback: all videos first, then images.
        val pages = mutableListOf<Page>()
        MrdsParser.extractVideoUrls(document) { normalizeDomain(fixUrl(it)) }
            .forEach { pages.add(toVideoPage(pages.size, it)) }
        MrdsParser.extractImageUrls(document) { normalizeDomain(fixUrl(it)) }
            .forEach { pages.add(Page(pages.size, imageUrl = it)) }
        if (pages.isNotEmpty()) return pages

        return listOf(Page(0, imageUrl = ""))
    }

    private fun toVideoPage(index: Int, url: String): Page = Page(index, imageUrl = url).apply {
        videoUrl = url
    }

    // ============================== Filters ==============================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("注意：文字搜索时分类过滤被忽略"),
        Filter.Separator(),
        CategoryFilter(),
    )

    // ============================== Helpers ==============================

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

    /**
     * Points a URL at the domain currently in use.
     *
     * Pages and uploads are mirrored across the rotating mrds hosts, so those are rewritten.
     * Media CDNs (hls.*, pic.*, mts.*) keep their own domain.
     */
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""

        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        if (url.startsWith("//")) return "https:$url"
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

        // Only rewrite the rotating mirror hosts; CDN/media hosts keep their own domain.
        if (!MIRROR_HOST_REGEX.matches(host)) return url

        val pathAndQuery = afterScheme.substringAfter("/", "")
        return if (pathAndQuery.isNotEmpty()) "$scheme://$currentHost/$pathAndQuery" else "$scheme://$currentHost"
    }

    private fun encodeQuery(query: String): String =
        URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val siteZone = ZoneId.of("Asia/Shanghai")

    /**
     * Thrown for deleted articles so the reader shows an actionable message instead of "HTTP 404".
     */
    class ContentGoneException : Exception("该内容已失效（文章已删除或跳转到广告页）")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val KIND_POPULAR = "popular"
        private const val KIND_LATEST = "latest"
        private const val KIND_SEARCH = "search"

        /** Pages shorter than this are redirect entrance stubs, not real listings. */
        private const val MIN_USABLE_PAGE_LENGTH = 50_000

        /** Absolute links to a host, used to hop from an entrance hub to the real site. */
        private val HOST_LINK_REGEX = Regex("""https?://([a-z0-9.-]+)""", RegexOption.IGNORE_CASE)

        /** Rotating mirrors of the site itself (not the media CDN). */
        private val MIRROR_HOST_REGEX = Regex(
            """^(?:www\.)?(?:[a-z0-9-]+\.)?(?:mrds[0-9a-z-]*|mrdsk|mrds)\.(?:com|club|net|org|cc|vip|xyz|top|one|info)$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
