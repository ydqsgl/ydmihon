package eu.kanade.tachiyomi.ui.reader.viewer.video

import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.getSystemService
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hosts the ExoPlayer surface and the custom [VideoPlayerOverlay].
 *
 * Controls live in Compose instead of [PlayerView]'s controller because the reader needs gestures
 * the stock controller does not offer: horizontal drag to seek, vertical drag for volume and
 * brightness on either half of the screen, and a landscape fullscreen mode.
 */
@Composable
fun VideoPlayerHost(
    videoUrl: String?,
    title: String,
    refererUrl: String?,
    activity: ReaderActivity,
    retryKey: Int,
    onPlaybackError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var controls by remember { mutableStateOf(VideoControlsState(title = title)) }
    var gesture by remember { mutableStateOf(VideoGestureState()) }
    var isFullscreen by remember { mutableStateOf(false) }

    val audioManager = remember(context) { context.getSystemService<AudioManager>() }
    val maxVolume = remember(audioManager) {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
    }

    val player = remember(context) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(
                buildMap {
                    put("User-Agent", USER_AGENT)
                    put("Accept", "*/*")
                    refererUrl?.toHttpUrlOrNull()?.let { put("Referer", "${it.scheme}://${it.host}/") }
                },
            )
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // The activity consumes a system back press by leaving fullscreen, so the HUD follows it.
    LaunchedEffect(activity.videoFullscreen) {
        if (!activity.videoFullscreen && isFullscreen) {
            isFullscreen = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Leaving the player must not leave the reader stuck in landscape.
            if (isFullscreen) activity.applyVideoFullscreen(false)
        }
    }

    // Playback state -> HUD state.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                controls = controls.copy(isPlaying = playing)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                controls = controls.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = player.duration.takeIf { it > 0L } ?: controls.durationMs,
                )
            }

            override fun onVolumeChanged(volume: Float) {
                controls = controls.copy(volume = volume, isMuted = volume == 0f)
            }

            override fun onPlayerError(error: PlaybackException) {
                controls = controls.copy(isBuffering = false, isPlaying = false)
                onPlaybackError(describePlaybackError(error))
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Load (and reload on retry) the media item.
    LaunchedEffect(videoUrl, retryKey) {
        if (videoUrl.isNullOrBlank()) return@LaunchedEffect
        controls = controls.copy(isBuffering = true, positionMs = 0L, durationMs = 0L)
        runCatching {
            player.setMediaItem(buildMediaItem(videoUrl))
            player.prepare()
            player.playWhenReady = true
        }.onFailure {
            onPlaybackError(it.message ?: "无法加载视频")
        }
    }

    // Position ticker: only while playing, so a paused player does not wake the device.
    LaunchedEffect(player, controls.isPlaying) {
        while (true) {
            if (videoUrl != null) {
                val duration = player.duration.takeIf { it > 0L } ?: 0L
                controls = controls.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = if (duration > 0L) duration else controls.durationMs,
                    bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
                    volume = player.volume,
                )
            }
            delay(if (controls.isPlaying) 500L else 1_000L)
        }
    }

    // Auto-hide the chrome three seconds after playback starts, like a normal video player.
    LaunchedEffect(controls.isPlaying, controls.controlsVisible, gesture.mode) {
        if (controls.isPlaying && controls.controlsVisible && gesture.mode == VideoGestureMode.NONE) {
            delay(3_500L)
            if (player.isPlaying) {
                controls = controls.copy(controlsVisible = false)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Transparent gesture layer, below the chrome so buttons and the slider win the touch.
        // The drag detector runs first and consumes a real drag; `detectTapGestures` then sees the
        // consumed movement and drops the gesture, so a drag never also toggles the chrome. It also
        // owns the double-tap timing, which is why the seek step is not handled by hand.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(videoUrl) {
                    var accumulatedX = 0f
                    var accumulatedY = 0f
                    var originPosition = 0L
                    var targetPosition = 0L
                    var startLevel = 0f
                    var chosenMode = VideoGestureMode.NONE

                    detectDragGestures(
                        onDragStart = { offset ->
                            accumulatedX = 0f
                            accumulatedY = 0f
                            chosenMode = VideoGestureMode.NONE
                            originPosition = player.currentPosition.coerceAtLeast(0L)
                            targetPosition = originPosition
                            startLevel = if (offset.x > size.width / 2f) {
                                currentVolumeLevel(audioManager, maxVolume, player.volume)
                            } else {
                                currentBrightness(activity)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulatedX += dragAmount.x
                            accumulatedY += dragAmount.y

                            // Lock the axis once, from the travel so far, so a slightly diagonal
                            // swipe does not flip between seeking and volume mid-gesture.
                            if (chosenMode == VideoGestureMode.NONE) {
                                if (abs(accumulatedX) < MIN_LOCK_TRAVEL_PX && abs(accumulatedY) < MIN_LOCK_TRAVEL_PX) {
                                    return@detectDragGestures
                                }
                                chosenMode = if (abs(accumulatedX) > abs(accumulatedY)) {
                                    VideoGestureMode.SEEK
                                } else if (change.position.x > size.width / 2f) {
                                    VideoGestureMode.VOLUME
                                } else {
                                    VideoGestureMode.BRIGHTNESS
                                }
                                gesture = VideoGestureState(
                                    mode = chosenMode,
                                    seekTargetMs = originPosition,
                                    seekOriginMs = originPosition,
                                    durationMs = player.duration.takeIf { it > 0L } ?: 0L,
                                    level = startLevel,
                                )
                                // Drop the travel spent choosing the axis, otherwise the first
                                // frames of the swipe jump the position.
                                accumulatedX = 0f
                                accumulatedY = 0f
                                return@detectDragGestures
                            }

                            when (chosenMode) {
                                VideoGestureMode.SEEK -> {
                                    val duration = player.duration.takeIf { it > 0L } ?: 0L
                                    // A full-width swipe covers a fixed slice of the media, so
                                    // the gesture feels the same on a 20 s clip and a 2 h video.
                                    val sweepMs = duration.toFloat().takeIf { it > 0f }
                                        ?.coerceIn(MIN_SEEK_SWEEP_MS, MAX_SEEK_SWEEP_MS)
                                        ?: FALLBACK_SEEK_SWEEP_MS
                                    val delta = (accumulatedX / size.width * sweepMs).toLong()
                                    targetPosition = (originPosition + delta)
                                        .coerceIn(0L, duration.coerceAtLeast(0L))
                                    gesture = gesture.copy(
                                        seekTargetMs = targetPosition,
                                        durationMs = duration,
                                        level = if (duration > 0L) {
                                            targetPosition.toFloat() / duration
                                        } else {
                                            0f
                                        },
                                    )
                                }
                                VideoGestureMode.VOLUME, VideoGestureMode.BRIGHTNESS -> {
                                    val level = (startLevel - accumulatedY / size.height).coerceIn(0f, 1f)
                                    if (chosenMode == VideoGestureMode.VOLUME) {
                                        setVolumeLevel(audioManager, maxVolume, player, level)
                                    } else {
                                        setBrightness(activity, level)
                                    }
                                    gesture = gesture.copy(level = level)
                                }
                                VideoGestureMode.NONE -> Unit
                            }
                        },
                        onDragEnd = {
                            if (chosenMode == VideoGestureMode.SEEK && player.duration > 0L) {
                                player.seekTo(targetPosition)
                            }
                            gesture = VideoGestureState()
                        },
                        onDragCancel = { gesture = VideoGestureState() },
                    )
                }
                .pointerInput(videoUrl) {
                    detectTapGestures(
                        onTap = {
                            controls = controls.copy(controlsVisible = !controls.controlsVisible)
                        },
                        onDoubleTap = { offset ->
                            val half = size.width / 2f
                            val delta = if (offset.x < half) -SEEK_STEP_MS else SEEK_STEP_MS
                            seekBy(player, delta)
                            controls = controls.copy(
                                controlsVisible = true,
                                positionMs = player.currentPosition.coerceAtLeast(0L),
                            )
                        },
                    )
                },
        )

        VideoPlayerOverlay(
            state = controls.copy(
                isFullscreen = isFullscreen,
                title = title,
            ),
            gesture = gesture,
            onTogglePlayPause = {
                if (player.isPlaying) player.pause() else player.play()
                controls = controls.copy(controlsVisible = true, isPlaying = player.isPlaying)
            },
            onSeekTo = { position ->
                if (player.duration > 0L) {
                    player.seekTo(position.coerceIn(0L, player.duration))
                    controls = controls.copy(positionMs = position, controlsVisible = true)
                }
            },
            onSeekBy = { delta ->
                seekBy(player, delta)
                controls = controls.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    controlsVisible = true,
                )
            },
            onToggleFullscreen = {
                val next = !isFullscreen
                isFullscreen = next
                activity.applyVideoFullscreen(next)
                controls = controls.copy(controlsVisible = true)
            },
            onCycleSpeed = {
                val next = nextSpeed(player.playbackParameters.speed)
                player.playbackParameters = player.playbackParameters.withSpeed(next)
                controls = controls.copy(speed = next)
            },
            onToggleMute = {
                val muted = player.volume > 0f
                player.volume = if (muted) 0f else 1f
                controls = controls.copy(isMuted = muted, volume = if (muted) 0f else 1f)
            },
            onOpenReaderMenu = { activity.toggleMenu() },
        )
    }
}

