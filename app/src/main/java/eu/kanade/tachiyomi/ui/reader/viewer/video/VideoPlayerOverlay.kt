package eu.kanade.tachiyomi.ui.reader.viewer.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Brightness6
import mihon.icons.materialsymbols.rounded.Fullscreen
import mihon.icons.materialsymbols.rounded.Forward10
import mihon.icons.materialsymbols.rounded.FullscreenExit
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Pause
import mihon.icons.materialsymbols.rounded.PlayArrow
import mihon.icons.materialsymbols.rounded.Replay10
import mihon.icons.materialsymbols.rounded.Speed
import mihon.icons.materialsymbols.rounded.VolumeOff
import mihon.icons.materialsymbols.rounded.VolumeUp
import kotlin.math.abs

/**
 * State that drives the player HUD. Owned by [VideoPlayerHost] so the controls survive recomposition.
 */
data class VideoControlsState(
    val title: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val isBuffering: Boolean = false,
    val isFullscreen: Boolean = false,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val isMuted: Boolean = false,
    val controlsVisible: Boolean = true,
)

/**
 * Full player chrome: centre transport controls plus the bottom seek bar.
 *
 * Tapping the surface toggles [VideoControlsState.controlsVisible] instead of the menu, so the
 * reader menu stays reachable through the reader's own navigation regions.
 */
@Composable
fun VideoPlayerOverlay(
    state: VideoControlsState,
    gesture: VideoGestureState,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onToggleFullscreen: () -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenReaderMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.controlsVisible || gesture.mode != VideoGestureMode.NONE

    Box(modifier = modifier.fillMaxSize()) {
        if (state.isBuffering && !state.isPlaying) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        if (visible) {
            // Top and bottom scrims keep the text readable over bright video.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                        ),
                    ),
            )

            VideoTopBar(
                title = state.title,
                onOpenReaderMenu = onOpenReaderMenu,
                onToggleMute = onToggleMute,
                isMuted = state.isMuted,
                onCycleSpeed = onCycleSpeed,
                speed = state.speed,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            VideoTransportRow(
                isPlaying = state.isPlaying,
                onTogglePlayPause = onTogglePlayPause,
                onSeekBy = onSeekBy,
                modifier = Modifier.align(Alignment.Center),
            )

            VideoBottomBar(
                state = state,
                onSeekTo = onSeekTo,
                onToggleFullscreen = onToggleFullscreen,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (gesture.mode == VideoGestureMode.SEEK) {
            SeekPreview(
                gesture = gesture,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (gesture.mode == VideoGestureMode.VOLUME || gesture.mode == VideoGestureMode.BRIGHTNESS) {
            LevelPreview(
                gesture = gesture,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun VideoTopBar(
    title: String,
    onOpenReaderMenu: () -> Unit,
    onToggleMute: () -> Unit,
    isMuted: Boolean,
    onCycleSpeed: () -> Unit,
    speed: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 6.dp, end = 4.dp),
        )
        PlayerIconButton(onClick = onCycleSpeed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Speed,
                    contentDescription = "倍速",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = formatSpeed(speed),
                    color = Color.White,
                    fontSize = 9.sp,
                )
            }
        }
        PlayerIconButton(onClick = onToggleMute) {
            Icon(
                imageVector = if (isMuted) {
                    MaterialSymbols.Rounded.VolumeOff
                } else {
                    MaterialSymbols.Rounded.VolumeUp
                },
                contentDescription = if (isMuted) "取消静音" else "静音",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        // The player swallows taps, so the reader menu needs an explicit entry point here.
        PlayerIconButton(onClick = onOpenReaderMenu) {
            Icon(
                imageVector = MaterialSymbols.Rounded.MoreVert,
                contentDescription = "更多",
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun VideoTransportRow(
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(onClick = { onSeekBy(-10_000L) }) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Replay10,
                contentDescription = "后退 10 秒",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(32.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTogglePlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) {
                    MaterialSymbols.Rounded.Pause
                } else {
                    MaterialSymbols.Rounded.PlayArrow
                },
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(38.dp),
            )
        }
        PlayerIconButton(onClick = { onSeekBy(10_000L) }) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Forward10,
                contentDescription = "前进 10 秒",
                tint = Color.White,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun VideoBottomBar(
    state: VideoControlsState,
    onSeekTo: (Long) -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // While dragging, the thumb follows the finger and the label shows the target time.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0f) }

    val duration = state.durationMs.coerceAtLeast(0L)
    val position = if (scrubbing) scrubPosition.toLong() else state.positionMs
    val sliderMax = duration.coerceAtLeast(1L).toFloat()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(position),
                color = if (scrubbing) MaterialTheme.colorScheme.primary else Color.White,
                fontSize = 11.sp,
            )
            Text(
                text = " / ",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
            Text(
                text = formatTime(duration),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            PlayerIconButton(onClick = onToggleFullscreen, size = 36.dp) {
                Icon(
                    imageVector = if (state.isFullscreen) {
                        MaterialSymbols.Rounded.FullscreenExit
                    } else {
                        MaterialSymbols.Rounded.Fullscreen
                    },
                    contentDescription = if (state.isFullscreen) "退出全屏" else "全屏",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        val sliderState = remember(duration) {
            SliderState(
                value = position.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat(),
                trackRange = 0f..sliderMax,
            )
        }
        // Keep the thumb in sync with playback unless the user is dragging it. This runs in a
        // side effect, not during composition.
        LaunchedEffect(position, scrubbing) {
            if (!scrubbing) {
                sliderState.value = position.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat()
            }
        }

        Slider(
            state = sliderState,
            onValueChangeFinished = {
                scrubbing = false
                onSeekTo(scrubPosition.toLong())
            },
            onValueChange = {
                scrubbing = true
                scrubPosition = it
                sliderState.value = it
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        )

        if (state.bufferedMs > 0L && duration > 0L) {
            LinearProgressIndicator(
                progress = { (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = Color.White.copy(alpha = 0.45f),
                trackColor = Color.Transparent,
            )
        }
    }
}

@Composable
private fun SeekPreview(
    gesture: VideoGestureState,
    modifier: Modifier = Modifier,
) {
    val delta = gesture.seekDeltaMs
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "${formatTime(gesture.seekTargetMs)} / ${formatTime(gesture.durationMs)}",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = if (delta >= 0) "+${formatSigned(delta)}" else formatSigned(delta),
            color = if (delta >= 0) Color(0xFF84E296) else Color(0xFFFF9E80),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LevelPreview(
    gesture: VideoGestureState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (gesture.mode == VideoGestureMode.VOLUME) {
                MaterialSymbols.Rounded.VolumeUp
            } else {
                MaterialSymbols.Rounded.Brightness6
            },
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = "${(gesture.level * 100).toInt()}%",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (gesture.levelFixed) {
            Text(
                text = "系统音量",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    size: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

internal fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatSigned(ms: Long): String {
    val seconds = abs(ms) / 1000
    return "${seconds}s"
}

private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"
