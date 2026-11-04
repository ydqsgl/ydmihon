package eu.kanade.tachiyomi.extension.zh.tangxin

import eu.kanade.tachiyomi.extension.zh.common.CategoryFilter
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document

/**
 * 糖心Vlog — a static Astro site with no server-side pagination.
 *
 * The homepage renders a fixed set of cards and `?page=2` returns the identical page, so the site's
 * own pager cannot be relied on. Instead the pre-built `search-index.json` (every clip, ~10k rows)
 * is used for paging and search, which is also how the site's own search box works.
 *
 * The catalogue index is fetched once and cached; it is a single ~2 MB document, and re-fetching it
 * on every page turn would make browsing unusable.
 */
class Tangxin : VideoSource(
    knownDomains = listOf(
        "https://tangxinvlog.app",
        "https://www.tangxinvlog.app",
    ),
) {

    override val name = "糖心Vlog"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean =
        html.contains("article") && html.contains("/v/")

    override fun listingUrl(page: Int): String = "$baseUrl/"

    override fun getFilterList(): eu.kanade.tachiyomi.source.model.FilterList =
        eu.kanade.tachiyomi.source.model.FilterList(CategoryFilter(LABELS, SLUGS))

    /** Search is answered from the local index, so no URL is built here. */
    override fun searchUrlFor(keyword: String, page: Int): String = "$baseUrl/"

    // ============================== Index cache ==============================

    @Volatile
    private var cachedIndex: List<TangxinParser.IndexEntry>? = null

    private val indexLock = Mutex()

    /**
     * Loads the catalogue index, from cache when possible.
     *
     * A failed fetch is not cached, so a transient network error does not poison the source for the
     * rest of the session.
     */
    private suspend fun index(): List<TangxinParser.IndexEntry> {
        cachedIndex?.let { return it }
        return indexLock.withLock {
            cachedIndex?.let { return@withLock it }
            val url = indexUrl()
            val body = client.newCall(GET(url, requestHeaders())).await().use { response ->
                if (!response.isSuccessful) return@withLock emptyList()
                response.body.string()
            }
            TangxinParser.parseIndex(body).also { if (it.isNotEmpty()) cachedIndex = it }
        }
    }

    /**
     * Reads the index URL from the search page.
     *
     * It carries a cache-busting version query (`?v=...`), so it is scraped rather than hard-coded.
     */
    private suspend fun indexUrl(): String {
        val fallback = "$baseUrl/search-index.json"
        return runCatching {
            val html = client.newCall(GET("$baseUrl/search/", requestHeaders()))
                .await().use { it.body.string() }
            INDEX_ATTR_REGEX.find(html)?.groupValues?.get(1)?.let { absolute(it) } ?: fallback
        }.getOrDefault(fallback)
    }

    // ============================== Listing ==============================

    /**
     * The homepage cards, or a slice of the catalogue index for later pages.
     *
     * Page 1 keeps the site's own editorial ordering; deeper pages come from the index so that
     * paging actually advances instead of repeating the homepage.
     */
    override suspend fun getPopularManga(page: Int): MangasPage = listing(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing(page)

    private suspend fun listing(page: Int): MangasPage {
        // The homepage is the site's own ordering and is worth keeping for page 1, but its cards
        // are also the first entries of the catalogue index. Later pages therefore continue *after*
        // those cards, otherwise page 2 would repeat most of page 1.
        if (page <= 1) {
            val document = fetchDocument("$baseUrl/")
            val items = TangxinParser.parseListing(document, ::absolute)
            if (items.isNotEmpty()) {
                homepageIds = items.mapNotNull { item ->
                    // Card URLs look like ".../v/<id>/", so the id is the path segment before the
                    // trailing slash.
                    item.url.trimEnd('/').substringAfterLast('/').ifBlank { null }
                }.toSet()
                return MangasPage(items.map { it.toManga() }, hasNextPage = true)
            }
        }

        val entries = index()
        if (entries.isEmpty()) return MangasPage(emptyList(), false)

        // Skip whatever the homepage already showed, then page through the rest.
        val seen = homepageIds
        val remaining = if (seen.isEmpty()) entries else entries.filterNot { it.id in seen }

        val from = (page - 2).coerceAtLeast(0) * PAGE_SIZE
        if (from >= remaining.size) return MangasPage(emptyList(), false)
        val slice = remaining.drop(from).take(PAGE_SIZE)

        return MangasPage(slice.map { it.toManga() }, from + slice.size < remaining.size)
    }

    /** Ids already shown on the homepage, so index paging starts after them. */
    @Volatile
    private var homepageIds: Set<String> = emptySet()

    /**
     * Search, or a tag listing when a category was picked instead.
     *
     * Tags are served as ordinary listing pages, but those pages have no pagination, so the local
     * index is used for anything past page 1 there as well.
     */
    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: eu.kanade.tachiyomi.source.model.FilterList,
    ): MangasPage {
        val keyword = query.trim()
        val tag = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.slug

        if (keyword.isEmpty() && tag != null) {
            return tagListing(tag, page)
        }
        if (keyword.isEmpty()) return listing(page)

        val matches = TangxinParser.search(index(), keyword)
        val from = (page - 1) * PAGE_SIZE
        if (from >= matches.size) return MangasPage(emptyList(), false)
        val slice = matches.drop(from).take(PAGE_SIZE)

        return MangasPage(slice.map { it.toManga() }, from + slice.size < matches.size)
    }

    /**
     * Index rows carry no cover URL, but every clip's artwork is derivable from its id — the site
     * builds the same path for its own cards.
     */
    private fun TangxinParser.IndexEntry.toManga(): SManga = SManga.create().apply {
        setUrlWithoutDomain(TangxinParser.indexPath(id))
        title = this@toManga.title
        thumbnail_url = "$COVER_BASE$id/cover.jpg"
        initialized = true
    }

    /**
     * Tag pages: the first page comes from the site, deeper pages are filtered out of the index.
     *
     * A tag's own page shows ~24 clips and offers no "next", so the index (which carries each
     * clip's tags) is the only way to keep going.
     */
    private suspend fun tagListing(tag: String, page: Int): MangasPage {
        if (page <= 1) {
            val document = fetchDocument("$baseUrl/tag/$tag/")
            val items = TangxinParser.parseListing(document, ::absolute)
            if (items.isNotEmpty()) return MangasPage(items.map { it.toManga() }, true)
        }

        val entries = index()
        val tagged = entries.filter { entry -> entry.tags.any { it.equals(tag, ignoreCase = true) } }
        if (tagged.isEmpty()) return MangasPage(emptyList(), false)

        val from = (page - 1) * PAGE_SIZE
        if (from >= tagged.size) return MangasPage(emptyList(), false)
        val slice = tagged.drop(from).take(PAGE_SIZE)
        return MangasPage(slice.map { it.toManga() }, from + slice.size < tagged.size)
    }

    private fun TangxinParser.ListingItem.toManga(): SManga = SManga.create().apply {
        // `this@toManga.url`, not `url`: inside `apply` the bare name resolves to SManga's own
        // lateinit `url`, which is still uninitialised here and throws on read.
        setUrlWithoutDomain(TangxinParser.relativePath(this@toManga.url, baseUrl))
        title = this@toManga.title
        thumbnail_url = this@toManga.thumbnailUrl
        initialized = true
    }

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = TangxinParser.parseDetails(document, ::absolute)
        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = details.tags.takeIf { it.isNotEmpty() }?.joinToString(", "),
            thumbnailUrl = details.coverUrl,
            dateUpload = eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport
                .parseDate(details.uploadDate),
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? =
        TangxinParser.parseVideoUrl(document)

    companion object {
        /** A small curated set; the site has 200+ tags, which is unusable as a dropdown. */
        private val SLUGS = arrayOf(
            "",
            "%E4%B8%9D%E8%A2%9C",
            "%E4%B8%AD%E5%87%BA",
            "%E5%8F%A3%E4%BA%A4",
            "%E4%B9%B1%E4%BC%A6",
            "%E4%B8%BB%E6%92%AD",
            "%E4%B8%AD%E6%96%87%E5%AD%97%E5%B9%95",
            "%E4%B8%9D%E8%A2%9C%E7%BE%8E%E8%85%BF",
            "%E4%B8%80%E5%AD%97%E9%A9%AC",
        )

        private val LABELS = arrayOf(
            "全部",
            "丝袜",
            "中出",
            "口交",
            "乱伦",
            "主播",
            "中文字幕",
            "丝袜美腿",
            "一字马",
        )

        private const val PAGE_SIZE = 24
        private const val COVER_BASE = "https://t.5gcdn.xyz/videos/"
        private val INDEX_ATTR_REGEX = Regex("""data-index="([^"]+)"""")
    }
}
