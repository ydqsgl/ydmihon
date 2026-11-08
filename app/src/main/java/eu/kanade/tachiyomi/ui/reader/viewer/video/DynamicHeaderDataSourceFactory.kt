package eu.kanade.tachiyomi.ui.reader.viewer.video

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * HTTP data source factory that resolves its request headers on every connection.
 *
 * ExoPlayer copies a factory's default request properties into the data source when the player is
 * built, so a fixed map keeps sending whatever was captured at construction time — that is what made
 * 123AV retries send the previous clip's Referer. Reading the headers through [headersProvider] on
 * each [createDataSource] call keeps one player usable across clips without rebuilding it;
 * rebuilding mid-playback tears down the surface and was observed looping create/release.
 */
internal class DynamicHeaderDataSourceFactory(
    private val headersProvider: () -> Map<String, String>,
    private val userAgent: String,
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 20_000,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val headers = headersProvider()
        return DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(connectTimeoutMs)
            .setReadTimeoutMs(readTimeoutMs)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
    }
}