private fun seekBy(player: ExoPlayer, deltaMs: Long) {
    val duration = player.duration.takeIf { it > 0L } ?: return
    player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, duration))
}

private fun currentVolumeLevel(audioManager: AudioManager?, maxVolume: Int, fallback: Float): Float {
    if (audioManager == null) return fallback
    return runCatching {
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
    }.getOrDefault(fallback)
}

private fun setVolumeLevel(
    audioManager: AudioManager?,
    maxVolume: Int,
    player: ExoPlayer,
    level: Float,
) {
    val handled = if (audioManager != null) {
        runCatching {
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (level * maxVolume).roundToInt().coerceIn(0, maxVolume),
                0,
            )
            true
        }.getOrDefault(false)
    } else {
        false
    }
    // Devices that refuse the stream change (DND, fixed volume) still get player-level volume.
    if (!handled) player.volume = level
}

private fun currentBrightness(activity: ReaderActivity): Float {
    val attrs = activity.window.attributes
    if (attrs.screenBrightness >= 0f) return attrs.screenBrightness.coerceIn(0f, 1f)
    val system = runCatching {
        android.provider.Settings.System.getInt(
            activity.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
        ) / 255f
    }.getOrDefault(1f)
    return system.coerceIn(0f, 1f)
}

private fun setBrightness(activity: ReaderActivity, level: Float) {
    activity.window.attributes = activity.window.attributes.apply {
        screenBrightness = level.coerceIn(0.01f, 1f)
    }
}

