package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException

/**
 * A viewer that plays video content (m3u8, mp4, etc.) using ExoPlayer.
 * Used when pages contain [Page.videoUrl] instead of image URLs.
 */
class VideoViewer(private val activity: ReaderActivity) : Viewer {

    private val pagesFlow = MutableStateFlow<List<ReaderPage>>(emptyList())
    private val currentPageIndex = MutableStateFlow(0)

    private var player: ExoPlayer? = null

    @Composable
    override fun Content(modifier: Modifier) {
        val pages by pagesFlow.collectAsState()
        val currentIndex by currentPageIndex.collectAsState()
        val context = LocalContext.current

        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        val currentPlayer = remember {
            ExoPlayer.Builder(context).build().also {
                player = it
            }
        }

        DisposableEffect(currentPlayer) {
            onDispose {
                currentPlayer.release()
                player = null
            }
        }

        val currentPage = pages.getOrNull(currentIndex)
        val videoUrl = currentPage?.videoUrl

        // Set up listener for loading/error states
        DisposableEffect(currentPlayer) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            isLoading = true
                            errorMessage = null
                        }
                        Player.STATE_READY -> {
                            isLoading = false
                            errorMessage = null
                        }
                        Player.STATE_ENDED -> {
                            isLoading = false
                        }
                        Player.STATE_IDLE -> {
                            isLoading = true
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    isLoading = false
                    errorMessage = error.message ?: "播放错误: ${error.errorCodeName}"
                }
            }
            currentPlayer.addListener(listener)
            onDispose {
                currentPlayer.removeListener(listener)
            }
        }

        LaunchedEffect(videoUrl) {
            if (videoUrl != null) {
                isLoading = true
                errorMessage = null
                try {
                    val mediaItem = MediaItem.fromUri(videoUrl)
                    // For HLS (m3u8) content, ExoPlayer auto-detects the MIME type
                    // from the URI extension, so we just need to set the media item
                    currentPlayer.setMediaItem(mediaItem)
                    currentPlayer.prepare()
                    currentPlayer.playWhenReady = true
                } catch (e: Exception) {
                    isLoading = false
                    errorMessage = e.message ?: "无法加载视频"
                }
            }
        }

        Box(modifier = modifier) {
            if (videoUrl != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            this.player = currentPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (isLoading) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            } else {
                // No video URL available - show message
                Text(
                    text = "暂无视频内容",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val pages = chapters.currChapter.pages ?: emptyList()
        pagesFlow.value = pages
        currentPageIndex.value = 0
    }

    override fun moveToPage(page: ReaderPage) {
        val pages = pagesFlow.value
        val index = pages.indexOf(page)
        if (index >= 0) {
            currentPageIndex.value = index
        }
    }

    override fun moveForward() {
        val current = currentPageIndex.value
        val max = pagesFlow.value.size - 1
        if (current < max) {
            currentPageIndex.value = current + 1
        }
    }

    override fun moveBackward() {
        val current = currentPageIndex.value
        if (current > 0) {
            currentPageIndex.value = current - 1
        }
    }

    override fun destroy() {
        player?.release()
        player = null
    }
}
