package eu.kanade.tachiyomi.extension.zh.hsck

import eu.kanade.tachiyomi.extension.zh.common.CategoryFilter
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * 黄色仓库 (333.aiock.cc) — an AppleCMS site on the `stui` theme.
 *
 * Playback is deliberately obstructed: the page renders an advert tile plus a locked button whose
 * countdown only unlocks after a recorded pointer gesture, and the real m3u8 is handed out by
 * `POST /static/count.php`. That endpoint is called directly here with a synthetic gesture, so the
 * clip plays without the wait; see [resolveVideoUrl].
 */
class Hsck : VideoSource(
    knownDomains = listOf(
        "https://333.aiock.cc",
        "https://www.333.aiock.cc",
    ),
) {

    override val name = "黄色仓库"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean = HsckParser.isListing(html)

    /** The homepage is a mixed feed; page 2 onwards uses the main "日本有码" category. */
    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/vodtype/7-$page.html"

    override fun searchUrlFor(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        return if (page <= 1) {
            "$baseUrl/vodsearch/-------------.html?wd=$encoded"
        } else {
            "$baseUrl/vodsearch/$encoded----------$page---.html"
        }
    }

    override fun categoryUrl(category: String?, page: Int): String {
        val typeId = category ?: return listingUrl(page)
        return if (page <= 1) "$baseUrl/vodtype/$typeId.html" else "$baseUrl/vodtype/$typeId-$page.html"
    }

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(LABELS, SLUGS))

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        HsckParser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document): String? =
        HsckParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = HsckParser.parseDetails(document, ::absolute)
        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = details.keywords.takeIf { it.isNotEmpty() }?.joinToString(", "),
            thumbnailUrl = details.coverUrl,
            dateUpload = VideoSourceSupport.parseDate(details.dateText),
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    /**
     * Exchanges the page's anti-bot token for the m3u8.
     *
     * The gesture fields (`g`, `x`, `y`, `dt`) are what the page's own handler would send after a
     * tap; the endpoint validates their presence rather than the pointer's real origin.
     */
    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? {
        val playToken = HsckParser.parsePlayToken(document) ?: return null

        val body = okhttp3.FormBody.Builder()
            .add("id", playToken.id)
            .add("sid", playToken.sid)
            .add("nid", playToken.nid)
            .add("tk", playToken.token)
            .add("g", "1")
            .add("x", "320")
            .add("y", "480")
            .add("dt", "1200")
            .add("sw", "412")
            .add("sh", "915")
            .add("tz", "-480")
            .add("t", System.currentTimeMillis().toString())
            .build()

        val request = okhttp3.Request.Builder()
            .url("$baseUrl/static/count.php")
            .post(body)
            .headers(
                requestHeaders().newBuilder()
                    .set("X-Requested-With", "XMLHttpRequest")
                    .set("Referer", chapterUrl)
                    .build(),
            )
            .build()

        val text = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        return HsckParser.parsePlayResponse(text)
    }

    companion object {
        private val SLUGS = arrayOf(
            "",
            "1",
            "2",
            "3",
            "4",
            "8",
            "9",
            "10",
            "7",
            "26",
            "15",
            "21",
            "22",
        )

        private val LABELS = arrayOf(
            "全部",
            "日韩AV",
            "国产系列",
            "欧美",
            "成人动漫",
            "无码中文字幕",
            "有码中文字幕",
            "日本无码",
            "日本有码",
            "骑兵破解",
            "国产视频",
            "欧美高清",
            "动漫剧情",
        )
    }
}
