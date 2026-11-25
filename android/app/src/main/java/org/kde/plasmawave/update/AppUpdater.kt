package org.kde.plasmawave.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.kde.plasmawave.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class AppUpdateRelease(
    val version: String,
    val tagName: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val htmlUrl: String
)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class UpToDate(val currentVersion: String) : UpdateState()
    data class Available(val release: AppUpdateRelease) : UpdateState()
    data class Downloading(
        val release: AppUpdateRelease,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateState()
    data class ReadyToInstall(val release: AppUpdateRelease, val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

sealed class InstallLaunchResult {
    data object Launched : InstallLaunchResult()
    data object NeedsPermission : InstallLaunchResult()
    data class Failed(val message: String) : InstallLaunchResult()
}

object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val REPO_OWNER = "Asedwa"
    private const val REPO_NAME = "projekt_b-d"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val MAX_APK_BYTES = 250L * 1024L * 1024L
    private const val UPDATES_SUBDIR = "updates"
    private const val APK_FILE_NAME = "projekt_b-d-update.apk"

    private val ALLOWED_DOWNLOAD_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )

    private val VERSION_REGEX = Regex("""\b(\d+\.\d+(?:\.\d+)?(?:[-+][0-9A-Za-z.-]+)?)\b""")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    internal fun isTrustedDownloadUrl(url: String): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (!parsed.scheme.equals("https", ignoreCase = true)) return false
        val host = parsed.host?.lowercase()?.trimEnd('.') ?: return false
        return host in ALLOWED_DOWNLOAD_HOSTS || host.endsWith(".githubusercontent.com")
    }

    internal fun extractVersion(vararg candidates: String?): String? {
        for (raw in candidates) {
            if (raw.isNullOrBlank()) continue
            val match = VERSION_REGEX.find(raw)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    /**
     * Returns positive if [remote] is newer than [current], 0 if equal, negative if older.
     */
    internal fun compareVersions(remote: String, current: String): Int {
        fun parseParts(v: String): Pair<List<Int>, String> {
            val cleaned = v.trim().removePrefix("v").removePrefix("V")
            val core = cleaned.substringBefore('-').substringBefore('+')
            val pre = cleaned.substringAfter('-', "")
            val nums = core.split('.').map { it.toIntOrNull() ?: 0 }
            return nums to pre
        }
        val (rNums, rPre) = parseParts(remote)
        val (cNums, cPre) = parseParts(current)
        val len = maxOf(rNums.size, cNums.size)
        for (i in 0 until len) {
            val r = rNums.getOrElse(i) { 0 }
            val c = cNums.getOrElse(i) { 0 }
            if (r != c) return r.compareTo(c)
        }
        // Stable release (no prerelease tag) is newer than a prerelease of the same version
        if (rPre.isEmpty() && cPre.isNotEmpty()) return 1
        if (rPre.isNotEmpty() && cPre.isEmpty()) return -1
        return rPre.compareTo(cPre)
    }

    fun isRemoteVersionNewer(remote: String, current: String = BuildConfig.VERSION_NAME): Boolean =
        compareVersions(remote, current) > 0

    suspend fun fetchLatestRelease(currentVersion: String = BuildConfig.VERSION_NAME): Result<AppUpdateRelease?> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(LATEST_RELEASE_API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "projekt_b-d/$currentVersion")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        return@withContext Result.failure(IOException("Yayın bulunamadı (depo gizli veya sürüm yok)"))
                    }
                    if (response.code == 403) {
                        return@withContext Result.failure(IOException("GitHub API hız sınırına ulaşıldı, biraz sonra tekrar dene"))
                    }
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IOException("Sunucu hatası (HTTP ${response.code})"))
                    }

                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@withContext Result.failure(IOException("Boş sunucu yanıtı"))
                    }

                    val json = JSONObject(body)
                    if (json.optBoolean("draft", false)) {
                        return@withContext Result.success(null)
                    }

                    val tagName = json.optString("tag_name", "")
                    val title = json.optString("name", tagName).ifBlank { tagName }
                    val notes = json.optString("body", "").trim()
                    val htmlUrl = json.optString("html_url", RELEASES_PAGE_URL)
                    val assets = json.optJSONArray("assets") ?: JSONArray()

                    var apkUrl: String? = null
                    var apkName: String? = null
                    var apkSize = 0L
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            val candidateUrl = asset.optString("browser_download_url", "")
                            if (isTrustedDownloadUrl(candidateUrl)) {
                                apkUrl = candidateUrl
                                apkName = name
                                apkSize = asset.optLong("size", 0L).coerceAtLeast(0L)
                                break
                            }
                        }
                    }

                    if (apkUrl == null) {
                        return@withContext Result.success(null)
                    }

                    val remoteVersion = extractVersion(title, apkName, tagName)
                        ?: return@withContext Result.success(null)

                    if (!isRemoteVersionNewer(remoteVersion, currentVersion)) {
                        return@withContext Result.success(null)
                    }

                    Result.success(
                        AppUpdateRelease(
                            version = remoteVersion,
                            tagName = tagName,
                            title = title,
                            notes = notes,
                            apkUrl = apkUrl,
                            apkSizeBytes = apkSize,
                            htmlUrl = htmlUrl
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                Result.failure(IOException(e.message ?: "Bağlantı hatası"))
            }
        }

    suspend fun downloadApk(
        context: Context,
        release: AppUpdateRelease,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!isTrustedDownloadUrl(release.apkUrl)) {
            return@withContext Result.failure(SecurityException("Güvenilmeyen indirme adresi engellendi"))
        }

        val updatesDir = File(context.cacheDir, UPDATES_SUBDIR).apply { mkdirs() }
        val targetFile = File(updatesDir, APK_FILE_NAME)
        val partFile = File(updatesDir, "$APK_FILE_NAME.part")
        partFile.delete()

        try {
            val request = Request.Builder()
                .url(release.apkUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "projekt_b-d/${BuildConfig.VERSION_NAME}")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (!isTrustedDownloadUrl(finalUrl)) {
                    return@withContext Result.failure(SecurityException("Güvenilmeyen yönlendirme engellendi"))
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("İndirme başarısız (HTTP ${response.code})"))
                }

                val body = response.body ?: return@withContext Result.failure(IOException("Boş APK akışı"))
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                if (totalBytes > MAX_APK_BYTES) {
                    return@withContext Result.failure(IOException("APK dosyası beklenenden büyük"))
                }

                var downloaded = 0L
                var lastEmitPercent = -1
                var checkedMagic = false

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (!checkedMagic) {
                                checkedMagic = true
                                if (read < 4 ||
                                    buffer[0] != 0x50.toByte() ||
                                    buffer[1] != 0x4B.toByte() ||
                                    buffer[2] != 0x03.toByte() ||
                                    buffer[3] != 0x04.toByte()
                                ) {
                                    throw IOException("Geçersiz APK dosyası")
                                }
                            }
                            downloaded += read
                            if (downloaded > MAX_APK_BYTES) {
                                throw IOException("APK boyut sınırı aşıldı")
                            }
                            output.write(buffer, 0, read)
                            val percent = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                0
                            }
                            if (percent != lastEmitPercent) {
                                lastEmitPercent = percent
                                onProgress(percent, downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }

                if (!checkedMagic || downloaded < 1024L) {
                    throw IOException("Eksik APK dosyası")
                }

                targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(100, downloaded, totalBytes.coerceAtLeast(downloaded))
                Result.success(targetFile)
            }
        } catch (e: CancellationException) {
            partFile.delete()
            throw e
        } catch (e: Exception) {
            partFile.delete()
            Log.w(TAG, "APK download failed", e)
            Result.failure(e)
        }
    }

    fun cleanupCachedApks(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, UPDATES_SUBDIR)
            if (dir.exists()) {
                dir.listFiles()?.forEach { it.delete() }
            }
        }
    }

    fun launchInstaller(context: Context, apkFile: File): InstallLaunchResult {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return InstallLaunchResult.Failed("İndirilen APK bulunamadı")
        }
        return try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                return InstallLaunchResult.NeedsPermission
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            InstallLaunchResult.Launched
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer", e)
            InstallLaunchResult.Failed(e.message ?: "Kurulum başlatılamadı")
        }
    }
}
