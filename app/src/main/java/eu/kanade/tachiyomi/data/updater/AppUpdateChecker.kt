package eu.kanade.tachiyomi.data.updater

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release

val GITHUB_REPO: String = "ydqsgl/ydmihon"

/**
 * Checks for a new release without touching the GitHub API.
 *
 * The API's unauthenticated quota is 60 requests per hour *per IP*, and returning 403 once it is
 * spent. Behind carrier NAT that budget is shared with strangers, so an API-based check silently
 * stops working for hours at a time — which looks exactly like "no update available".
 *
 * Instead this reads two ordinary web pages, which are not rate limited the same way:
 *
 * 1. `releases/latest` is a 302 whose `Location` names the newest tag. That alone answers
 *    "is there a newer version".
 * 2. `releases.atom` is the release feed, used when the redirect is unavailable. It also carries
 *    the changelog, so no extra request is needed for the "what's new" text.
 *
 * Asset links come from the `expanded_assets` HTML fragment, with a constructed fallback URL, so a
 * layout change on that fragment cannot make the download button dead.
 */
@Inject
class AppUpdateChecker(
    private val networkHelper: NetworkHelper,
) {

    /**
     * A client that does not follow redirects, because the redirect target *is* the answer here.
     */
    private val redirectClient: OkHttpClient by lazy {
        networkHelper.client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        return withContext(Dispatchers.IO) {
            val failures = mutableListOf<String>()

            val tag = try {
                latestTagFromRedirect()
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Redirect check failed: ${e.message}" }
                failures += "重定向检查失败：${e.message ?: e.javaClass.simpleName}"
                null
            }

            val feedEntry = if (tag == null) {
                try {
                    latestFromFeed()
                } catch (e: Exception) {
                    logcat(LogPriority.WARN) { "Atom check failed: ${e.message}" }
                    failures += "Atom 检查失败：${e.message ?: e.javaClass.simpleName}"
                    null
                }
            } else {
                null
            }

            val latestTag = tag ?: feedEntry?.tag
            if (latestTag == null) {
                val detail = failures.joinToString("；").ifEmpty { "所有检查通道均无响应" }
                return@withContext GetApplicationRelease.Result.Failure(detail)
            }

            val latestVersion = latestTag.removePrefix("v").removePrefix("r")
            val currentVersion = BuildConfig.VERSION_NAME

            if (!isVersionNewer(latestVersion, currentVersion)) {
                return@withContext GetApplicationRelease.Result.NoNewUpdate
            }

            val releaseLink = "https://github.com/$GITHUB_REPO/releases/tag/$latestTag"
            // A dead download button is worse than a slightly stale one, so fall back to the
            // conventional asset name if the fragment could not be read.
            val downloadLink = (try {
                resolveDownloadLink(latestTag)
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Asset lookup failed: ${e.message}" }
                null
            }) ?: defaultDownloadLink(latestTag)

            GetApplicationRelease.Result.NewUpdate(
                Release(
                    version = latestVersion,
                    info = feedEntry?.body?.takeIf { it.isNotBlank() } ?: "新版本可用",
                    releaseLink = releaseLink,
                    downloadLink = downloadLink,
                ),
            )
        }
    }

    // ============================== Channels ==============================

    /**
     * Reads the tag from the `releases/latest` redirect.
     *
     * A 200 instead of a 302 means the repo has no releases yet, which is reported as "no tag"
     * rather than an error so the feed channel can take over.
     */
    private fun latestTagFromRedirect(): String? {
        val request = Request.Builder()
            .url("https://github.com/$GITHUB_REPO/releases/latest")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html")
            .get()
            .build()

        redirectClient.newCall(request).execute().use { response ->
            val location = response.header("Location")
            if (location != null) {
                return TAG_IN_PATH.find(location)?.groupValues?.get(1)
            }
            // Some intermediaries follow the redirect for us; the final URL still carries the tag.
            return TAG_IN_PATH.find(response.request.url.toString())?.groupValues?.get(1)
        }
    }

    private data class FeedEntry(val tag: String, val body: String)

    /** Reads the newest entry of the release feed. */
    private fun latestFromFeed(): FeedEntry? {
        val request = Request.Builder()
            .url("https://github.com/$GITHUB_REPO/releases.atom")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/atom+xml, application/xml, text/xml")
            .get()
            .build()

        val xml = redirectClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body.string()
        }

        val entry = FIRST_ENTRY.find(xml)?.value ?: return null
        val tag = ENTRY_TAG.find(entry)?.groupValues?.get(1)
            ?: ENTRY_LINK.find(entry)?.groupValues?.get(1)
            ?: return null
        val content = ENTRY_CONTENT.find(entry)?.groupValues?.get(1)
        return FeedEntry(tag = tag, body = content?.let(::htmlToPlainText).orEmpty())
    }

    // ============================== Assets ==============================

    /**
     * Picks the APK to download.
     *
     * The fragment lists every asset as a plain link. arm64 is preferred because that is what
     * essentially every phone since 2017 runs; the universal build is the safety net.
     */
    private fun resolveDownloadLink(tag: String): String? {
        val request = Request.Builder()
            .url("https://github.com/$GITHUB_REPO/releases/expanded_assets/$tag")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val html = redirectClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body.string()
        }

        val assets = ASSET_HREF.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.endsWith(".apk", ignoreCase = true) }
            .map { if (it.startsWith("http")) it else "https://github.com$it" }
            .toList()

        if (assets.isEmpty()) return null

        return assets.firstOrNull { it.contains(ABI_ARM64) }
            ?: assets.firstOrNull { it.contains("universal") }
            ?: assets.firstOrNull()
    }

    private fun defaultDownloadLink(tag: String): String =
        "https://github.com/$GITHUB_REPO/releases/download/$tag/app-arm64-v8a-release.apk"

    // ============================== Helpers ==============================

    /**
     * Compares dotted versions numerically.
     *
     * Padded on the right so `1.3` and `1.3.0` are equal instead of throwing on a missing part.
     */
    private fun isVersionNewer(new: String, old: String): Boolean {
        val newParts = new.split(".").mapNotNull { it.toIntOrNull() }
        val oldParts = old.split(".").mapNotNull { it.toIntOrNull() }
        if (newParts.isEmpty() || oldParts.isEmpty()) return false

        val length = maxOf(newParts.size, oldParts.size)
        for (i in 0 until length) {
            val newPart = newParts.getOrElse(i) { 0 }
            val oldPart = oldParts.getOrElse(i) { 0 }
            if (newPart > oldPart) return true
            if (newPart < oldPart) return false
        }
        return false
    }

    /**
     * Turns the feed's escaped HTML into the plain text the update dialog renders.
     *
     * The dialog shows `info` as text, so leaving tags in would display raw `<h2>` markup.
     */
    private fun htmlToPlainText(html: String): String {
        return html
            // The feed escapes its HTML once more than the parser expects.
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(BR_TAG, "\n")
            .replace(TAG, "")
            .replace(TRIPLE_NEWLINE, "\n\n")
            .trim()
    }

    private companion object {
        private const val USER_AGENT = "ydmihon-updater"

        /** `app-arm64-v8a-release.apk` — the ABI nearly every device uses. */
        private const val ABI_ARM64 = "arm64-v8a"

        private val TAG_IN_PATH = Regex("""/releases/tag/([^/?#]+)""")
        private val FIRST_ENTRY = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
        private val ENTRY_TAG = Regex("""/releases/tag/([^"<]+)""")
        private val ENTRY_LINK = Regex("""<link[^>]+href="[^"]*/releases/tag/([^"/]+)"""")
        private val ENTRY_CONTENT = Regex("""<content[^>]*>(.*?)</content>""", RegexOption.DOT_MATCHES_ALL)
        private val ASSET_HREF = Regex("""href="([^"]*/releases/download/[^"]*)"""")
        private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val TAG = Regex("""<[^>]+>""")
        private val TRIPLE_NEWLINE = Regex("""\n{3,}""")
    }
}

val RELEASE_TAG: String by lazy {
    "v${BuildConfig.VERSION_NAME}"
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
