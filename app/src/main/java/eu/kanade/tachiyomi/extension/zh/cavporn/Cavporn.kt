package eu.kanade.tachiyomi.extension.zh.cavporn

import eu.kanade.tachiyomi.extension.zh.common.CategoryFilter
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * CavPorn — a Kernel Video Sharing (KT) tube with a large Chinese-language section.
 *
 * The player is `Playerjs` wrapped around an HLS file with a signed query string; the signature is
 * valid for weeks, so the URL is used directly (see [CavpornParser.parseVideoUrl]).
 */
class Cavporn : VideoSource(
    knownDomains = listOf(
        "https://cavporn.com",
        "https://www.cavporn.com",
    ),
) {

    override val name = "CavPorn"
    override val lang = "zh"
    override val supportsLatest = true

    override fun isListingHtml(html: String): Boolean =
        html.contains("list-videos") && html.contains("/video/")

    /** `/` is a mixed "正在观看" feed that can be empty; `/newvideo/` is always populated. */
    override val probePath: String get() = "/newvideo/"

    /** `/newvideo/` is the chronological feed; the root is a mixed "正在观看" feed. */
    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/newvideo/" else "$baseUrl/newvideo/$page/"

    override fun searchUrlFor(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        return if (page <= 1) {
            "$baseUrl/search/$encoded/"
        } else {
            "$baseUrl/search/$encoded/$page/"
        }
    }

    override fun categoryUrl(category: String?, page: Int): String {
        if (category == null) return listingUrl(page)
        return if (page <= 1) {
            "$baseUrl/categories/$category/"
        } else {
            "$baseUrl/categories/$category/$page/"
        }
    }

    override fun getFilterList(): FilterList = FilterList(CategoryFilter(LABELS, SLUGS))

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        CavpornParser.parseListing(document, ::absolute).map {
            ListingItem(url = it.url, title = it.title, thumbnailUrl = it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document): String? =
        CavpornParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = CavpornParser.parseDetails(document, ::absolute)
        val genre = (details.categories + details.tags)
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = genre,
            thumbnailUrl = details.coverUrl,
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    /**
     * Most clips are members-only; the "need an account" case is reported as such instead of
     * pretending the clip is broken.
     */
    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? =
        when (val playback = CavpornParser.parsePlayback(document)) {
            is CavpornParser.Playback.Success -> playback.url
            CavpornParser.Playback.LoginRequired ->
                throw eu.kanade.tachiyomi.extension.zh.common
                    .VideoUnavailableException("该视频仅限登录会员观看（站点未公开播放地址）")
            CavpornParser.Playback.Missing -> null
        }

    companion object {
        /**
         * The slugs are opaque hashes for most categories, so they are listed verbatim alongside the
         * human-readable label the site shows.
         */
        private val SLUGS = arrayOf(
            "",
            "682fe6262e31b4109f30bd6a775da01c",
            "666f7fff3b4de654c38e6fabec755fb6",
            "a9b22ad1cae680f0cc2e12a79eed83d0",
            "e1a6bf199084505694085903e84df466",
            "709faddbeb4a85dcef4d0a989e6f9f2a",
            "3de603c8e89e3298084764a778a391be",
            "6adf2a7e4082840708e7d8334578df5a",
            "aa4efad0b2e9e3384d2c7008b0f9b09c",
            "59249f5d383e24dbbe4f6a36e2ea5932",
            "fa065c8ef4d2c0ebb3fd2af0bdbceaf2",
            "1aa23f34b2b9f221b79ae46c622a792b",
            "95c215505f5b6462e6d469038b2c3e2a",
            "836db2c24db14907e1b8b912c7df0af4",
            "b25de5ddf8783fc89a5ef36334cc7fea",
            "5c6eb38635ac4128bd103fe4f3f9d7bf",
            "fc2",
            "93bc7a48534201fc44e7f67b44953f60",
            "d26c77340302aa5179f757ad3a519b9d",
            "amateur",
            "solowork",
        )

        private val LABELS = arrayOf(
            "全部",
            "国产自拍",
            "女主播",
            "主播福利",
            "网红黑料",
            "素人投稿",
            "盗拍偷拍",
            "传媒映画",
            "高清无码",
            "高清有码",
            "中文字幕",
            "三级电影",
            "卡通动漫",
            "欧美风情",
            "エロ動画",
            "东南亚",
            "FC2",
            "無修正動画",
            "モザイク破壊",
            "Amateur",
            "Solowork",
        )
    }
}
