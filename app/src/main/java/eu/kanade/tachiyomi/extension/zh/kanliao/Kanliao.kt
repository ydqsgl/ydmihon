package eu.kanade.tachiyomi.extension.zh.kanliao

import eu.kanade.tachiyomi.network.GET
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class Kanliao : HttpSource() {

    override val name = "今日看料"
    override val baseUrl = "https://kanliao16.one"
    override val lang = "zh"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().apply {
        add("Referer", baseUrl)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.newCall(GET("$baseUrl/", headers)).awaitSuccess()
        val document = response.asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.newCall(GET("$baseUrl/category/dy/", headers)).awaitSuccess()
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

        val response = client.newCall(GET(url, headers)).awaitSuccess()
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
        val response = client.newCall(GET(getMangaUrl(manga), headers)).awaitSuccess()
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
        val response = client.newCall(GET(getChapterUrl(chapter), headers)).awaitSuccess()
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
                        // Create a page with video URL for the VideoViewer
                        val picMatch = Regex(""""pic"\s*:\s*\\?"([^\\"]+)\\?"*""").find(configJson)
                        var rawPosterUrl = picMatch?.groupValues?.get(1)
                            ?.replace("\\/", "/")
                            ?.replace("\\u002F", "/")
                            ?: ""
                        // Ensure poster URL also has a proper scheme
                        // Use video URL as imageUrl so it survives cache serialization
                        // (videoUrl is @Transient and gets lost during JSON serialize/deserialize)
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
     * Fix a URL to ensure it has a proper http:// or https:// scheme.
     * Handles protocol-relative URLs (//example.com/path) and relative paths.
     */
    private fun fixUrl(url: String): String {
        if (url.isBlank()) return ""
        // Already has a valid scheme
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        // Protocol-relative URL (e.g. //cdn.example.com/video.m3u8)
        if (url.startsWith("//")) return "https:$url"
        // Relative path - prepend base URL
        if (url.startsWith("/")) return "$baseUrl$url"
        // Fallback: treat as relative to base
        return "$baseUrl/$url"
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private val siteZone = ZoneId.of("Asia/Shanghai")
}
