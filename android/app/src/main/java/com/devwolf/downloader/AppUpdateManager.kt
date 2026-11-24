package com.devwolf.downloader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.devwolf.downloader.resolvers.ErrorClassifier
import com.devwolf.downloader.resolvers.ResolverException
import com.devwolf.downloader.resolvers.SafeHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updater backed by the public GitHub Releases of [REPO_OWNER]/[REPO_NAME].
 *
 * - Compares the installed `versionName` with the newest release (optionally including
 *   pre-releases) using SemVer ordering (`3.0.0-rc.7 < 3.0.0-rc.8 < 3.0.0`).
 * - Downloads the APK matching the device ABI (fallback: universal) into `filesDir/updates/`,
 *   verifying the GitHub `sha256` digest when published, and keeps it so installation can be
 *   retried later (e.g. after granting "install unknown apps") without downloading again.
 * - Refuses to hand an APK to the system installer when its package name or signing
 *   certificate differs from the installed app (Android would reject it anyway).
 */
object AppUpdateManager {

    const val REPO_OWNER = "rafadgarcia"
    const val REPO_NAME = "devwolf-downloader"
    private const val RELEASES_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases?per_page=30"
    private const val PREFS = "devwolf_app_update"
    private const val KEY_LATEST = "latest_release"
    private const val KEY_DOWNLOADED = "downloaded_apk"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val UPDATES_DIR = "updates"

    private val downloading = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    // ---- Pure logic (unit-tested) -------------------------------------------------------------

