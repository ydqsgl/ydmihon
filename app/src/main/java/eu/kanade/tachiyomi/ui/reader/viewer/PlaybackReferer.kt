package eu.kanade.tachiyomi.ui.reader.viewer

/**
 * Referer rules for the player, kept as pure functions so they can be tested without a device.
 *
 * Most sources accept the chapter's own origin. 123AV is the exception: its embed host hands out
 * an HLS playlist on a third-party CDN, and both the playlist and every segment answer 403 unless
 * the request carries the embed origin as its Referer.
 *
 * The CDN hostname is **not stable**. It rotates per video and per region, and has been observed as
 * `jl77.piper-store.site`, `jl77.calmquietmind.site` and `e625.highpurevibe.site` — so matching a
 * hostname shape cannot work: any pattern derived from one observation misses the next clip. The
 * discriminator that is actually stable is the *source*: only 123AV's clips need this Referer, and
 * no other source in the app is served from a host of that kind.
 */
internal object PlaybackReferer {

    /** The embed origin 123AV's playlist CDN expects. */
    const val TT123AV_EMBED_ORIGIN = "https://sadie-shop.site/"

    /** Hosts that belong to the 123AV catalogue itself; these keep the ordinary chapter origin. */
    private val TT123AV_SITE_HOSTS = listOf("123av.com", "icdn.123av.me")

    /** Third-party embed host whose `/e/<id>` and `/stream` endpoints front the clips. */
    private const val TT123AV_EMBED_HOST = "sadie-shop.site"

    /**
     * Referer for a clip, or null when the chapter URL is unknown.
     *
     * [chapterHost] is the source site's host and [videoHost] the clip's host. The override applies
     * only when the chapter itself lives on 123AV and the clip does not: the catalogue's own hosts
     * (the site and its image CDN) still expect the catalogue origin.
     */
    fun refererFor(chapterHost: String?, videoHost: String?): String? {
        val chapterHostClean = chapterHost?.takeIf { it.isNotBlank() }
        val chapterOrigin = chapterHostClean?.let { "https://$it/" } ?: return null

        val from123Av = chapterHostClean.endsWith("123av.com")
        if (!from123Av) return chapterOrigin

        val onCatalogueHost = videoHost == null ||
            TT123AV_SITE_HOSTS.any { host -> videoHost == host || videoHost.endsWith(".$host") }
        return if (onCatalogueHost) chapterOrigin else TT123AV_EMBED_ORIGIN
    }

    /** The embed host that fronts 123AV's clips; exposed for callers that need to recognise it. */
    fun isEmbedHost(videoHost: String?): Boolean =
        videoHost == TT123AV_EMBED_HOST || videoHost?.endsWith(".$TT123AV_EMBED_HOST") == true
}
