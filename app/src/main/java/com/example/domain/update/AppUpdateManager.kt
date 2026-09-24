package com.example.domain.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class AppUpdateManager(
    private val context: Context,
    private val repositoryOwner: String = "Eslam3537",
    private val repositoryName: String = "wifi"
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    val currentVersionName: String = BuildConfig.VERSION_NAME
    val currentVersionCode: Int = BuildConfig.VERSION_CODE

    suspend fun checkForUpdates(silent: Boolean = false): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
        if (!silent) {
            _updateState.value = AppUpdateState.Checking
        }

        try {
            val apiUrl = "https://api.github.com/repos/$repositoryOwner/$repositoryName/releases/latest"
            val request = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "NetManagerPro-Android")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorMsg = if (response.code == 404) {
                    "No releases published yet on repository $repositoryOwner/$repositoryName"
                } else {
                    "GitHub API HTTP error: ${response.code}"
                }
                if (!silent) {
                    _updateState.value = AppUpdateState.Error(errorMsg)
                } else {
                    _updateState.value = AppUpdateState.Idle
                }
                return@withContext Result.failure(Exception(errorMsg))
            }

            val bodyString = response.body?.string() ?: ""
            val json = JSONObject(bodyString)

            val tagName = json.optString("tag_name", "")
            val title = json.optString("name", "Update $tagName")
            val notes = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")

            var downloadUrl = "https://github.com/$repositoryOwner/$repositoryName/releases/latest/download/app-debug.apk"
            var sizeBytes = 0L
            var metadataVersionCode: Int? = null
            var metadataVersionName: String? = null

            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", downloadUrl)
                        sizeBytes = asset.optLong("size", 0L)
                    } else if (name.equals("update-metadata.json", ignoreCase = true) || name.equals("version.json", ignoreCase = true)) {
                        val metaUrl = asset.optString("browser_download_url", "")
                        if (metaUrl.isNotBlank()) {
                            try {
                                val metaReq = Request.Builder().url(metaUrl).build()
                                val metaResp = client.newCall(metaReq).execute()
                                if (metaResp.isSuccessful) {
                                    val metaJson = JSONObject(metaResp.body?.string() ?: "")
                                    if (metaJson.has("versionCode")) {
                                        metadataVersionCode = metaJson.optInt("versionCode")
                                    }
                                    if (metaJson.has("versionName")) {
                                        metadataVersionName = metaJson.optString("versionName")
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            // Determine remote version code and version name
            val remoteVersionCode = metadataVersionCode ?: extractVersionCode(tagName, notes, title)
            val remoteVersionName = metadataVersionName ?: extractVersionName(tagName, title)

            // Strict numeric comparison: new versionCode > old versionCode
            val isNewer = remoteVersionCode > currentVersionCode

            val releaseInfo = AppReleaseInfo(
                tagName = tagName,
                title = title,
                notes = if (notes.isNotBlank()) notes else "Version $remoteVersionName (Build #$remoteVersionCode)",
                downloadUrl = downloadUrl,
                sizeBytes = sizeBytes,
                publishedAt = publishedAt,
                remoteVersionCode = remoteVersionCode,
                remoteVersionName = remoteVersionName,
                isNewer = isNewer
            )

            if (isNewer) {
                _updateState.value = AppUpdateState.UpdateAvailable(releaseInfo)
            } else {
                if (!silent) {
                    // Manual check: Show "App is up to date"
                    _updateState.value = AppUpdateState.UpToDate
                } else {
                    // Silent background check: Stay Idle (NO UI, NO Dialog, NO Toast)
                    _updateState.value = AppUpdateState.Idle
                }
            }

            Result.success(releaseInfo)
        } catch (e: Exception) {
            val error = e.localizedMessage ?: "Failed to check for updates."
            if (!silent) {
                _updateState.value = AppUpdateState.Error(error)
            } else {
                _updateState.value = AppUpdateState.Idle
            }
            Result.failure(e)
        }
    }

    private fun extractVersionCode(tagName: String, notes: String, title: String): Int {
        // 1. Look for explicit "versionCode": X or "versionCode = X" or "VersionCode: X" in notes or title
        val explicitMatch = Regex("""(?i)(?:version_?code|build_?code|build_?number)\s*[:=]\s*(\d+)""").find("$notes $title $tagName")
        if (explicitMatch != null) {
            val code = explicitMatch.groupValues[1].toIntOrNull()
            if (code != null && code > 0) return code
        }

        // 2. Look for "build-X" in tagName or title
        val buildMatch = Regex("""(?i)build-(\d+)""").find("$tagName $title")
        if (buildMatch != null) {
            val code = buildMatch.groupValues[1].toIntOrNull()
            if (code != null && code > 0) return code
        }

        // 3. Look for "vX.Y.Z" and compute numeric fallback if no explicit code
        val semverMatch = Regex("""(?:v|V)?(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(tagName)
        if (semverMatch != null) {
            val major = semverMatch.groupValues.getOrNull(1)?.toIntOrNull() ?: 0
            val minor = semverMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val patch = semverMatch.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            return (major * 1000) + (minor * 100) + patch
        }

        return 0
    }

    private fun extractVersionName(tagName: String, title: String): String {
        val semverMatch = Regex("""(?:v|V)?(\d+\.\d+(?:\.\d+)?)""").find("$tagName $title")
        if (semverMatch != null) {
            return semverMatch.groupValues[1]
        }
        return tagName.removePrefix("v").removePrefix("V").ifBlank { currentVersionName }
    }

    suspend fun downloadUpdate(downloadUrl: String): Result<File> = withContext(Dispatchers.IO) {
        _updateState.value = AppUpdateState.Downloading(progress = 0f, downloadedBytes = 0L, totalBytes = 0L)

        try {
            val updateDir = File(context.cacheDir, "apk_updates")
            if (!updateDir.exists()) {
                updateDir.mkdirs()
            }
            val apkFile = File(updateDir, "app-debug.apk")
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NetManagerPro-Android")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val error = "Failed to download update APK (HTTP ${response.code})"
                _updateState.value = AppUpdateState.Error(error)
                return@withContext Result.failure(Exception(error))
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val progress = if (totalBytes > 0) {
                            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0.5f
                        }
                        _updateState.value = AppUpdateState.Downloading(
                            progress = progress,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes
                        )
                    }
                    output.flush()
                }
            }

            if (!apkFile.exists() || apkFile.length() == 0L) {
                val error = "Downloaded file is corrupted or empty."
                _updateState.value = AppUpdateState.Error(error)
                return@withContext Result.failure(Exception(error))
            }

            _updateState.value = AppUpdateState.ReadyToInstall(apkFile)
            Result.success(apkFile)
        } catch (e: Exception) {
            val error = e.localizedMessage ?: "Failed to download update."
            _updateState.value = AppUpdateState.Error(error)
            Result.failure(e)
        }
    }

    fun installApk(apkFile: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            _updateState.value = AppUpdateState.Error("Could not launch package installer: ${e.localizedMessage}")
            false
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun resetState() {
        _updateState.value = AppUpdateState.Idle
    }
}