private val SPEED_STEPS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun nextSpeed(current: Float): Float {
    val index = SPEED_STEPS.indexOfFirst { abs(it - current) < 0.01f }
    return if (index == -1) 1f else SPEED_STEPS[(index + 1) % SPEED_STEPS.size]
}

/**
 * Builds the media item, hinting the MIME type when the extension makes it obvious so
 * ExoPlayer doesn't have to guess (some CDN URLs carry no useful suffix).
 */
private fun buildMediaItem(videoUrl: String): MediaItem {
    val path = videoUrl.substringBefore('?').lowercase()
    val mimeType = when {
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        path.endsWith(".mkv") -> "video/x-matroska"
        else -> null
    }

    return MediaItem.Builder()
        .setUri(videoUrl)
        .apply { mimeType?.let { setMimeType(it) } }
        .build()
}

/**
 * Turns a [PlaybackException] into something actionable instead of the bare "Source error".
 */
private fun describePlaybackError(error: PlaybackException): String {
    val hint = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "视频地址无效或已过期（HTTP 错误）"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "视频文件不存在（404）"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败，请检查网络"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "视频地址返回的不是视频内容"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "视频格式无法解析"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "播放列表解析失败"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "设备解码失败，可尝试用其他播放器打开"
        else -> error.errorCodeName
    }
    return "播放失败：$hint"
}

private const val SEEK_STEP_MS = 10_000L
private const val MIN_LOCK_TRAVEL_PX = 8f
private const val MIN_SEEK_SWEEP_MS = 30_000f
private const val MAX_SEEK_SWEEP_MS = 300_000f
private const val FALLBACK_SEEK_SWEEP_MS = 120_000f

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"
