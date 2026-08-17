package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        "https://api.github.com/repos/kutsandriy14-cyber/OrvexaAuth/releases?per_page=10"
    private const val APK_ASSET = "app-debug.apk"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class ReleaseUpdate(
        val tag: String,
        val displayName: String,
        val apkUrl: String,
        val checksumUrl: String?
    )

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
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val releases = JSONArray(response.body?.string().orEmpty())
                for (index in 0 until releases.length()) {
                    val release = releases.optJSONObject(index) ?: continue
                    val tag = release.optString("tag_name")
                    val current = currentTag()
                    if (tag.isBlank() || tag == current || tag.startsWith("$current-")) continue
                    val assets = release.optJSONArray("assets") ?: continue
                    var apkUrl: String? = null
                    var checksumUrl: String? = null
                    for (assetIndex in 0 until assets.length()) {
                        val asset = assets.optJSONObject(assetIndex) ?: continue
                        when (asset.optString("name")) {
                            APK_ASSET -> apkUrl = asset.optString("browser_download_url")
                            "app-debug.apk.sha256" -> checksumUrl = asset.optString("browser_download_url")
                        }
                    }
                    if (!apkUrl.isNullOrBlank()) {
                        return@withContext ReleaseUpdate(
                            tag = tag,
                            displayName = release.optString("name", tag),
                            apkUrl = apkUrl,
                            checksumUrl = checksumUrl
                        )
                    }
                }
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
                val apkFile = File(appContext.cacheDir, APK_ASSET)
                downloadToFile(update.apkUrl, apkFile)
                update.checksumUrl?.let { checksumUrl ->
                    val expected = downloadText(checksumUrl)
                        .trim()
                        .substringBefore(" ")
                        .substringBefore("\t")
                        .lowercase()
                    if (expected.isNotBlank() && expected != sha256(apkFile)) {
                        apkFile.delete()
                        error("APK checksum verification failed")
                    }
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
                    Toast.makeText(
                        appContext,
                        "Не удалось проверить или установить обновление",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun downloadToFile(url: String, destination: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: ${response.code}" }
            val body = response.body ?: error("Empty APK response")
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        }
    }

    private fun downloadText(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Checksum download failed: ${response.code}" }
            return response.body?.string().orEmpty()
        }
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
}
