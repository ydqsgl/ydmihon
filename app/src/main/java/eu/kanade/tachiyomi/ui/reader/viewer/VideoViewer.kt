package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.video.VideoPlayerHost
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 123AV's HLS CDN host, which expects the embed origin as the Referer. */
private const val PIPER_STREAM_HOST = "piper-store.site"

/** Origin of the 123AV embed player, the Referer that host requires. */
private const val SADIE_EMBED_ORIGIN = "https://sadie-shop.site/"

/**
 * A viewer that plays video content (m3u8, mp4, etc.) using ExoPlayer.
 * Used when pages contain [Page.videoUrl] instead of image URLs.
 *
 * Controls are custom Compose chrome (see [VideoPlayerHost]) so the player supports horizontal
 * drag-to-seek, vertical volume/brightness, and landscape fullscreen.
 */
class VideoViewer(private val activity: ReaderActivity) : Viewer {

    private val pagesFlow = MutableStateFlow<List<ReaderPage>>(emptyList())
    private val currentPageIndex = MutableStateFlow(0)

    @Composable
    override fun Content(modifier: Modifier) {
        val pages by pagesFlow.collectAsState()
        val currentIndex by currentPageIndex.collectAsState()

        var errorMessage by remember { mutableStateOf<String?>(null) }
        var retryCount by remember { mutableIntStateOf(0) }

        val currentPage = pages.getOrNull(currentIndex)
        val videoUrl = currentPage?.videoUrl

        // The site's CDN rejects requests without a matching Referer/UA. 123AV's playlist CDN
        // (piper-store.site) expects the embed origin (sadie-shop.site) instead of the catalogue
        // origin, so scope a small override to that host — no other source is affected.
        val referer = remember(videoUrl) {
            val chapterOrigin = activity.viewModel.getChapterUrl()
                ?.toHttpUrlOrNull()
                ?.let { "${it.scheme}://${it.host}/" }
            if (videoUrl?.contains(PIPER_STREAM_HOST) == true) SADIE_EMBED_ORIGIN else chapterOrigin
        }

        Box(modifier = modifier.background(Color.Black)) {
            if (videoUrl != null) {
                // The chapter name ("视频 1") identifies the clip better than the whole title.
                val chapterName = remember(currentPage) {
                    runCatching { currentPage.chapter.chapter.name }.getOrDefault("")
                }
                VideoPlayerHost(
                    videoUrl = videoUrl,
                    title = chapterName,
                    refererUrl = referer,
                    activity = activity,
                    retryKey = retryCount,
                    onPlaybackError = { errorMessage = it },
                    modifier = Modifier.fillMaxSize(),
                )

                errorMessage?.let { msg ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.8f))
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = msg,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                        )
                        Button(
                            onClick = {
                                errorMessage = null
                                retryCount++
                            },
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text("重试")
                        }
                    }
                }
            } else {
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
        // The player is released by VideoPlayerHost's DisposableEffect.
    }
}