    data class AppVersion(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: List<String>,
    ) : Comparable<AppVersion> {
        override fun compareTo(other: AppVersion): Int {
            compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
            compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
            compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
            // A release without pre-release identifiers ranks above any pre-release.
            if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
            if (preRelease.isEmpty()) return 1
            if (other.preRelease.isEmpty()) return -1
            for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
                val a = preRelease[i]
                val b = other.preRelease[i]
                val an = a.toIntOrNull()
                val bn = b.toIntOrNull()
                val cmp = when {
                    an != null && bn != null -> an.compareTo(bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> a.compareTo(b)
                }
                if (cmp != 0) return cmp
            }
            return preRelease.size.compareTo(other.preRelease.size)
        }

        val isPreRelease: Boolean get() = preRelease.isNotEmpty()

        companion object {
            private val REGEX = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+.*)?$""")

            fun parse(raw: String?): AppVersion? {
                val m = REGEX.matchEntire(raw?.trim().orEmpty()) ?: return null
                return AppVersion(
                    major = m.groupValues[1].toInt(),
                    minor = m.groupValues[2].toInt(),
                    patch = m.groupValues[3].toIntOrNull() ?: 0,
                    preRelease = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList(),
                )
            }
        }
    }

    data class ReleaseAsset(
        val name: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val sha256: String?,
    )

    data class ReleaseInfo(
        val tag: String,
        val version: AppVersion,
        val name: String,
        val notes: String,
        val htmlUrl: String,
        val publishedAt: String?,
        val isPreRelease: Boolean,
        val assets: List<ReleaseAsset>,
    )

    /** Picks the highest-versioned non-draft release with at least one APK asset. */
    fun pickLatestRelease(releases: JSONArray, includePreReleases: Boolean): ReleaseInfo? {
        var best: ReleaseInfo? = null
        for (i in 0 until releases.length()) {
            val r = releases.optJSONObject(i) ?: continue
            if (r.optBoolean("draft", false)) continue
            val pre = r.optBoolean("prerelease", false)
            val tag = r.optString("tag_name")
            val version = AppVersion.parse(tag) ?: continue
            if ((pre || version.isPreRelease) && !includePreReleases) continue
            val assets = parseApkAssets(r.optJSONArray("assets"))
            if (assets.isEmpty()) continue
            val info = ReleaseInfo(
                tag = tag,
                version = version,
                name = r.optString("name").ifBlank { tag },
                notes = r.optString("body").take(4_000),
                htmlUrl = r.optString("html_url"),
                publishedAt = r.optString("published_at").takeIf { it.isNotBlank() && it != "null" },
                isPreRelease = pre || version.isPreRelease,
                assets = assets,
            )
            if (best == null || info.version > best.version) best = info
        }
        return best
    }

    private fun parseApkAssets(arr: JSONArray?): List<ReleaseAsset> {
        if (arr == null) return emptyList()
        val out = mutableListOf<ReleaseAsset>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (!name.endsWith(".apk", ignoreCase = true) || url.isBlank()) continue
            val digest = a.optString("digest").takeIf { it.startsWith("sha256:", ignoreCase = true) }
            out += ReleaseAsset(
                name = name,
                downloadUrl = url,
                sizeBytes = a.optLong("size", -1L),
                sha256 = digest?.substringAfter(':')?.lowercase(),
            )
        }
        return out
    }

    /**
     * Prefers `*-<abi>-release.apk` for the device ABIs (in preference order), then the
     * universal APK, then any APK.
     */
    fun pickAssetForAbis(assets: List<ReleaseAsset>, supportedAbis: List<String>): ReleaseAsset? {
        for (abi in supportedAbis) {
            assets.firstOrNull { it.name.contains("-$abi-", ignoreCase = true) || it.name.contains("-$abi.", ignoreCase = true) }
                ?.let { return it }
        }
        return assets.firstOrNull { it.name.contains("universal", ignoreCase = true) } ?: assets.firstOrNull()
    }

    // ---- Device operations --------------------------------------------------------------------

    fun installedVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun updatesDir(context: Context) = File(context.applicationContext.filesDir, UPDATES_DIR)

    /** Removes downloaded APKs that are not newer than the installed app (update already applied). */
    fun cleanupObsoleteDownloads(context: Context) {
        if (downloading.get()) return
        val stored = readDownloaded(context) ?: run {
            // Orphan files without metadata (e.g. interrupted downloads) are useless.
            updatesDir(context).listFiles()?.forEach { it.deleteRecursively() }
            return
        }
        val installed = AppVersion.parse(installedVersionName(context))
        val downloadedVersion = AppVersion.parse(stored.optString("tag"))
        val file = File(stored.optString("filePath"))
        if (installed != null && downloadedVersion != null && downloadedVersion <= installed || !file.exists()) {
            deleteDownloaded(context)
        }
        updatesDir(context).listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun deleteDownloaded(context: Context) {
        updatesDir(context).listFiles()?.forEach { it.deleteRecursively() }
        prefs(context).edit().remove(KEY_DOWNLOADED).apply()
    }

    private fun readDownloaded(context: Context): JSONObject? {
        val raw = prefs(context).getString(KEY_DOWNLOADED, null) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    private fun readLatest(context: Context): JSONObject? {
        val raw = prefs(context).getString(KEY_LATEST, null) ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    /** Current state for the UI, without network access. */
    fun getState(context: Context): JSONObject {
        val installed = installedVersionName(context)
        val installedVersion = AppVersion.parse(installed)
        val latest = readLatest(context)
        val downloaded = readDownloaded(context)?.takeIf { File(it.optString("filePath")).isFile }
        val latestVersion = AppVersion.parse(latest?.optString("tag"))
        val updateAvailable = latestVersion != null && installedVersion != null && latestVersion > installedVersion
        val downloadedMatchesLatest = downloaded != null && latest != null &&
            downloaded.optString("tag") == latest.optString("tag")
        return JSONObject().apply {
            put("currentVersion", installed)
            put("latest", latest ?: JSONObject.NULL)
            put("updateAvailable", updateAvailable)
            put("downloaded", downloaded ?: JSONObject.NULL)
            put("downloadedReady", downloadedMatchesLatest && updateAvailable)
            put("downloading", downloading.get())
            put("canRequestInstall", canRequestPackageInstalls(context))
            put("lastCheckAt", prefs(context).getLong(KEY_LAST_CHECK_AT, 0L).takeIf { it > 0L } ?: JSONObject.NULL)
            put("repository", "$REPO_OWNER/$REPO_NAME")
        }
    }

    /** Queries GitHub Releases and stores the newest applicable release. */
    fun checkForUpdate(context: Context, includePreReleases: Boolean): JSONObject {
        val response = SafeHttpClient.shared.getText(
            url = RELEASES_API,
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to "2022-11-28",
                "User-Agent" to "DevwolfDownloader-Updater",
            ),
            maxBodyBytes = 4L * 1024L * 1024L,
        )
        when (response.statusCode) {
            in 200..299 -> Unit
            404 -> throw IllegalStateException(
                "Repositório de releases não encontrado ou privado ($REPO_OWNER/$REPO_NAME).",
            )
            403, 429 -> throw IllegalStateException(
                "Limite de consultas ao GitHub atingido. Tente novamente em alguns minutos.",
            )
            else -> throw IllegalStateException("GitHub respondeu HTTP ${response.statusCode} ao consultar releases.")
        }
        val release = pickLatestRelease(JSONArray(response.body), includePreReleases)
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val asset = release?.let { pickAssetForAbis(it.assets, abis) }
        val editor = prefs(context).edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
        if (release != null && asset != null) {
            val latestJson = JSONObject().apply {
                put("tag", release.tag)
                put("version", release.tag.removePrefix("v"))
                put("name", release.name)
                put("notes", release.notes)
                put("htmlUrl", release.htmlUrl)
                put("publishedAt", release.publishedAt ?: JSONObject.NULL)
                put("isPreRelease", release.isPreRelease)
                put("assetName", asset.name)
                put("assetUrl", asset.downloadUrl)
                put("assetSizeBytes", asset.sizeBytes)
                put("assetSha256", asset.sha256 ?: JSONObject.NULL)
            }
            editor.putString(KEY_LATEST, latestJson.toString())
        } else {
            editor.remove(KEY_LATEST)
        }
        editor.apply()
        cleanupObsoleteDownloads(context)
        return getState(context)
    }

    fun cancelDownload() {
        cancelRequested.set(true)
    }

    /**
     * Downloads the APK of the stored latest release. Returns immediately with the existing file
     * when it was already downloaded (and still verifies).
     */
    fun downloadLatest(context: Context, onProgress: (JSONObject) -> Unit): JSONObject {
        val latest = readLatest(context) ?: throw IllegalStateException("Verifique atualizações antes de baixar.")
        val tag = latest.optString("tag")
        val assetName = latest.optString("assetName")
        val assetUrl = latest.optString("assetUrl")
        val expectedSize = latest.optLong("assetSizeBytes", -1L)
        val expectedSha = latest.optString("assetSha256").takeIf { it.isNotBlank() && it != "null" }

        readDownloaded(context)?.let { existing ->
            val existingFile = File(existing.optString("filePath"))
            if (existing.optString("tag") == tag && existingFile.isFile) {
                return getState(context)
            }
        }

        if (!downloading.compareAndSet(false, true)) {
            throw IllegalStateException("Já existe um download de atualização em andamento.")
        }
        cancelRequested.set(false)
        val dir = File(updatesDir(context), tag.replace(Regex("[^0-9A-Za-z._-]"), "_")).apply { mkdirs() }
        val safeName = assetName.replace(Regex("[^0-9A-Za-z._-]"), "_")
        val partFile = File(dir, "$safeName.part")
        val finalFile = File(dir, safeName)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            SafeHttpClient.shared.executeStreamingGet(
                url = assetUrl,
                headers = mapOf("User-Agent" to "DevwolfDownloader-Updater", "Accept" to "application/octet-stream"),
                cancellationCheck = { cancelRequested.get() },
            ) { response, _, _, _ ->
                if (response.code !in 200..299) {
                    throw ResolverException(
                        errorCode = ErrorClassifier.fromHttpStatus(response.code),
                        userMessage = "Falha ao baixar o APK (HTTP ${response.code}).",
                        httpStatus = response.code,
                    )
                }
                val body = response.body ?: throw IllegalStateException("Resposta sem conteúdo ao baixar o APK.")
                val total = body.contentLength().takeIf { it > 0L } ?: expectedSize.takeIf { it > 0L }
                var downloaded = 0L
                var lastEmit = 0L
                body.byteStream().use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelRequested.get()) throw InterruptedException("Download da atualização cancelado.")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                                lastEmit = now
                                onProgress(progressJson(tag, downloaded, total, "downloading"))
                            }
                        }
                    }
                }
                if (expectedSize > 0L && downloaded != expectedSize) {
                    throw IllegalStateException("Download incompleto do APK ($downloaded de $expectedSize bytes).")
                }
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha != null && !actualSha.equals(expectedSha, ignoreCase = true)) {
                throw IllegalStateException("O APK baixado não confere com o hash SHA-256 publicado no release.")
            }
            val verification = verifyApk(context, partFile)
            if (!verification.optBoolean("packageMatches")) {
                throw IllegalStateException("O APK baixado não pertence ao Devwolf Downloader.")
            }
            // Keep only this version on disk.
            updatesDir(context).listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) throw IllegalStateException("Não foi possível finalizar o arquivo do APK.")
            val stored = JSONObject().apply {
                put("tag", tag)
                put("version", tag.removePrefix("v"))
                put("fileName", finalFile.name)
                put("filePath", finalFile.absolutePath)
                put("sizeBytes", finalFile.length())
                put("sha256", actualSha)
                put("signatureMatches", verification.optBoolean("signatureMatches"))
                put("downloadedAt", System.currentTimeMillis())
            }
            prefs(context).edit().putString(KEY_DOWNLOADED, stored.toString()).apply()
            onProgress(progressJson(tag, finalFile.length(), finalFile.length(), "downloaded"))
            return getState(context)
        } catch (e: Throwable) {
            partFile.delete()
            val cancelled = cancelRequested.get() || e is InterruptedException
            onProgress(progressJson(tag, 0L, null, if (cancelled) "cancelled" else "failed"))
            if (cancelled) throw IllegalStateException("Download da atualização cancelado.")
            throw e
        } finally {
            downloading.set(false)
            cancelRequested.set(false)
        }
    }

    private fun progressJson(tag: String, downloaded: Long, total: Long?, status: String) = JSONObject().apply {
        put("tag", tag)
        put("status", status)
        put("bytesDownloaded", downloaded)
        put("totalBytes", total ?: JSONObject.NULL)
        put("percent", if (total != null && total > 0L) (downloaded * 100.0 / total).coerceIn(0.0, 100.0) else JSONObject.NULL)
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** Intent to the per-app "Install unknown apps" screen (Android 8+). */
    fun unknownSourcesSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Builds the system installer intent for the downloaded APK, or throws with a user-facing
     * reason. The caller must check [canRequestPackageInstalls] first.
     */
    fun buildInstallIntent(context: Context): Intent {
        val stored = readDownloaded(context) ?: throw IllegalStateException("Nenhuma atualização baixada.")
        val file = File(stored.optString("filePath"))
        if (!file.isFile) {
            deleteDownloaded(context)
            throw IllegalStateException("O APK baixado não foi encontrado. Baixe novamente.")
        }
        val verification = verifyApk(context, file)
        if (!verification.optBoolean("packageMatches")) {
            throw IllegalStateException("O APK baixado não pertence ao Devwolf Downloader.")
        }
        if (verification.optBoolean("signatureKnown") && !verification.optBoolean("signatureMatches")) {
            throw IllegalStateException(
                "O APK foi assinado com outra chave e o Android vai recusar a atualização. " +
                    "Seria preciso desinstalar o app atual (perdendo fila e sessões) e instalar manualmente.",
            )
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Checks package name and signing certificates of an APK against the installed app. */
    fun verifyApk(context: Context, apk: File): JSONObject {
        val pm = context.packageManager
        val result = JSONObject()
        val archive: PackageInfo? = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            }
        } catch (_: Exception) {
            null
        }
        result.put("packageMatches", archive?.packageName == context.packageName)
        result.put("archiveVersionName", archive?.versionName ?: JSONObject.NULL)
        val installed: PackageInfo? = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
        } catch (_: Exception) {
            null
        }
        val archiveCerts = archive?.let { signerDigests(it) }.orEmpty()
        val installedCerts = installed?.let { signerDigests(it) }.orEmpty()
        val known = archiveCerts.isNotEmpty() && installedCerts.isNotEmpty()
        result.put("signatureKnown", known)
        result.put("signatureMatches", known && archiveCerts == installedCerts)
        return result
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            info.signatures
        } ?: return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return signatures.mapTo(HashSet()) { sig -> md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }
    }
}
