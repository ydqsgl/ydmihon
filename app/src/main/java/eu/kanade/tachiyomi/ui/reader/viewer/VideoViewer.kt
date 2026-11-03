package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
        var retryCount by remember { mutableIntStateOf(0) }

        // The site's CDN rejects requests without a matching Referer/UA, which is what the
        // built-in ExoPlayer HTTP stack sends by default. Reuse the chapter page as the referer.
        val referer = remember {
            activity.viewModel.getChapterUrl()
                ?.toHttpUrlOrNull()
                ?.let { "${it.scheme}://${it.host}/" }
        }

        val mediaSourceFactory = remember {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setDefaultRequestProperties(
                    buildMap {
                        put("User-Agent", USER_AGENT)
                        put("Accept", "*/*")
                        if (referer != null) put("Referer", referer)
                    },
                )
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)

            DefaultMediaSourceFactory(dataSourceFactory)
        }

        val currentPlayer = remember {
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .also { player = it }
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
                    errorMessage = describeError(error)
                }
            }
            currentPlayer.addListener(listener)
            onDispose {
                currentPlayer.removeListener(listener)
            }
        }

        LaunchedEffect(videoUrl, retryCount) {
            if (videoUrl != null) {
                isLoading = true
                errorMessage = null
                try {
                    currentPlayer.setMediaItem(buildMediaItem(videoUrl))
                    currentPlayer.prepare()
                    currentPlayer.playWhenReady = true
                } catch (e: Exception) {
                    isLoading = false
                    errorMessage = e.message ?: "无法加载视频"
                }
            }
        }

        Box(modifier = modifier.background(Color.Black)) {
            if (videoUrl != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            useController = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            this.player = currentPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                errorMessage?.let { msg ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
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
                            onClick = { retryCount++ },
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Text("重试")
                        }
                    }
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
    private fun describeError(error: PlaybackException): String {
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

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
