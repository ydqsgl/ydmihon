package eu.kanade.tachiyomi.ui.reader.viewer.video

/**
 * Which kind of gesture the user is currently performing on the video surface.
 */
enum class VideoGestureMode {
    NONE,
    SEEK,
    VOLUME,
    BRIGHTNESS,
}

/**
 * Immutable snapshot of an in-progress gesture, used to draw the HUD.
 *
 * All positions are fractions (0f..1f) so the HUD does not depend on the pixel size of the surface.
 */
data class VideoGestureState(
    val mode: VideoGestureMode = VideoGestureMode.NONE,
    /**
     * Seek target in milliseconds while [mode] is [VideoGestureMode.SEEK].
     */
    val seekTargetMs: Long = 0L,
    /**
     * Current playback position when the gesture started, so the delta can be previewed.
     */
    val seekOriginMs: Long = 0L,
    /**
     * Duration of the media, 0 when unknown.
     */
    val durationMs: Long = 0L,
    /**
     * Volume (0f..1f) or brightness (0f..1f) depending on [mode].
     */
    val level: Float = 0f,
    /**
     * True when the volume is controlled by the device rather than by the player.
     */
    val levelFixed: Boolean = false,
) {
    val seekDeltaMs: Long get() = seekTargetMs - seekOriginMs

    val seekFraction: Float
        get() = if (durationMs <= 0L) 0f else (seekTargetMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
