package com.devwolf.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * Foreground Service (dataSync) executing local yt-dlp downloads, FFmpeg WhatsApp Status Mode
 * processing, MediaStore saving, and real-time progress/completion notifications.
 */
class DevwolfDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var queueStore: LocalQueueStore
    private lateinit var secureVault: SecureVault

    companion object {
        const val CHANNEL_PROGRESS = "devwolf_downloads"
        const val CHANNEL_COMPLETED = "devwolf_completed"

        const val ACTION_ENQUEUE = "com.devwolf.downloader.action.ENQUEUE"
        const val ACTION_CANCEL = "com.devwolf.downloader.action.CANCEL"

        const val EXTRA_JOB_JSON = "extra_job_json"
        const val EXTRA_JOB_ID = "extra_job_id"

        private val listeners = CopyOnWriteArraySet<(JSONObject) -> Unit>()

        fun addProgressListener(listener: (JSONObject) -> Unit) {
            listeners.add(listener)
        }

        fun removeProgressListener(listener: (JSONObject) -> Unit) {
            listeners.remove(listener)
        }

        internal fun emitProgress(snapshot: JSONObject) {
            for (listener in listeners) {
                try {
                    listener(snapshot)
                } catch (_: Throwable) {
                }
            }
        }

        fun enqueueDownload(context: Context, jobPayload: JSONObject) {
            val intent = Intent(context, DevwolfDownloadService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra(EXTRA_JOB_JSON, jobPayload.toString())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context, jobId: String) {
            try {
                YoutubeDL.getInstance().destroyProcessById(jobId)
            } catch (_: Throwable) {
            }
            val intent = Intent(context, DevwolfDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            }
            context.startService(intent)
        }

        internal fun parseYtdlpProgressLine(line: String): ProgressParseResult? {
            if (!line.contains("[download]")) return null
            val percentMatch = Regex("""(\d+(?:\.\d+)?)%""").find(line)
            val totalMatch = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""", RegexOption.IGNORE_CASE).find(line)
            val downloadedMatch = Regex("""\[download]\s+([\d.]+)\s*([KMGT]?i?B)\s+at""", RegexOption.IGNORE_CASE).find(line)
            val speedMatch = Regex("""at\s+([\d.]+)\s*([KMGT]?i?B)/s""", RegexOption.IGNORE_CASE).find(line)
            val etaMatch = Regex("""ETA\s+(\d+):(\d+)(?::(\d+))?""").find(line)

            val percent = percentMatch?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(0.0, 100.0)
            val totalBytes = totalMatch?.let { parseSizeBytes(it.groupValues[1], it.groupValues[2]) }
            val explicitDownloaded = downloadedMatch?.let { parseSizeBytes(it.groupValues[1], it.groupValues[2]) }
            val computedDownloaded = when {
                explicitDownloaded != null -> explicitDownloaded
                totalBytes != null && percent != null -> ((totalBytes * percent) / 100.0).toLong()
                else -> 0L
            }
            val speedBps = speedMatch?.let { parseSizeBytes(it.groupValues[1], it.groupValues[2]) }
            val etaSeconds = etaMatch?.let { m ->
                val p1 = m.groupValues[1].toLongOrNull() ?: 0L
                val p2 = m.groupValues[2].toLongOrNull() ?: 0L
                val p3 = m.groupValues[3].toLongOrNull()
                if (p3 != null) p1 * 3600 + p2 * 60 + p3 else p1 * 60 + p2
            }

            if (percent == null && explicitDownloaded == null && totalBytes == null) return null
            return ProgressParseResult(
                bytesDownloaded = computedDownloaded,
                totalBytes = totalBytes,
                percent = if (totalBytes != null) percent else null,
                isIndeterminate = totalBytes == null,
                speedBytesPerSec = speedBps,
                etaSeconds = etaSeconds,
            )
        }

        private fun parseSizeBytes(numStr: String, unit: String): Long? {
            val value = numStr.toDoubleOrNull() ?: return null
            val mult = when (unit.lowercase()) {
                "b" -> 1.0
                "kb" -> 1_000.0
                "kib" -> 1_024.0
                "mb" -> 1_000_000.0
                "mib" -> 1_048_576.0
                "gb" -> 1_000_000_000.0
                "gib" -> 1_073_741_824.0
                else -> 1.0
            }
            return (value * mult).toLong()
        }
    }

    data class ProgressParseResult(
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val percent: Double?,
        val isIndeterminate: Boolean,
        val speedBytesPerSec: Long?,
        val etaSeconds: Long?,
    )

    override fun onCreate() {
        super.onCreate()
        queueStore = LocalQueueStore(applicationContext)
        secureVault = SecureVault(applicationContext)
        createNotificationChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                val rawJson = intent.getStringExtra(EXTRA_JOB_JSON) ?: return START_NOT_STICKY
                val jobReq = try { JSONObject(rawJson) } catch (_: Exception) { return START_NOT_STICKY }
                val jobId = jobReq.optString("jobId")
                if (jobId.isBlank()) return START_NOT_STICKY

                promoteForeground(jobId, "Preparando download…", null, true)
                serviceScope.launch {
                    runDownloadJob(jobReq)
                }
            }
            ACTION_CANCEL -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
                if (jobId.isNotBlank()) {
                    try {
                        YoutubeDL.getInstance().destroyProcessById(jobId)
                    } catch (_: Throwable) {
                    }
                    markCancelled(jobId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runDownloadJob(jobReq: JSONObject) {
        val jobId = jobReq.getString("jobId")
        val url = jobReq.getString("url")
        val format = jobReq.optString("format", "auto")
        val quality = jobReq.optString("quality", "best")
        val statusMode = jobReq.optBoolean("statusMode", false)
        val segmentDuration = if (jobReq.has("segmentDurationSeconds") && !jobReq.isNull("segmentDurationSeconds")) {
            jobReq.optInt("segmentDurationSeconds", 0).takeIf { it > 0 }
        } else {
            null
        }
        val keepOriginal = jobReq.optBoolean("keepOriginal", false)
        val platform = UrlExtractor.detectPlatform(url)

        val workDir = File(cacheDir, "jobs/$jobId").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        LocalEngineManager.incrementActiveDownloads()
        var currentSnapshot = buildSnapshot(
            jobId = jobId,
            url = url,
            platform = platform,
            status = "resolving",
            phaseLabel = "Procurando mídia",
            bytesDownloaded = 0L,
            totalBytes = null,
            percent = null,
            isIndeterminate = true,
        )
        publishSnapshot(currentSnapshot)

        try {
            if (!UrlExtractor.isSafePublicHttpUrl(url)) {
                throw IllegalArgumentException("URL inválida ou bloqueada pela política de segurança.")
            }

            LocalEngineManager.ensureInitialized(applicationContext)

            // Resolve title & metadata quickly when possible (non-fatal if skipped)
            var resolvedTitle: String? = null
            try {
                val info = YoutubeDL.getInstance().getInfo(url)
                resolvedTitle = info.title?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
            }

            currentSnapshot = buildSnapshot(
                jobId = jobId,
                url = url,
                platform = platform,
                status = "downloading",
                phaseLabel = "Baixando",
                bytesDownloaded = 0L,
                totalBytes = null,
                percent = null,
                isIndeterminate = true,
                title = resolvedTitle,
            )
            publishSnapshot(currentSnapshot)
            promoteForeground(jobId, resolvedTitle ?: "Baixando mídia…", null, true)

            val request = buildYoutubeDlRequest(url, platform, format, quality, workDir)

            var lastNotifMs = 0L
            YoutubeDL.getInstance().execute(request, jobId) { progress, etaInSeconds, line ->
                val parsed = parseYtdlpProgressLine(line)
                val pct = parsed?.percent ?: if (progress >= 0f && parsed?.totalBytes != null) progress.toDouble() else null
                val bytesDown = parsed?.bytesDownloaded ?: currentSnapshot.optLong("bytesDownloaded", 0L)
                val totalB = parsed?.totalBytes ?: if (currentSnapshot.isNull("totalBytes")) null else currentSnapshot.optLong("totalBytes")
                val isIndet = totalB == null

                currentSnapshot = buildSnapshot(
                    jobId = jobId,
                    url = url,
                    platform = platform,
                    status = "downloading",
                    phaseLabel = "Baixando",
                    bytesDownloaded = bytesDown,
                    totalBytes = totalB,
                    percent = pct,
                    isIndeterminate = isIndet,
                    speedBytesPerSec = parsed?.speedBytesPerSec,
                    etaSeconds = parsed?.etaSeconds ?: etaInSeconds.takeIf { it > 0 },
                    title = resolvedTitle,
                )
                publishSnapshot(currentSnapshot)

                val now = System.currentTimeMillis()
                if (now - lastNotifMs > 600) {
                    lastNotifMs = now
                    promoteForeground(
                        jobId,
                        resolvedTitle ?: "Baixando via yt-dlp…",
                        pct?.roundToInt(),
                        isIndet,
                    )
                }
            }

            // Collect downloaded files inside isolated job dir
            val rawFiles = workDir.listFiles { f ->
                f.isFile && !f.name.endsWith(".part") && !f.name.endsWith(".ytdl") && f.length() > 0L
            }?.sortedBy { it.name }.orEmpty()

            if (rawFiles.isEmpty()) {
                throw IllegalStateException("Nenhum arquivo de mídia foi gerado pelo motor local.")
            }

            currentSnapshot = buildSnapshot(
                jobId = jobId,
                url = url,
                platform = platform,
                status = "processing",
                phaseLabel = "Preparando arquivo",
                bytesDownloaded = rawFiles.sumOf { it.length() },
                totalBytes = rawFiles.sumOf { it.length() },
                percent = 95.0,
                isIndeterminate = false,
                title = resolvedTitle,
            )
            publishSnapshot(currentSnapshot)
            promoteForeground(jobId, "Preparando arquivo…", 95, false)

            val processedItems = mutableListOf<ProcessedMediaItem>()
            for (rawFile in rawFiles) {
                if (statusMode || segmentDuration != null) {
                    processedItems.addAll(
                        StatusModeProcessor.processForStatusMode(
                            context = applicationContext,
                            sourceFile = rawFile,
                            workDir = workDir,
                            segmentDurationSeconds = segmentDuration,
                            keepOriginal = keepOriginal,
                        ),
                    )
                } else {
                    processedItems.add(
                        ProcessedMediaItem(
                            file = rawFile,
                            mimeType = StatusModeProcessor.guessMimeType(rawFile.name),
                            durationSeconds = StatusModeProcessor.probe(applicationContext, rawFile)?.durationSeconds,
                            isSegment = false,
                            segmentIndex = null,
                            isOriginal = true,
                        ),
                    )
                }
            }

            val outputFilesJson = JSONArray()
            var totalOutBytes = 0L
            processedItems.forEachIndexed { idx, item ->
                val baseTitle = resolvedTitle ?: item.file.nameWithoutExtension
                val suffix = when {
                    item.isSegment && item.segmentIndex != null -> "_parte${item.segmentIndex.toString().padStart(2, '0')}"
                    statusMode && !item.isOriginal -> "_status"
                    else -> ""
                }
                val preferredName = "${baseTitle}$suffix.${item.file.extension.ifBlank { "mp4" }}"
                val saved = MediaStoreHelper.saveFileToGallery(
                    context = applicationContext,
                    sourceFile = item.file,
                    mimeType = item.mimeType,
                    preferredName = preferredName,
                )
                totalOutBytes += saved.sizeBytes
                outputFilesJson.put(
                    JSONObject().apply {
                        put("id", "${jobId}_$idx")
                        put("fileName", saved.fileName)
                        put("filePath", "")
                        put("mediaUri", saved.mediaUri)
                        put("displayPath", saved.displayPath)
                        put("mimeType", item.mimeType)
                        put("sizeBytes", saved.sizeBytes)
                        put("durationSeconds", item.durationSeconds ?: JSONObject.NULL)
                        put("isSegment", item.isSegment)
                        put("segmentIndex", item.segmentIndex ?: JSONObject.NULL)
                        put("isOriginal", item.isOriginal)
                    },
                )
            }

            val readySnapshot = buildSnapshot(
                jobId = jobId,
                url = url,
                platform = platform,
                status = "ready",
                phaseLabel = "Pronto para baixar",
                bytesDownloaded = totalOutBytes,
                totalBytes = totalOutBytes,
                percent = 100.0,
                isIndeterminate = false,
                title = resolvedTitle,
                files = outputFilesJson,
            )
            publishSnapshot(readySnapshot)
            showCompletedNotification(jobId, resolvedTitle ?: "Mídia pronta", outputFilesJson)
        } catch (e: InterruptedException) {
            markCancelled(jobId, url, platform)
        } catch (e: Throwable) {
            val current = queueStore.getJob(jobId)
            if (current?.optString("status") == "cancelled") {
                // Already marked cancelled
            } else {
                val (code, userMessage) = classifyError(e)
                val failed = buildSnapshot(
                    jobId = jobId,
                    url = url,
                    platform = platform,
                    status = "failed",
                    phaseLabel = "Falhou",
                    bytesDownloaded = currentSnapshot.optLong("bytesDownloaded", 0L),
                    totalBytes = if (currentSnapshot.isNull("totalBytes")) null else currentSnapshot.optLong("totalBytes"),
                    percent = null,
                    isIndeterminate = false,
                    title = currentSnapshot.optString("title").takeIf { it.isNotBlank() },
                    error = userMessage,
                    errorCode = code,
                )
                publishSnapshot(failed)
            }
        } finally {
            workDir.deleteRecursively()
            val remaining = LocalEngineManager.decrementActiveDownloads()
            if (remaining == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun buildYoutubeDlRequest(
        url: String,
        platform: String,
        format: String,
        quality: String,
        workDir: File,
    ): YoutubeDLRequest {
        return YoutubeDLRequest(url).apply {
            addOption("-o", "${workDir.absolutePath}/%(title).60B_%(id)s.%(ext)s")
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--max-filesize", "500M")
            addOption("--newline")
            addOption("--progress")
            addOption("--restrict-filenames")

            if (format == "audio" || quality == "audio") {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
            } else {
                val fmtExpr = when (quality) {
                    "1080p" -> "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"
                    "720p" -> "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]/best"
                    "480p" -> "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=480]+bestaudio/best[height<=480]/best"
                    else -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
                }
                addOption("-f", fmtExpr)
                addOption("--merge-output-format", "mp4")
            }

            // Optional user-configured local session cookie (never sent to VPS)
            val cookie = secureVault.getCookieForPlatform(platform)
            if (!cookie.isNullOrBlank()) {
                addOption("--add-header", "Cookie:$cookie")
            }
        }
    }

    private fun classifyError(error: Throwable): Pair<String, String> {
        val raw = error.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            lower.contains("private") || lower.contains("login required") || lower.contains("sign in") || lower.contains("cookies") ->
                "TERMINAL_AUTH" to "Conteúdo privado ou exige autenticação na plataforma."
            lower.contains("drm") ->
                "TERMINAL_DRM" to "Mídia protegida por DRM não suportada."
            lower.contains("404") || lower.contains("not found") || lower.contains("unavailable") || lower.contains("removed") ->
                "TERMINAL_UNAVAILABLE" to "Mídia indisponível ou removida na origem."
            lower.contains("429") || lower.contains("rate limit") || lower.contains("too many requests") ->
                "TERMINAL_RATE_LIMIT" to "Limite de requisições atingido pela plataforma. Aguarde antes de tentar novamente."
            lower.contains("interrupted") || lower.contains("cancelled") ->
                "CANCELLED" to "Download cancelado."
            else ->
                "RECOVERABLE_ENGINE_ERROR" to (raw.take(240).ifBlank { "Erro no motor local ao baixar mídia." })
        }
    }

    private fun markCancelled(jobId: String, url: String = "", platform: String = "unknown") {
        val existing = queueStore.getJob(jobId)
        val cancelled = buildSnapshot(
            jobId = jobId,
            url = existing?.optString("url", url) ?: url,
            platform = existing?.optString("platform", platform) ?: platform,
            status = "cancelled",
            phaseLabel = "Cancelado",
            bytesDownloaded = existing?.optLong("bytesDownloaded", 0L) ?: 0L,
            totalBytes = null,
            percent = null,
            isIndeterminate = false,
            title = existing?.optString("title")?.takeIf { it.isNotBlank() },
            error = "Cancelado pelo usuário.",
            errorCode = "CANCELLED",
        )
        publishSnapshot(cancelled)
    }

    private fun publishSnapshot(snapshot: JSONObject) {
        queueStore.upsertJob(snapshot)
        emitProgress(snapshot)
    }

    private fun buildSnapshot(
        jobId: String,
        url: String,
        platform: String,
        status: String,
        phaseLabel: String,
        bytesDownloaded: Long,
        totalBytes: Long?,
        percent: Double?,
        isIndeterminate: Boolean,
        speedBytesPerSec: Long? = null,
        etaSeconds: Long? = null,
        title: String? = null,
        error: String? = null,
        errorCode: String? = null,
        files: JSONArray? = null,
    ): JSONObject {
        return JSONObject().apply {
            put("jobId", jobId)
            put("url", url)
            put("status", status)
            put("phaseLabel", phaseLabel)
            put("engine", "local-ytdlp")
            put("bytesDownloaded", bytesDownloaded)
            put("totalBytes", totalBytes ?: JSONObject.NULL)
            put("percent", percent ?: JSONObject.NULL)
            put("isIndeterminate", isIndeterminate)
            put("speedBytesPerSec", speedBytesPerSec ?: JSONObject.NULL)
            put("etaSeconds", etaSeconds ?: JSONObject.NULL)
            put("title", title ?: JSONObject.NULL)
            put("platform", platform)
            put("error", error ?: JSONObject.NULL)
            put("errorCode", errorCode ?: JSONObject.NULL)
            if (files != null) {
                put("files", files)
            }
            put("updatedAt", Instant.now().toString())
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    "Downloads em andamento",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_COMPLETED,
                    "Downloads concluídos",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    private fun promoteForeground(jobId: String, title: String, percent: Int?, indeterminate: Boolean) {
        val notif = buildProgressNotification(jobId, title, percent, indeterminate)
        val notifId = (jobId.hashCode() and 0x0FFF) + 100
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifId, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notifId, notif)
        }
    }

    private fun buildProgressNotification(jobId: String, title: String, percent: Int?, indeterminate: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getBroadcast(
            this,
            jobId.hashCode(),
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_CANCEL_JOB
                putExtra(EXTRA_JOB_ID, jobId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Devwolf Downloader")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .setProgress(100, percent ?: 0, indeterminate)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancelar", cancelIntent)
            .build()
    }

    private fun showCompletedNotification(jobId: String, title: String, files: JSONArray) {
        if (files.length() == 0) return
        val first = files.getJSONObject(0)
        val uris = ArrayList<String>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val uri = f.optString("mediaUri")
            if (uri.isNotBlank() && !f.optBoolean("isOriginal", false) || files.length() == 1) {
                uris.add(uri)
            }
        }
        if (uris.isEmpty()) uris.add(first.optString("mediaUri"))
        val mimeType = first.optString("mimeType", "video/mp4")

        val openAppIntent = PendingIntent.getActivity(
            this,
            jobId.hashCode(),
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val shareIntent = PendingIntent.getBroadcast(
            this,
            (jobId.hashCode() + 1),
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_SHARE_MEDIA
                putStringArrayListExtra(NotificationActionReceiver.EXTRA_URIS, uris)
                putExtra(NotificationActionReceiver.EXTRA_MIME_TYPE, mimeType)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openMediaIntent = PendingIntent.getBroadcast(
            this,
            (jobId.hashCode() + 2),
            Intent(this, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_OPEN_MEDIA
                putExtra(NotificationActionReceiver.EXTRA_URI, uris.first())
                putExtra(NotificationActionReceiver.EXTRA_MIME_TYPE, mimeType)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download concluído")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_share, "Compartilhar no Status / App", shareIntent)
            .addAction(android.R.drawable.ic_menu_view, "Abrir", openMediaIntent)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify((jobId.hashCode() and 0x0FFF) + 5000, notification)
    }
}
