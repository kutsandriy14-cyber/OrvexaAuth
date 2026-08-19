package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Beta updater backed by GitHub Releases. It never installs an APK silently:
 * Android always owns the final package-install confirmation.
 */
object UpdateManager {
    private const val RELEASES_URL =
        "https://api.github.com/repos/kutsandriy14-cyber/OrvexaAuth/releases?per_page=30"
    private const val RELEASE_DOWNLOAD_PREFIX =
        "/kutsandriy14-cyber/OrvexaAuth/releases/download/"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ReleaseUpdate(
        val tag: String,
        val displayName: String,
        val apkUrl: String,
        val sha256: String
    )

    /**
     * State for the foreground update UI. A negative total or percent means that
     * the download server did not send a Content-Length header.
     */
    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int
    )

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private data class SemanticVersion(
        val major: Int,
        val minor: Int,
        val patch: Int
    ) : Comparable<SemanticVersion> {
        override fun compareTo(other: SemanticVersion): Int = compareValuesBy(
            this,
            other,
            SemanticVersion::major,
            SemanticVersion::minor,
            SemanticVersion::patch
        )
    }

    private val client = OkHttpClient.Builder()
        .connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun check(): ReleaseUpdate? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "OrvexaAuth/${BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val releases = JSONArray(response.body?.string().orEmpty())
                val currentVersion = parseVersion(BuildConfig.VERSION_NAME) ?: return@withContext null
                var newest: ReleaseUpdate? = null
                var newestVersion: SemanticVersion? = null
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    if (release.optBoolean("draft")) continue
                    val tag = release.optString("tag_name").trim()
                    val releaseVersion = parseVersion(tag) ?: continue
                    if (releaseVersion <= currentVersion) continue
                    val releaseVersionName = tag.removePrefix("v").substringBefore("-")
                    val assets = release.optJSONArray("assets") ?: continue
                    val allowedNames = setOf(
                        "OrvexaAuth-$releaseVersionName.apk",
                        "OrvexaAuth-Beta-$releaseVersionName.apk"
                    )
                    for (assetIndex in 0 until assets.length()) {
                        val asset = assets.optJSONObject(assetIndex) ?: continue
                        if (asset.optString("name") !in allowedNames) continue
                        val apkUrl = asset.optString("browser_download_url")
                        val sha256 = asset.optString("digest")
                            .removePrefix("sha256:")
                            .lowercase()
                        if (!isTrustedReleaseUrl(apkUrl) || !SHA256_REGEX.matches(sha256)) continue
                        if (newestVersion == null || releaseVersion > newestVersion!!) {
                            newest = ReleaseUpdate(
                            tag = tag,
                            displayName = release.optString("name", tag),
                            apkUrl = apkUrl,
                            sha256 = sha256
                        )
                            newestVersion = releaseVersion
                        }
                    }
                }
                return@withContext newest
            }
        } catch (_: Exception) {
            // Update checks are optional and must never block authentication.
        }
        null
    }

    fun currentTag(): String = "v${BuildConfig.VERSION_NAME}"

    /** Downloads, verifies, and opens Android's package installer. */
    fun downloadAndOpenInstaller(context: Context, update: ReleaseUpdate) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !appContext.packageManager.canRequestPackageInstalls()
                ) {
                    withContext(Dispatchers.Main) {
                        appContext.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${appContext.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        Toast.makeText(
                            appContext,
                            "Разреши установку обновлений для OrvexaAuth, затем нажми обновление ещё раз",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }

                val apkFile = File(appContext.cacheDir, "OrvexaAuth-${update.tag.removePrefix("v")}.apk")
                _downloadProgress.value = DownloadProgress(0L, -1L, -1)
                downloadToFile(update.apkUrl, apkFile) { progress ->
                    _downloadProgress.value = progress
                }
                if (update.sha256 != sha256(apkFile)) {
                    apkFile.delete()
                    error("APK checksum verification failed")
                }

                withContext(Dispatchers.Main) {
                    val apkUri = FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        apkFile
                    )
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, APK_MIME)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        appContext.startActivity(installIntent)
                    } catch (_: Exception) {
                        val settingsIntent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${appContext.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        appContext.startActivity(settingsIntent)
                        Toast.makeText(
                            appContext,
                            "Разреши установку обновлений, затем повтори загрузку",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    _downloadProgress.value = null
                    Toast.makeText(
                        appContext,
                        "Не удалось проверить или установить обновление",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private suspend fun downloadToFile(
        url: String,
        destination: File,
        onProgress: (DownloadProgress) -> Unit
    ) {
        check(isTrustedReleaseUrl(url)) { "Untrusted APK URL" }
        val request = Request.Builder().url(url).build()
        destination.delete()
        try {
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Download failed: ${response.code}" }
                val body = response.body ?: error("Empty APK response")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val percent = if (totalBytes > 0L) {
                                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                            onProgress(DownloadProgress(downloadedBytes, totalBytes, percent))
                        }
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun parseVersion(value: String): SemanticVersion? {
        val match = VERSION_PATTERN.matchEntire(value.trim()) ?: return null
        return SemanticVersion(
            major = match.groupValues[1].toIntOrNull() ?: return null,
            minor = match.groupValues[2].toIntOrNull() ?: return null,
            patch = match.groupValues[3].toIntOrNull() ?: return null
        )
    }

    private fun isTrustedReleaseUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" &&
            uri.host == "github.com" &&
            uri.path?.startsWith(RELEASE_DOWNLOAD_PREFIX) == true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count: Int
            while (input.read(buffer).also { count = it } >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val VERSION_PATTERN = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
    private val SHA256_REGEX = Regex("^[a-f0-9]{64}$")
}
