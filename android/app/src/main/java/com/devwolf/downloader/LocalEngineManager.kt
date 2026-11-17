package com.devwolf.downloader

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages initialization, version discovery, safe updates, and binary rollback for the
 * on-device yt-dlp + FFmpeg engine. Blocks updates while downloads are active.
 */
object LocalEngineManager {
    private val initialized = AtomicBoolean(false)
    private val initLock = Any()
    private val activeDownloads = AtomicInteger(0)

    fun ensureInitialized(context: Context) {
        if (initialized.get()) return
        synchronized(initLock) {
            if (initialized.get()) return
            val appCtx = context.applicationContext
            YoutubeDL.getInstance().init(appCtx)
            FFmpeg.getInstance().init(appCtx)
            try {
                Aria2c.getInstance().init(appCtx)
            } catch (_: Throwable) {
                // Aria2c is optional; yt-dlp works without it
            }
            initialized.set(true)
        }
    }

    fun incrementActiveDownloads(): Int = activeDownloads.incrementAndGet()

    fun decrementActiveDownloads(): Int = activeDownloads.updateAndGet { current ->
        if (current > 0) current - 1 else 0
    }

    fun activeDownloadCount(): Int = activeDownloads.get()

    fun getInstalledYtdlpVersion(context: Context): String? {
        return try {
            ensureInitialized(context)
            YoutubeDL.getInstance().version(context.applicationContext)
        } catch (_: Throwable) {
            null
        }
    }

    fun getFfmpegVersion(context: Context): String? {
        return try {
            ensureInitialized(context)
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

    fun getStatus(context: Context, checkRemote: Boolean = true): JSONObject {
        val appCtx = context.applicationContext
        val vault = SecureVault(appCtx)
        val installed = getInstalledYtdlpVersion(appCtx)
        val ffmpegVersion = getFfmpegVersion(appCtx)

        if (checkRemote) {
            val latest = fetchLatestYtdlpTag()
            if (latest != null) {
                vault.latestKnownYtdlpVersion = latest
                vault.lastCheckAt = Instant.now().toString()
            }
        }

        val latestKnown = vault.latestKnownYtdlpVersion
        val updateAvailable = !installed.isNullOrBlank() &&
            !latestKnown.isNullOrBlank() &&
            normalizeVer(installed) != normalizeVer(latestKnown)

        val active = activeDownloads.get()
        return JSONObject().apply {
            put("ytdlpVersion", installed ?: JSONObject.NULL)
            put("latestYtdlpVersion", latestKnown ?: JSONObject.NULL)
            put("updateAvailable", updateAvailable)
            put("ffmpegVersion", ffmpegVersion ?: JSONObject.NULL)
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
        if (activeDownloads.get() > 0) {
            return JSONObject().apply {
                put("updated", false)
                put("version", getInstalledYtdlpVersion(appCtx) ?: JSONObject.NULL)
                put("previousVersion", vault.previousYtdlpVersion ?: JSONObject.NULL)
                put("message", "Atualização bloqueada enquanto há downloads ativos.")
            }
        }

        ensureInitialized(appCtx)
        val currentVersion = getInstalledYtdlpVersion(appCtx)
        val backupSuccess = backupYtdlpBinary(appCtx)
        val channel = if (channelName.equals("nightly", ignoreCase = true)) {
            YoutubeDL.UpdateChannel.NIGHTLY
        } else {
            YoutubeDL.UpdateChannel.STABLE
        }

        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(appCtx, channel)
            val newVersion = getInstalledYtdlpVersion(appCtx)
            if (newVersion.isNullOrBlank()) {
                // Binary validation failed after update -> attempt automatic rollback
                if (backupSuccess) restoreBackupYtdlpBinary(appCtx)
                vault.lastEngineError = "Validação do binário yt-dlp falhou após atualização; rollback aplicado."
                return JSONObject().apply {
                    put("updated", false)
                    put("version", getInstalledYtdlpVersion(appCtx) ?: JSONObject.NULL)
                    put("previousVersion", currentVersion ?: JSONObject.NULL)
                    put("message", vault.lastEngineError)
                }
            }

            if (!currentVersion.isNullOrBlank() && currentVersion != newVersion) {
                vault.previousYtdlpVersion = currentVersion
            }
            vault.lastCheckAt = Instant.now().toString()
            vault.lastUpdateAt = Instant.now().toString()
            vault.lastEngineError = null
            vault.latestKnownYtdlpVersion = newVersion

            val updated = status == YoutubeDL.UpdateStatus.DONE || (currentVersion != newVersion)
            val message = if (updated) {
                "yt-dlp atualizado para $newVersion"
            } else {
                "yt-dlp já está na versão mais recente ($newVersion)"
            }
            JSONObject().apply {
                put("updated", updated)
                put("version", newVersion)
                put("previousVersion", vault.previousYtdlpVersion ?: JSONObject.NULL)
                put("message", message)
            }
        } catch (e: Throwable) {
            if (backupSuccess) {
                restoreBackupYtdlpBinary(appCtx)
            }
            val msg = "Falha ao atualizar yt-dlp: ${e.message ?: "erro desconhecido"}"
            vault.lastEngineError = msg
            JSONObject().apply {
                put("updated", false)
                put("version", getInstalledYtdlpVersion(appCtx) ?: JSONObject.NULL)
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
                put("message", "Rollback bloqueado enquanto há downloads ativos.")
            }
        }
        val restored = restoreBackupYtdlpBinary(appCtx)
        val versionAfter = getInstalledYtdlpVersion(appCtx)
        val msg = if (restored && !versionAfter.isNullOrBlank()) {
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

    private fun fetchLatestYtdlpTag(): String? {
        return try {
            val conn = (URL("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DevwolfDownloader/2.0.0")
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

    private fun normalizeVer(v: String): String = v.trim().removePrefix("v").replace(".0", ".")

    private inline fun <T> HttpURLConnection.useDisconnect(block: () -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
