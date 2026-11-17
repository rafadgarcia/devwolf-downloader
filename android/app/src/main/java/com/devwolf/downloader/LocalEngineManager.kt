package com.devwolf.downloader

import android.content.Context
import com.devwolf.downloader.resolvers.EngineRouter
import com.devwolf.downloader.resolvers.GalleryDlLocalResolver
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages lazy initialization, real binary version discovery, daily/manual updates
 * (Stable & Nightly), and binary rollback for the on-device yt-dlp + FFmpeg engines.
 * Never initializes Python/yt-dlp unless a job or status/update action requires it.
 */
object LocalEngineManager {
    const val WRAPPER_VERSION = "0.18.1"
    private const val PREFS_NAME = "devwolf_engine_meta"
    private const val KEY_CHANNEL = "preferred_update_channel"
    private const val KEY_CACHED_YTDLP_VER = "cached_ytdlp_version"
    private const val AUTO_UPDATE_INTERVAL_HOURS = 24L

    private val ytdlpInitialized = AtomicBoolean(false)
    private val ffmpegInitialized = AtomicBoolean(false)
    private val ytdlpLock = Any()
    private val ffmpegLock = Any()
    private val activeDownloads = AtomicInteger(0)
    private val autoUpdateInFlight = AtomicBoolean(false)

    fun isYtdlpInitialized(): Boolean = ytdlpInitialized.get()

    fun isFfmpegInitialized(): Boolean = ffmpegInitialized.get()

