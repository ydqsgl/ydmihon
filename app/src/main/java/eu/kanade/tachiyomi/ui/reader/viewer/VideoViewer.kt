package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import kotlinx.coroutines.flow.MutableStateFlow

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

        LaunchedEffect(videoUrl) {
            if (videoUrl != null) {
                val mediaItem = MediaItem.fromUri(videoUrl)
                currentPlayer.setMediaItem(mediaItem)
                currentPlayer.prepare()
                currentPlayer.playWhenReady = true
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
                            this.player = currentPlayer
                            setFullscreenButtonClickListener { /* toggle fullscreen */ }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
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
