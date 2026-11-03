package eu.kanade.tachiyomi.data.updater

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.system.isFossBuildType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import logcat.LogPriority
import logcat.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release

val GITHUB_REPO: String = "ydqsgl/ydmihon"

@Inject
class AppUpdateChecker(
    private val getApplicationRelease: GetApplicationRelease,
    private val networkHelper: NetworkHelper,
) {

    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        // First try the standard flow (uses GitHub API)
        val standardResult = withContext(Dispatchers.IO) {
            try {
                getApplicationRelease.await(
                    GetApplicationRelease.Arguments(
                        isFossBuildType,
                        false,
                        BuildConfig.COMMIT_COUNT.toInt(),
                        BuildConfig.VERSION_NAME,
                        GITHUB_REPO,
                        forceCheck,
                    ),
                )
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Standard update check failed: ${e.message}" }
                null
            }
        }

        // If standard flow returned a new update, use it
        if (standardResult is GetApplicationRelease.Result.NewUpdate) {
            return standardResult
        }

        // Fallback: directly query the latest release page HTML to detect version
        return withContext(Dispatchers.IO) {
            try {
                checkForUpdateFallback()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Fallback update check failed: ${e.message}" }
                GetApplicationRelease.Result.NoNewUpdate
            }
        }
    }

    private suspend fun checkForUpdateFallback(): GetApplicationRelease.Result {
        // Use the GitHub API with a shorter timeout to avoid long waits
        val json = Json { ignoreUnknownKeys = true }
        val response = networkHelper.client.newCall(
            GET("https://api.github.com/repos/$GITHUB_REPO/releases/latest"),
        ).awaitSuccess()

        val releaseInfo = json.decodeFromString<GithubLatestRelease>(
            response.body?.string() ?: return GetApplicationRelease.Result.NoNewUpdate,
        )

        val latestVersion = releaseInfo.tag_name.removePrefix("v").removePrefix("r")
        val currentVersion = BuildConfig.VERSION_NAME

        // Simple version comparison
        if (isVersionNewer(latestVersion, currentVersion)) {
            // Pick the APK that actually exists in the release instead of assuming a fixed name.
            val apkAsset = releaseInfo.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && it.name.contains("arm64-v8a")
            } ?: releaseInfo.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) && it.name.contains("universal")
            } ?: releaseInfo.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true)
            }

            val downloadUrl = apkAsset?.browserDownloadUrl
                ?: "https://github.com/$GITHUB_REPO/releases/download/${releaseInfo.tag_name}/app-arm64-v8a-release.apk"
            return GetApplicationRelease.Result.NewUpdate(
                Release(
                    version = latestVersion,
                    info = releaseInfo.body ?: "新版本可用",
                    releaseLink = "https://github.com/$GITHUB_REPO/releases/tag/${releaseInfo.tag_name}",
                    downloadLink = downloadUrl,
                ),
            )
        }

        return GetApplicationRelease.Result.NoNewUpdate
    }

    private fun isVersionNewer(new: String, old: String): Boolean {
        val newParts = new.split(".").mapNotNull { it.toIntOrNull() }
        val oldParts = old.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(newParts.size, oldParts.size)
        for (i in 0 until maxLen) {
            val newPart = newParts.getOrElse(i) { 0 }
            val oldPart = oldParts.getOrElse(i) { 0 }
            if (newPart > oldPart) return true
            if (newPart < oldPart) return false
        }
        return false
    }
}

@Serializable
private data class GithubLatestRelease(
    val tag_name: String,
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    @kotlinx.serialization.SerialName("browser_download_url")
    val browserDownloadUrl: String,
)

val RELEASE_TAG: String by lazy {
    "v${BuildConfig.VERSION_NAME}"
}

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