    fun ensureYtdlpInitialized(context: Context): Long {
        if (ytdlpInitialized.get()) return 0L
        synchronized(ytdlpLock) {
            if (ytdlpInitialized.get()) return 0L
            val start = System.currentTimeMillis()
            val appCtx = context.applicationContext
            YoutubeDL.getInstance().init(appCtx)
            try {
                Aria2c.getInstance().init(appCtx)
            } catch (_: Throwable) {
                // Aria2c is optional; yt-dlp works without it
            }
            ytdlpInitialized.set(true)
            return (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }
    }

    fun ensureFfmpegInitialized(context: Context): Long {
        if (ffmpegInitialized.get()) return 0L
        synchronized(ffmpegLock) {
            if (ffmpegInitialized.get()) return 0L
            val start = System.currentTimeMillis()
            FFmpeg.getInstance().init(context.applicationContext)
            ffmpegInitialized.set(true)
            return (System.currentTimeMillis() - start).coerceAtLeast(1L)
        }
    }

    fun ensureInitialized(context: Context): Long {
        val t1 = ensureYtdlpInitialized(context)
        val t2 = ensureFfmpegInitialized(context)
        return t1 + t2
    }

    fun incrementActiveDownloads(): Int = activeDownloads.incrementAndGet()

    fun decrementActiveDownloads(): Int = activeDownloads.updateAndGet { current ->
        if (current > 0) current - 1 else 0
    }

    fun activeDownloadCount(): Int = activeDownloads.get()

    fun getPreferredChannel(context: Context): String {
        return SecureVault(context.applicationContext).updateChannel
    }

    fun setPreferredChannel(context: Context, channel: String) {
        SecureVault(context.applicationContext).updateChannel = channel
    }

    fun getCachedYtdlpVersion(context: Context): String? {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_YTDLP_VER, null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun cacheYtdlpVersion(context: Context, version: String?) {
        if (version.isNullOrBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHED_YTDLP_VER, version)
            .apply()
    }

    fun getInstalledYtdlpVersion(context: Context): String? {
        return try {
            ensureYtdlpInitialized(context)
            val realVersion = YoutubeDL.getInstance().version(context.applicationContext)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (realVersion != null) {
                cacheYtdlpVersion(context, realVersion)
            }
            realVersion
        } catch (_: Throwable) {
            getCachedYtdlpVersion(context)
        }
    }

    fun getFfmpegVersion(context: Context): String? {
        return try {
            ensureFfmpegInitialized(context)
            val ffmpeg = StatusModeProcessor.findFfmpegBinary(context.applicationContext) ?: return null
            val pb = ProcessBuilder(ffmpeg.absolutePath, "-version")
            pb.environment().putAll(StatusModeProcessor.buildEnv(context.applicationContext))
            val proc = pb.start()
            val firstLine = proc.inputStream.bufferedReader().readLine()
            proc.destroy()
            firstLine?.removePrefix("ffmpeg version ")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
                ?: if (ffmpeg.exists()) "bundled" else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Non-blocking automatic check/update triggered on first app startup and once every 24h.
     * Respects active downloads and falls back safely on network or validation failure.
     */
    fun maybeAutoUpdateInBackground(context: Context) {
        if (!autoUpdateInFlight.compareAndSet(false, true)) return
        try {
            val appCtx = context.applicationContext
            if (activeDownloads.get() > 0) return

            val vault = SecureVault(appCtx)
            val lastCheck = vault.lastCheckAt
            val shouldCheck = if (lastCheck.isNullOrBlank()) {
                true
            } else {
                try {
                    Duration.between(Instant.parse(lastCheck), Instant.now()).toHours() >= AUTO_UPDATE_INTERVAL_HOURS
                } catch (_: Exception) {
                    true
                }
            }
            if (!shouldCheck) return

            val channel = getPreferredChannel(appCtx)
            val installed = getInstalledYtdlpVersion(appCtx)
            val latest = fetchLatestYtdlpTag(channel)
            vault.lastCheckAt = Instant.now().toString()
            if (latest != null) {
                vault.latestKnownYtdlpVersion = latest
                val outdated = installed.isNullOrBlank() || compareVersions(installed, latest) < 0
                if (outdated && activeDownloads.get() == 0) {
                    updateEngine(appCtx, channel)
                }
            }
        } catch (_: Throwable) {
            // Automatic background check must never crash app startup
        } finally {
            autoUpdateInFlight.set(false)
        }
    }

    fun getStatus(context: Context, checkRemote: Boolean = true): JSONObject {
        val appCtx = context.applicationContext
        val vault = SecureVault(appCtx)
        val channel = getPreferredChannel(appCtx)
        val installed = getInstalledYtdlpVersion(appCtx)
        val ffmpegVersion = getFfmpegVersion(appCtx)

        if (checkRemote) {
            val latest = fetchLatestYtdlpTag(channel)
            if (latest != null) {
                vault.latestKnownYtdlpVersion = latest
                vault.lastCheckAt = Instant.now().toString()
            }
        }

        val latestKnown = vault.latestKnownYtdlpVersion
        val updateAvailable = !installed.isNullOrBlank() &&
            !latestKnown.isNullOrBlank() &&
            compareVersions(installed, latestKnown) < 0

        val active = activeDownloads.get()
        val dnrTelemetry = EngineRouter.getDnrTelemetry()
        val dnrStrategies = JSONArray(dnrTelemetry.strategies)
        val dnrAvgMs = PerformanceTracker.getDnrAvgResolutionMs() ?: dnrTelemetry.avgResolutionMs
        val galleryCaps = GalleryDlLocalResolver.inspectCapabilities(appCtx)

        return JSONObject().apply {
            put("ytdlpVersion", installed ?: JSONObject.NULL)
            put("wrapperVersion", WRAPPER_VERSION)
            put("latestYtdlpVersion", latestKnown ?: JSONObject.NULL)
            put("updateAvailable", updateAvailable)
            put("updateChannel", channel)
            put("ffmpegVersion", ffmpegVersion ?: JSONObject.NULL)
            put("gallerydlVersion", galleryCaps.version)
            put("localResolversStatus", "online")
            put("dnrStrategies", dnrStrategies)
            put("dnrAvgResolutionMs", dnrAvgMs ?: JSONObject.NULL)
            put("previousVersion", vault.previousYtdlpVersion ?: JSONObject.NULL)
            put("lastCheckAt", vault.lastCheckAt ?: JSONObject.NULL)
            put("lastUpdateAt", vault.lastUpdateAt ?: JSONObject.NULL)
            put("activeDownloads", active)
            put("canUpdate", active == 0)
            put("lastError", vault.lastEngineError ?: JSONObject.NULL)
        }
    }

    fun updateEngine(context: Context, channelName: String?): JSONObject {
        val appCtx = context.applicationContext
        val vault = SecureVault(appCtx)
        if (!channelName.isNullOrBlank()) {
            setPreferredChannel(appCtx, channelName)
        }
        val activeChannel = getPreferredChannel(appCtx)

        if (activeDownloads.get() > 0) {
            return JSONObject().apply {
                put("updated", false)
                put("version", getInstalledYtdlpVersion(appCtx) ?: JSONObject.NULL)
                put("wrapperVersion", WRAPPER_VERSION)
                put("updateChannel", activeChannel)
                put("previousVersion", vault.previousYtdlpVersion ?: JSONObject.NULL)
                put("canUpdate", false)
                put("message", "Atualização bloqueada enquanto há downloads ativos.")
            }
        }

        ensureYtdlpInitialized(appCtx)
        val currentVersion = getInstalledYtdlpVersion(appCtx)
        val backupSuccess = backupYtdlpBinary(appCtx)
        val channel = if (activeChannel == "nightly") {
            YoutubeDL.UpdateChannel.NIGHTLY
        } else {
            YoutubeDL.UpdateChannel.STABLE
        }

        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(appCtx, channel)
            val newVersion = getInstalledYtdlpVersion(appCtx)
            if (newVersion.isNullOrBlank()) {
                if (backupSuccess) restoreBackupYtdlpBinary(appCtx)
                vault.lastEngineError = "Validação do binário yt-dlp falhou após atualização; rollback aplicado."
                return JSONObject().apply {
                    put("updated", false)
                    put("version", getInstalledYtdlpVersion(appCtx) ?: JSONObject.NULL)
                    put("wrapperVersion", WRAPPER_VERSION)
                    put("updateChannel", activeChannel)
                    put("previousVersion", currentVersion ?: JSONObject.NULL)
                    put("message", vault.lastEngineError)
                }
            }

            if (!currentVersion.isNullOrBlank() && currentVersion != newVersion) {
                vault.previousYtdlpVersion = currentVersion
            }
            cacheYtdlpVersion(appCtx, newVersion)
            val nowIso = Instant.now().toString()
            vault.lastCheckAt = nowIso
            vault.lastUpdateAt = nowIso
            vault.lastEngineError = null
            vault.latestKnownYtdlpVersion = newVersion

            val updated = status == YoutubeDL.UpdateStatus.DONE || (currentVersion != newVersion)
            val message = if (updated) {
                "yt-dlp ($activeChannel) atualizado para $newVersion"
            } else {
                "yt-dlp ($activeChannel) já está na versão mais recente ($newVersion)"
            }
            JSONObject().apply {
                put("updated", updated)
                put("version", newVersion)
                put("wrapperVersion", WRAPPER_VERSION)
                put("updateChannel", activeChannel)
                put("previousVersion", vault.previousYtdlpVersion ?: JSONObject.NULL)
                put("lastUpdateAt", nowIso)
                put("message", message)
            }
        } catch (e: Throwable) {
            if (backupSuccess) {
                restoreBackupYtdlpBinary(appCtx)
            }
            val msg = "Falha ao atualizar yt-dlp ($activeChannel): ${e.message ?: "erro desconhecido"}"
            vault.lastEngineError = msg
            JSONObject().apply {
                put("updated", false)
                put("version", getInstalledYtdlpVersion(appCtx) ?: JSONObject.NULL)
                put("wrapperVersion", WRAPPER_VERSION)
                put("updateChannel", activeChannel)
                put("previousVersion", currentVersion ?: JSONObject.NULL)
                put("message", msg)
            }
        }
    }

    fun rollbackEngine(context: Context): JSONObject {
        val appCtx = context.applicationContext
        val vault = SecureVault(appCtx)
        if (activeDownloads.get() > 0) {
            return JSONObject().apply {
                put("rolledBack", false)
                put("version", getInstalledYtdlpVersion(appCtx) ?: JSONObject.NULL)
                put("canUpdate", false)
                put("message", "Rollback bloqueado enquanto há downloads ativos.")
            }
        }
        ensureYtdlpInitialized(appCtx)
        val restored = restoreBackupYtdlpBinary(appCtx)
        val versionAfter = getInstalledYtdlpVersion(appCtx)
        val msg = if (restored && !versionAfter.isNullOrBlank()) {
            cacheYtdlpVersion(appCtx, versionAfter)
            vault.lastEngineError = null
            "Rollback concluído para a versão $versionAfter"
        } else {
            "Nenhum backup funcional anterior do yt-dlp disponível para rollback."
        }
        return JSONObject().apply {
            put("rolledBack", restored && !versionAfter.isNullOrBlank())
            put("version", versionAfter ?: JSONObject.NULL)
            put("message", msg)
        }
    }

    internal fun compareVersions(a: String, b: String): Int {
        val partsA = a.trim().removePrefix("v").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull() }
        val partsB = b.trim().removePrefix("v").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toLongOrNull() }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0L }
            val vb = partsB.getOrElse(i) { 0L }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun findYtdlpBinary(context: Context): File? {
        val candidates = listOf(
            File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp"),
            File(context.filesDir, "youtubedl-android/yt-dlp/yt-dlp"),
            File(context.noBackupFilesDir, "youtubedl-android/packages/yt-dlp"),
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    private fun backupYtdlpBinary(context: Context): Boolean {
        val src = findYtdlpBinary(context) ?: return false
        val backupDir = File(context.filesDir, "devwolf-ytdlp-backup")
        backupDir.mkdirs()
        val dst = File(backupDir, "yt-dlp.bak")
        return try {
            src.copyTo(dst, overwrite = true)
            File(backupDir, "target_path.txt").writeText(src.absolutePath)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreBackupYtdlpBinary(context: Context): Boolean {
        val backupDir = File(context.filesDir, "devwolf-ytdlp-backup")
        val bak = File(backupDir, "yt-dlp.bak")
        val pathFile = File(backupDir, "target_path.txt")
        if (!bak.exists() || !pathFile.exists()) return false
        val target = File(pathFile.readText().trim())
        return try {
            target.parentFile?.mkdirs()
            bak.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchLatestYtdlpTag(channel: String = "stable"): String? {
        val repo = if (channel.equals("nightly", ignoreCase = true)) {
            "yt-dlp/yt-dlp-nightly-builds"
        } else {
            "yt-dlp/yt-dlp"
        }
        return try {
            val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DevwolfDownloader/3.0.0")
            }
            conn.useDisconnect {
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().readText()
                    JSONObject(body).optString("tag_name").takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <T> HttpURLConnection.useDisconnect(block: () -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
