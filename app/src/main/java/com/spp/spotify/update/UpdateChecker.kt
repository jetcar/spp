package com.spp.spotify.update

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.spp.spotify.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("assets") val assets: List<ReleaseAsset>
)

private data class ReleaseAsset(
    @SerializedName("name") val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String
)

data class UpdateInfo(
    val latestVersion: String,
    val currentVersion: String,
    val releaseUrl: String,
    val apkDownloadUrl: String?,
    val isUpdateAvailable: Boolean
)

object UpdateChecker {

    private val releasesApiUrl: String
        get() = "https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest"

    private val client = OkHttpClient()
    private val gson = Gson()

    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(releasesApiUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }

                val bodyStr = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                val release = gson.fromJson(bodyStr, GitHubRelease::class.java)
                val latestVersion = release.tagName.removePrefix("v")
                val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk") }

                Result.success(
                    UpdateInfo(
                        latestVersion = latestVersion,
                        currentVersion = currentVersion,
                        releaseUrl = release.htmlUrl,
                        apkDownloadUrl = apkAsset?.downloadUrl,
                        isUpdateAvailable = isNewer(latestVersion, currentVersion)
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Returns true when [candidate] is strictly newer than [current] (semver comparison). */
    private fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val v = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(c.size, v.size)
        for (i in 0 until len) {
            val ci = c.getOrElse(i) { 0 }
            val vi = v.getOrElse(i) { 0 }
            if (ci > vi) return true
            if (ci < vi) return false
        }
        return false
    }
}
