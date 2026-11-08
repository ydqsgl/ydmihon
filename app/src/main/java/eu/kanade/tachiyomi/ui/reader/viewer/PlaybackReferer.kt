package eu.kanade.tachiyomi.ui.reader.viewer

/**
 * Referer rules for the player, kept as pure functions so they can be tested without a device.
 *
 * Most sources accept the chapter's own origin. 123AV is the exception: its embed host serves an
 * HLS playlist on a rotating `jl<N>.<domain>` CDN, and both the playlist and every segment answer
 * 403 unless the request carries the embed origin as its Referer.
 */
internal object PlaybackReferer {

    /** The embed origin 123AV's playlist CDN expects. */
    const val TT123AV_EMBED_ORIGIN = "https://sadie-shop.site/"

    /**
     * The playlist CDN is served under a rotating host: `jl77.piper-store.site` and
     * `jl77.calmquietmind.site` both appear for the same clip, so the numbered label is the stable
     * part to match.
     */
    private val STREAM_CDN_HOST = Regex("""^jl\d+\.[a-z0-9-]+\.[a-z]{2,}$""")

    /**
     * Referer for a clip, or null when the chapter URL is unknown.
     *
     * [chapterHost] is the source site's host and [videoHost] the clip's host, so the 123AV rule
     * cannot leak onto another source that happens to use a `jl77.` CDN.
     */
    fun refererFor(chapterHost: String?, videoHost: String?): String? {
        val chapterOrigin = chapterHost?.takeIf { it.isNotBlank() }?.let { "https://$it/" }
        val from123Av = chapterHost?.endsWith("123av.com") == true
        val onRotatingCdn = videoHost != null && STREAM_CDN_HOST.matches(videoHost)
        return if (from123Av && onRotatingCdn) TT123AV_EMBED_ORIGIN else chapterOrigin
    }
}
