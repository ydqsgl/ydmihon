package eu.kanade.tachiyomi.extension.zh.ttrgw

import eu.kanade.tachiyomi.extension.zh.cdn.XorImageCdn
import eu.kanade.tachiyomi.extension.zh.common.VideoSource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * 天天热瓜网 (ttrgw.com) — a Typecho gossip site in the same family as 51吃瓜 / 每日大赛.
 *
 * The layout is a `.videoBox` card grid over `/archives/<slug>/` posts, `/home/<page>/` pagination,
 * and one DPlayer clip per post whose manifest sits in `div.dplayerVideo[data-url]` as a path
 * relative to `/h5/m3u8/`. Segment and key URLs inside the manifest are absolute, so ExoPlayer
 * needs no special handling.
 *
 * Images are served as XOR ciphertext from `webimg.rlkfus.cn` (the first 100 bytes are XOR-ed with
 * `"2019ysapp7527"`), which [XorImageCdn] decrypts transparently.
 */
class Ttrgw : VideoSource(
    knownDomains = listOf("https://ttrgw.com"),
) {

    override val name = "天天热瓜"
    override val lang = "zh"
    override val supportsLatest = true

    /**
     * The shared client plus image decryption: every cover and article image is XOR ciphertext
     * from `webimg.rlkfus.cn`, so it has to be decrypted before Coil can decode it.
     */
    private val decryptedClient: OkHttpClient by lazy {
        XorImageCdn.client(Injekt.get<NetworkHelper>().client)
    }

    override val client: OkHttpClient get() = decryptedClient

    override fun isListingHtml(html: String): Boolean =
        html.contains("videoBox") && html.contains("videoCardLink")

    /** `/` is the latest feed; pagination is the `/home/<page>/` scheme. */
    override fun listingUrl(page: Int): String =
        if (page <= 1) "$baseUrl/" else "$baseUrl/home/$page/"

    // ============================== Listing ==============================

    override fun parseListing(document: Document, page: Int): List<ListingItem> =
        TtrgwParser.parseListing(document, ::absolute).map {
            ListingItem(it.url, it.title, it.thumbnailUrl)
        }

    override fun findNextPageUrl(document: Document, currentPage: Int): String? =
        TtrgwParser.findNextPageUrl(document, ::absolute)

    // ============================== Details ==============================

    override fun parseDetails(document: Document, manga: SManga): DetailsResult {
        val details = TtrgwParser.parseDetails(document)
        return DetailsResult(
            title = details.title,
            description = details.description,
            genre = details.genre,
            thumbnailUrl = details.coverUrl,
            chapterName = "视频",
        )
    }

    // ============================== Playback ==============================

    override suspend fun resolveVideoUrl(document: Document, chapterUrl: String): String? =
        TtrgwParser.resolveVideoUrl(document, "$baseUrl/h5/m3u8/")
}
