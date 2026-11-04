package eu.kanade.tachiyomi.extension.zh.common

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

/**
 * Base class for the video sites in this bundle.
 *
 * All seven sites share the same access pattern even though their markup is unrelated:
 *
 * - a handful of rotating mirror hosts, only one of which works at a time;
 * - a listing that paginates, where the "next" URL is more reliable than a page-number formula;
 * - one clip per details page, played from an m3u8 behind a Referer check.
 *
 * Subclasses only supply selectors and site-specific URL shapes. Everything else — mirror probing,
 * learned pagination, 404-vs-mirror-failure handling, the single-chapter shape — is handled here so
 * a fix in one place benefits all seven sources.
 *
 * @param knownDomains mirror hosts, most reliable first. The first entry is the default.
 * @param pageSizeHint used by sites that serve a fixed number of items per page, for `hasNextPage`.
 */
abstract class VideoSource(
    knownDomains: List<String>,
) : HttpSource() {

    private val knownDomains = knownDomains.map { it.trimEnd('/') }

    @Volatile
    private var currentBaseUrl: String = knownDomains.first()

    override val baseUrl: String get() = currentBaseUrl

    private val domainLock = Mutex()

    @Volatile
    private var domainVerified = false

    /**
     * "kind|page" -> absolute URL learned from the site's own "next page" link.
     *
     * These sites disagree about pagination (`/page/2/`, `?page=2`, `/vodtype/1-2.html`,
     * `/newvideo/2/`), and some of them change it without notice. Following the rendered link is
     * more durable than hard-coding a formula, so the URL is remembered per listing kind.
     */
    private val nextPageUrls = ConcurrentHashMap<String, String>()

    // ============================== Site contract ==============================

    /** Extra request headers; the default is a mobile UA with a matching Referer. */
    protected open fun requestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", VideoSourceSupport.USER_AGENT)
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("Referer", "$baseUrl/")
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", VideoSourceSupport.USER_AGENT)
        .add("Referer", "$baseUrl/")

    /** True when [html] is a real listing page for this site rather than a stub or ad landing. */
    protected abstract fun isListingHtml(html: String): Boolean

    /** Listing URL for [page] (1-based) before any learned "next" URL is known. */
    protected abstract fun listingUrl(page: Int): String

    /**
     * Parses listing cards. Return an empty list when the page has no items.
     *
     * Defaults to "no cards": a source that pages through a catalogue index instead of crawling
     * listing HTML (see 糖心Vlog) has nothing to parse here.
     */
    protected open fun parseListing(document: Document, page: Int): List<ListingItem> = emptyList()

    /**
     * Finds the "next page" link in a listing document, or null on the last page.
     *
     * Defaults to null, which ends pagination after the first page.
     */
    protected open fun findNextPageUrl(document: Document): String? = null

    /** Parses a details page into the entry plus its playable clip. */
    protected abstract fun parseDetails(document: Document, manga: SManga): DetailsResult

    /**
     * Resolves the playable URL from a chapter document; null when the site hides it.
     *
     * Defaults to null, which is correct for a gallery source that overrides [parseGalleryPages]:
     * it never reaches the video branch.
     */
    protected open suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? = null

    /** One card on a listing page. */
    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    /**
     * Details of a page that yields one or more playable chapters.
     *
     * Most sites publish one clip per page, so [chapters] defaults to a single entry pointing back
     * at the page itself. A series site (one page listing many episodes) fills it with one chapter
     * per episode URL instead.
     */
    data class DetailsResult(
        val title: String,
        val description: String = "",
        val genre: String? = null,
        val thumbnailUrl: String? = null,
        val dateUpload: Long = 0L,
        val chapterName: String = "视频",
        val chapters: List<ChapterSpec> = emptyList(),
    )

    /** One playable item inside a details page. [url] is a site-relative path. */
    data class ChapterSpec(
        val url: String,
        val name: String,
    )

    // ============================== Mirror handling ==============================

    /**
     * Confirms the current mirror serves real pages, switching to another host if it does not.
     *
     * The probe fetches [probePath], not the site root: several of these sites answer `/` with an
     * age gate or a landing banner that is not a listing, which would otherwise make a perfectly
     * healthy mirror look dead and send the source hunting through mirrors that are worse.
     */
    protected suspend fun ensureDomainWorks() {
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
     * Path fetched when probing a mirror, resolved against each candidate host.
     *
     * Defaults to the first page of the browse listing, which is by definition a listing page.
     */
    protected open val probePath: String get() = "/"

    private suspend fun pingDomain(domain: String): Boolean = try {
        val path = probePath
        val url = if (path == "/") "$domain/" else VideoSourceSupport.resolveUrl(domain, path)
        val html = fetchText(url)
        isListingHtml(html) || isListingHtml(VideoSourceSupport.decodeWrappedPage(html))
    } catch (_: Exception) {
        false
    }

    private suspend fun fetchText(url: String): String {
        val response = client.newCall(GET(url, requestHeaders())).await()
        val body = response.body.string()
        response.close()
        return body
    }

    /**
     * GET with mirror recovery.
     *
     * Retries once on 502/503 (a mirror dying mid-session) by re-probing hosts. A 404 is *not*
     * retried: the clip was removed, and cycling every mirror would only delay the error.
     */
    protected suspend fun fetch(url: String): Response {
        ensureDomainWorks()
        var response = client.newCall(GET(url, requestHeaders())).await()

        if (response.code == 502 || response.code == 503) {
            response.close()
            domainVerified = false
            ensureDomainWorks()
            response = client.newCall(GET(url, requestHeaders())).await()
        }

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 404 || code == 410) throw ContentGoneException()
            throw eu.kanade.tachiyomi.network.HttpException(code)
        }
        return response
    }

    /** Fetches and parses a page, unwrapping Base64 document stubs when present. */
    protected suspend fun fetchDocument(url: String): Document {
        val response = fetch(url)
        val html = response.body.string()
        response.close()
        return org.jsoup.Jsoup.parse(VideoSourceSupport.decodeWrappedPage(html), url)
    }

    // ============================== Listing plumbing ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = getListing(KIND_POPULAR, page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing(KIND_LATEST, page)

    /**
     * Browse. When a query is present the site's own search is used; otherwise the category filter
     * maps to a listing URL.
     */
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val keyword = query.trim()
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.slug
        val kind = "search|$keyword|${category.orEmpty()}"

        val url = nextPageUrls["$kind|$page"]?.takeIf { page > 1 && keyword.isEmpty() }
            ?: searchUrl(page, keyword, category)

        return getListingPage(url, kind, page, search = keyword.isNotEmpty())
    }

    /** Builds the first-page URL for a search or category browse. Override per site. */
    protected open fun searchUrl(page: Int, keyword: String, category: String?): String {
        return if (keyword.isNotEmpty()) searchUrlFor(keyword, page) else categoryUrl(category, page)
    }

    /** Site search results URL. */
    protected open fun searchUrlFor(keyword: String, page: Int): String = listingUrl(page)

    /** Category listing URL; null category means the site's default feed. */
    protected open fun categoryUrl(category: String?, page: Int): String = listingUrl(page)

    private suspend fun getListing(kind: String, page: Int): MangasPage {
        val url = if (page > 1) nextPageUrls["$kind|$page"] ?: listingUrl(page) else listingUrl(page)
        return getListingPage(url, kind, page, search = false)
    }

    private suspend fun getListingPage(url: String, kind: String, page: Int, search: Boolean): MangasPage {
        val document = fetchDocument(url)
        val items = parseListing(document, page)

        val mangas = items.map { item ->
            SManga.create().apply {
                setUrlWithoutDomain(item.url)
                title = item.title
                thumbnail_url = item.thumbnailUrl
                initialized = true
            }
        }

        val next = findNextPageUrl(document)
        if (next != null) {
            nextPageUrls["$kind|${page + 1}"] = next
        } else {
            nextPageUrls.remove("$kind|${page + 1}")
        }

        // Empty pages end pagination even when the site renders a "next" link pointing at nothing.
        val hasNext = mangas.isNotEmpty() && next != null
        return MangasPage(mangas, hasNext)
    }

    // ============================== Details / chapters ==============================

    /**
     * Fetches the clip page once and derives both the details and the chapter list from it.
     *
     * When only chapters are wanted (a library refresh) the page still has to be read, because a
     * series' episode list only exists there — there is no separate chapter endpoint on these sites.
     */
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = getMangaUrl(manga)
        val document = fetchDocument(url)
        val details = parseDetails(document, manga)

        val entry = SManga.create().apply {
            this.url = manga.url
            title = details.title.ifBlank { manga.title }
            description = details.description
            genre = details.genre
            thumbnail_url = details.thumbnailUrl ?: manga.thumbnail_url
            status = SManga.COMPLETED
            update_strategy = eu.kanade.tachiyomi.source.model.UpdateStrategy.ONLY_FETCH_ONCE
        }

        val chapterSpecs = details.chapters.ifEmpty {
            listOf(ChapterSpec(manga.url, details.chapterName))
        }

        return SMangaUpdate(
            manga = entry,
            chapters = chapterSpecs.mapIndexed { index, spec ->
                VideoSourceSupport.singleChapter(spec.url, spec.name, details.dateUpload).apply {
                    // A single-clip entry should not pretend to have an ordered chapter list.
                    if (chapterSpecs.size == 1) chapter_number = 0f else chapter_number = (index + 1).toFloat()
                }
            },
        )
    }

    // ============================== Pages ==============================

    override fun getMangaUrl(manga: SManga): String = absolute(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = absolute(chapter.url)

    /**
     * Turns the chapter page into reader pages.
     *
     * Most sources are video: one page carrying [Page.videoUrl]. A gallery source overrides
     * [parseGalleryPages] instead, and its images are returned untouched by the video branch.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = getChapterUrl(chapter)
        val document = fetchDocument(url)

        parseGalleryPages(document)?.let { images ->
            return images.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
        }

        val videoUrl = resolveVideoUrl(document, url)
        if (videoUrl.isNullOrBlank()) {
            throw VideoUnavailableException()
        }

        return listOf(
            Page(0, imageUrl = videoUrl).apply {
                this.videoUrl = videoUrl
            },
        )
    }

    /**
     * Gallery sources return every image URL of the page, in reading order.
     *
     * Returning null (the default) means "this is a video source", which sends the caller down the
     * video path; returning an empty list means "a gallery with no images", which is reported as an
     * empty chapter rather than a playback error.
     */
    protected open suspend fun parseGalleryPages(document: Document): List<String>? = null

    // ============================== Helpers ==============================

    /** Makes a site-relative path absolute against the mirror currently in use. */
    protected fun absolute(href: String): String {
        val raw = href.trim()
        if (raw.isEmpty()) return ""
        // Stored entries are already relative paths; mirror rewriting keeps them on the live host.
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            return rewriteMirrorHost(raw)
        }
        return VideoSourceSupport.resolveUrl(currentBaseUrl, raw)
    }

    /** Points a mirror URL at the host currently serving traffic. */
    private fun rewriteMirrorHost(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val mirrorHosts = knownDomains.mapNotNull { it.toHttpUrlOrNull()?.host }
        if (parsed.host !in mirrorHosts) return url
        val currentHost = currentBaseUrl.substringAfter("://").substringBefore("/")
        if (parsed.host == currentHost) return url
        return url.replaceFirst("://${parsed.host}", "://$currentHost")
    }

    /**
     * Decodes a Base64 payload, tolerating both standard and URL-safe alphabets plus missing padding.
     * Several of these sites hand the playable URL over encoded.
     */
    protected fun decodeBase64(payload: String): String? {
        val cleaned = payload.trim().replace("\n", "").replace("\r", "")
        if (cleaned.isEmpty()) return null
        val normalized = cleaned.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching {
            String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** An empty page: the site answered, but with nothing to browse. */
    protected fun emptyPage(): MangasPage = MangasPage(emptyList(), false)

    companion object {
        protected const val KIND_POPULAR = "popular"
        protected const val KIND_LATEST = "latest"
    }
}

/**
 * The clip exists but the site will not hand out a playable URL.
 *
 * These sites gate playback behind a login or an ad-countdown; telling the user that is more useful
 * than a bare "source error".
 */
internal class VideoUnavailableException(
    message: String = "该视频需要登录或积分才能播放",
) : Exception(message)

/** The page was removed, so the reader should say so instead of retrying every mirror. */
internal class ContentGoneException(
    message: String = "该内容已失效（已删除或跳转到了广告页）",
) : Exception(message)

/**
 * Category selector shared by the sources.
 *
 * [labels] is what the user sees ("全部", "日韩AV", ...) and [slugs] is the matching site path or
 * query value. The first entry is conventionally "全部" and maps to a null slug, which the source
 * reads as "use the site's default feed".
 */
internal open class CategoryFilter(
    labels: Array<String>,
    private val slugs: Array<String> = arrayOf(""),
) : Filter.Select<String>("分类", labels) {

    init {
        require(slugs.size == labels.size) { "分类标签与路径数量不一致" }
    }

    /** Site slug for the selected category, or null for "全部". */
    val slug: String?
        get() = slugs.getOrNull(state)?.takeIf { it.isNotEmpty() }
}
