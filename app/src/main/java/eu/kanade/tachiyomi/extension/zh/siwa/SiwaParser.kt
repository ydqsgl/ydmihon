package eu.kanade.tachiyomi.extension.zh.siwa

import eu.kanade.tachiyomi.extension.zh.common.VideoSourceSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Parsing for 丝袜天堂 (siwa.cam), kept free of networking so it can be tested on saved pages.
 *
 * The site itself is ordinary Laravel markup, but it does not host its own video: the details page
 * only carries a `loadPlayer(<element>, "<embed url>", "iframe")` call, and the embed is a small
 * React player whose stream URL is handed out as an **AES-256-GCM** blob by
 * `GET /api/videos/<code>`.
 *
 * That blob is not a fixed key: the JSON ships ~30 `key_parts` of which exactly two are real, and
 * `version` says which — part `v` and part `31 - v`. Concatenating those two (they decode to 16
 * bytes each) yields the 32-byte AES-256 key. Every other part decodes to 24 bytes and is a decoy
 * that would make the key length invalid, which is what makes the scheme fail loudly if the wrong
 * pair is picked.
 */
internal object SiwaParser {

    data class ListingItem(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
    )

    data class Details(
        val title: String,
        val genre: String?,
        val thumbnailUrl: String?,
        val dateUpload: Long,
    )

    /** What the details page says about where the clip lives. */
    data class Player(
        val url: String,
        val kind: String,
    )

    /** A stream URL recovered from the player, or an explanation of why there is none. */
    sealed interface Stream {
        data class Success(val url: String) : Stream

        data class Failure(val message: String) : Stream
    }

    fun isListing(html: String): Boolean =
        html.contains("/videos/") && !html.contains("loadPlayer(")

    // ============================== Listing ==============================

    fun parseListing(document: Document, absoluteUrl: (String) -> String): List<ListingItem> {
        return document.select("div.grid > div").mapNotNull { card ->
            val anchor = card.selectFirst("a[href*=\"/videos/\"]") ?: return@mapNotNull null
            val href = anchor.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null

            val image = card.selectFirst("img")
            val title = sequenceOf(
                card.selectFirst("h2")?.text(),
                image?.attr("alt"),
                anchor.attr("title"),
            ).map { it?.trim().orEmpty() }
                .firstOrNull { it.isNotEmpty() }
                ?: return@mapNotNull null

            ListingItem(
                url = absoluteUrl(href),
                title = title,
                thumbnailUrl = image?.attr("src")?.trim()?.takeIf { it.startsWith("http") },
            )
        }.distinctBy { it.url }
    }

    /** Laravel's pager renders the forward link with `rel="next"` and the page number in `?page=`. */
    fun findNextPageUrl(document: Document, absoluteUrl: (String) -> String): String? {
        val anchor = document.selectFirst("a[rel=next][href]") ?: return null
        val href = anchor.absUrl("href").ifEmpty { anchor.attr("href") }.trim()
        return href.takeIf { it.isNotEmpty() }?.let(absoluteUrl)
    }

    // ============================== Details ==============================

    fun parseDetails(document: Document): Details {
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.title().substringBefore('|').trim()

        val genre = document.select("a[href*=\"/tags/\"]")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }

        val jsonLd = jsonLd(document)

        return Details(
            title = title,
            genre = genre,
            thumbnailUrl = stringField(jsonLd, "thumbnailUrl"),
            dateUpload = VideoSourceSupport.parseDate(stringField(jsonLd, "uploadDate")),
        )
    }

    /** Reads the `loadPlayer(element, "url", "kind")` call the details page boots the player with. */
    fun parsePlayer(document: Document): Player? {
        val match = PLAYER_CALL.find(document.outerHtml()) ?: return null
        val url = match.groupValues[1].trim()
        if (url.isEmpty()) return null
        return Player(url = url, kind = match.groupValues[2].trim().ifEmpty { "iframe" })
    }

    /** The clip code inside an embed URL such as `https://host/e/vz9c1yu56g53`. */
    fun parseEmbedCode(embedUrl: String): String? {
        val path = embedUrl.substringAfter("://", embedUrl).substringAfter('/', "")
        val segment = path.substringAfterLast('/')
        return segment.takeIf { it.isNotEmpty() && !it.contains('?') }
    }

    // ============================== Playback ==============================

    /**
     * Decrypts the `playback` object of `GET /api/videos/<code>`.
     *
     * The payload is `{"algorithm":"AES-256-GCM","iv":"…","payload":"…","key_parts":[…],
     * "version":14}` and the plaintext is `{"sources":[{"url":"…master.m3u8"}]}`.
     */
    fun decryptPlayback(body: String): Stream {
        val playback = runCatching {
            json.parseToJsonElement(body).jsonObject["playback"]?.jsonObject
        }.getOrNull() ?: return Stream.Failure("播放信息解析失败")

        val algorithm = (playback["algorithm"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (!algorithm.contains("GCM", ignoreCase = true)) {
            return Stream.Failure("不支持的加密方式：$algorithm")
        }

        val version = (playback["version"] as? JsonPrimitive)?.intOrNull
            ?: return Stream.Failure("播放信息缺少版本号")
        val parts = (playback["key_parts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        if (parts.isEmpty()) return Stream.Failure("播放信息缺少密钥")

        // version v selects part v and part 31-v (1-based); anything else is a decoy.
        val key = runCatching {
            val first = parts[version - 1]
            val second = parts[31 - version - 1]
            decodeBase64Url(first) + decodeBase64Url(second)
        }.getOrNull() ?: return Stream.Failure("播放密钥无效")

        val iv = decodeBase64Url((playback["iv"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        val payload = decodeBase64Url((playback["payload"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        if (iv.size < 12 || payload.isEmpty()) return Stream.Failure("播放数据不完整")

        val plaintext = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(payload).toString(Charsets.UTF_8)
        }.getOrNull() ?: return Stream.Failure("播放数据解密失败")

        val url = runCatching {
            val root = json.parseToJsonElement(plaintext).jsonObject
            root["sources"]?.jsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("url")?.let { u -> (u as? JsonPrimitive)?.contentOrNull } }
                ?.firstOrNull { it.isNotBlank() }
        }.getOrNull()

        return url?.let { Stream.Success(it) } ?: Stream.Failure("播放地址为空")
    }

    /**
     * Picks a stream out of the `links = {…}` object a VidHide-style embed page ships.
     *
     * The page's own player tries `hls4 || hls3 || hls2`, but `hls3` is served as `.txt` — an HLS
     * playlist under a name ExoPlayer will not recognise — so a real `.m3u8` is preferred here.
     */
    fun parseEmbedLinks(html: String): String? {
        val block = LINKS_BLOCK.find(html)?.groupValues?.get(1) ?: return null
        val urls = LINK_URL.findAll(block).map { it.groupValues[1] }.toList()
        return urls.firstOrNull { it.contains(".m3u8") } ?: urls.firstOrNull()
    }

    private fun decodeBase64Url(value: String): ByteArray {
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.getDecoder().decode(padded)
    }

    private fun jsonLd(document: Document): String =
        document.select("script[type=application/ld+json]").joinToString("\n") { it.data() }

    private fun stringField(jsonLd: String, name: String): String? =
        Regex(""""$name"\s*:\s*"([^"]*)"""").find(jsonLd)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val PLAYER_CALL = Regex("""loadPlayer\s*\([^,]+,\s*"([^"]+)"\s*,\s*"([^"]*)"""")
    private val LINKS_BLOCK = Regex("""links\s*=\s*\{([^}]*)}""")
    private val LINK_URL = Regex(""""([^"]+?)"""")
}
