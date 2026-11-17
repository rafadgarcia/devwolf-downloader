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
import com.devwolf.downloader.resolvers.DevwolfErrorCode
import com.devwolf.downloader.resolvers.EngineKind
import com.devwolf.downloader.resolvers.EngineRouter
import com.devwolf.downloader.resolvers.ErrorClassifier
import com.devwolf.downloader.resolvers.HttpDownloadResult
import com.devwolf.downloader.resolvers.HttpMediaDownloader
import com.devwolf.downloader.resolvers.MediaItemType
import com.devwolf.downloader.resolvers.ResolutionRequest
import com.devwolf.downloader.resolvers.ResolutionResult
import com.devwolf.downloader.resolvers.ResolvedMediaItem
import com.devwolf.downloader.resolvers.ResolverException
import com.devwolf.downloader.resolvers.SafeHttpClient
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/**
 * Foreground Service (dataSync) orchestrating local downloads via [EngineRouter]
 * (DNR Instagram resolver, local Pinterest/Reddit resolvers, and single-pass yt-dlp),
 * Status Mode FFmpeg processing, individual carousel MediaStore saving, and performance tracking.
 */
class DevwolfDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var queueStore: LocalQueueStore
    private lateinit var secureVault: SecureVault
    private val httpMediaDownloader = HttpMediaDownloader()

    companion object {
        const val CHANNEL_PROGRESS = "devwolf_downloads"
        const val CHANNEL_COMPLETED = "devwolf_completed"

        const val ACTION_ENQUEUE = "com.devwolf.downloader.action.ENQUEUE"
        const val ACTION_CANCEL = "com.devwolf.downloader.action.CANCEL"

        const val EXTRA_JOB_JSON = "extra_job_json"
        const val EXTRA_JOB_ID = "extra_job_id"

        private const val META_PREFIX = "DEVWOLF_META:"

        private val cancelledJobIds = ConcurrentHashMap.newKeySet<String>()
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
            val jobId = jobPayload.optString("jobId")
            if (jobId.isNotBlank()) {
                cancelledJobIds.remove(jobId)
            }
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
            if (jobId.isNotBlank()) {
                cancelledJobIds.add(jobId)
            }
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

        internal fun isJobCancelled(jobId: String): Boolean {
            return cancelledJobIds.contains(jobId) || Thread.currentThread().isInterrupted
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

        internal fun parseYtdlpMetadataLine(line: String): YtdlpMetadata? {
            val idx = line.indexOf(META_PREFIX)
            if (idx < 0) return null
            val jsonPart = line.substring(idx + META_PREFIX.length).trim()
            return try {
                val obj = JSONObject(jsonPart)
                val title = obj.optString("title").takeIf { it.isNotBlank() && it != "null" }
                val duration = if (obj.isNull("duration")) null else obj.optDouble("duration").takeIf { !it.isNaN() && it > 0 }
                val width = if (obj.isNull("width")) null else obj.optInt("width").takeIf { it > 0 }
                val height = if (obj.isNull("height")) null else obj.optInt("height").takeIf { it > 0 }
                YtdlpMetadata(title = title, durationSeconds = duration, width = width, height = height)
            } catch (_: Exception) {
                null
            }
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

    data class YtdlpMetadata(
        val title: String?,
        val durationSeconds: Double?,
        val width: Int?,
        val height: Int?,
    )

    private data class PreparedLocalFile(
        val file: File,
        val index: Int,
        val type: String,
        val mimeType: String,
        val width: Int?,
        val height: Int?,
        val durationSeconds: Double?,
        val isSegment: Boolean,
        val segmentIndex: Int?,
        val isOriginal: Boolean,
        val baseNameHint: String?,
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
                    cancelledJobIds.add(jobId)
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
        val jobStartMs = System.currentTimeMillis()
        val jobId = jobReq.getString("jobId")
        val rawUrl = jobReq.getString("url")
        val format = jobReq.optString("format", "auto")
        val quality = jobReq.optString("quality", "best")
        val statusMode = jobReq.optBoolean("statusMode", false)
        val segmentDuration = if (jobReq.has("segmentDurationSeconds") && !jobReq.isNull("segmentDurationSeconds")) {
            jobReq.optInt("segmentDurationSeconds", 0).takeIf { it > 0 }
        } else {
            null
        }
        val keepOriginal = jobReq.optBoolean("keepOriginal", false)
        val initialPlatform = UrlExtractor.detectPlatform(rawUrl)

        val workDir = File(cacheDir, "jobs/$jobId").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        LocalEngineManager.incrementActiveDownloads()

        var engineInitMs = 0L
        var resolutionMs = 0L
        var ttfbMs = 0L
        var transferMs = 0L
        var processingMs = 0L
        var galleryPublishMs = 0L
        var httpRequestCount = 0
        var activeEngineName = "dnr"
        var activeEngineVersion: String? = null
        var activeStrategy: String? = null
        var resolvedTitle: String? = null
        var currentPlatform = initialPlatform

        var currentSnapshot = buildSnapshot(
            jobId = jobId,
            url = rawUrl,
            platform = currentPlatform,
            engine = activeEngineName,
            status = "resolving",
            phaseLabel = "Link detectado",
            bytesDownloaded = 0L,
            totalBytes = null,
            percent = null,
            isIndeterminate = true,
        )
        publishSnapshot(currentSnapshot)

        try {
            val plan = EngineRouter.shared.route(rawUrl)
            currentPlatform = plan.platform
            val targetUrl = plan.normalizedUrl
            val enginesToTry = listOf(plan.primaryEngine) + plan.fallbackEngines

            currentSnapshot = buildSnapshot(
                jobId = jobId,
                url = targetUrl,
                platform = currentPlatform,
                engine = plan.primaryEngine.wireName,
                status = "resolving",
                phaseLabel = "Procurando mídia",
                bytesDownloaded = 0L,
                totalBytes = null,
                percent = null,
                isIndeterminate = true,
            )
            publishSnapshot(currentSnapshot)

            var preparedFiles: List<PreparedLocalFile>? = null
            var lastFailure: ResolverException? = null

            for ((attemptIdx, engineKind) in enginesToTry.withIndex()) {
                if (isJobCancelled(jobId)) {
                    throw ResolverException(DevwolfErrorCode.CANCELLED)
                }

                // Clear partial files between fallback attempts
                if (attemptIdx > 0) {
                    workDir.listFiles()?.forEach { it.deleteRecursively() }
                }

                activeEngineName = engineKind.wireName
                try {
                    when (engineKind) {
                        EngineKind.DNR, EngineKind.GALLERY_DL_LOCAL -> {
                            val igSession = if (currentPlatform == "instagram") secureVault.getInstagramSession() else null
                            val cookieHeader = igSession?.cookieHeader ?: secureVault.getCookieForPlatform(currentPlatform)
                            val req = ResolutionRequest(
                                url = targetUrl,
                                platform = currentPlatform,
                                format = format,
                                quality = quality,
                                cookieHeader = cookieHeader,
                                userAgent = igSession?.userAgent,
                                cancellationCheck = { isJobCancelled(jobId) },
                            )
                            val resolution: ResolutionResult = EngineRouter.shared.resolveNative(req, engineKind)
                            resolutionMs += resolution.metrics.resolutionMs
                            httpRequestCount += resolution.metrics.httpRequestCount
                            activeEngineName = resolution.engineUsed
                            activeEngineVersion = resolution.engineVersion
                            activeStrategy = resolution.strategyUsed
                            resolvedTitle = resolution.title ?: resolvedTitle

                            val itemsState = buildInitialCarouselItemsArray(resolution.items)
                            currentSnapshot = buildSnapshot(
                                jobId = jobId,
                                url = targetUrl,
                                platform = currentPlatform,
                                engine = activeEngineName,
                                engineVersion = activeEngineVersion,
                                strategyUsed = activeStrategy,
                                status = "downloading",
                                phaseLabel = "Baixando",
                                bytesDownloaded = 0L,
                                totalBytes = null,
                                percent = null,
                                isIndeterminate = true,
                                title = resolvedTitle,
                                files = itemsState,
                            )
                            publishSnapshot(currentSnapshot)
                            promoteForeground(jobId, resolvedTitle ?: "Baixando mídia…", null, true)

                            var lastNotifMs = 0L
                            val completedItemBytes = LongArray(resolution.items.size)
                            val itemTotals = LongArray(resolution.items.size) { -1L }

                            val dlResults: List<HttpDownloadResult> = httpMediaDownloader.downloadAll(
                                items = resolution.items,
                                workDir = workDir,
                                cancellationCheck = { isJobCancelled(jobId) },
                            ) { prog ->
                                val idx = (prog.itemIndex - 1).coerceIn(0, resolution.items.size - 1)
                                completedItemBytes[idx] = prog.bytesDownloaded
                                if (prog.totalBytes != null && prog.totalBytes > 0L) {
                                    itemTotals[idx] = prog.totalBytes
                                }
                                if (ttfbMs == 0L && prog.ttfbMs != null && prog.ttfbMs > 0L) {
                                    ttfbMs = prog.ttfbMs
                                }

                                updateCarouselItemProgress(
                                    itemsState = itemsState,
                                    itemIdx = idx,
                                    bytesDownloaded = prog.bytesDownloaded,
                                    totalBytes = prog.totalBytes,
                                    percent = prog.percent,
                                    status = if (prog.percent != null && prog.percent >= 100.0) "ready" else "downloading",
                                )

                                val aggBytes = completedItemBytes.sum()
                                val allTotalsKnown = itemTotals.all { it > 0L }
                                val aggTotal = if (allTotalsKnown) itemTotals.sum() else null
                                val aggPct = if (aggTotal != null && aggTotal > 0L) {
                                    ((aggBytes.toDouble() / aggTotal.toDouble()) * 100.0).coerceIn(0.0, 100.0)
                                } else if (resolution.items.size == 1) {
                                    prog.percent
                                } else {
                                    val doneCount = (0 until itemsState.length()).count {
                                        itemsState.optJSONObject(it)?.optString("status") == "ready"
                                    }
                                    val currentFrac = (prog.percent ?: 0.0) / 100.0
                                    (((doneCount + currentFrac) / resolution.items.size.toDouble()) * 100.0).coerceIn(0.0, 99.9)
                                }

                                currentSnapshot = buildSnapshot(
                                    jobId = jobId,
                                    url = targetUrl,
                                    platform = currentPlatform,
                                    engine = activeEngineName,
                                    engineVersion = activeEngineVersion,
                                    strategyUsed = activeStrategy,
                                    status = "downloading",
                                    phaseLabel = if (resolution.items.size > 1) {
                                        "Baixando (${prog.itemIndex}/${prog.totalItems})"
                                    } else {
                                        "Baixando"
                                    },
                                    bytesDownloaded = aggBytes,
                                    totalBytes = aggTotal,
                                    percent = aggPct,
                                    isIndeterminate = aggTotal == null && prog.isIndeterminate,
                                    speedBytesPerSec = prog.speedBytesPerSec,
                                    etaSeconds = prog.etaSeconds,
                                    title = resolvedTitle,
                                    files = itemsState,
                                )
                                publishSnapshot(currentSnapshot)

                                val now = System.currentTimeMillis()
                                if (now - lastNotifMs > 600) {
                                    lastNotifMs = now
                                    promoteForeground(
                                        jobId,
                                        resolvedTitle ?: "Baixando (${prog.itemIndex}/${prog.totalItems})…",
                                        aggPct?.roundToInt(),
                                        aggPct == null,
                                    )
                                }
                            }

                            if (ttfbMs == 0L) {
                                ttfbMs = dlResults.firstOrNull()?.ttfbMs ?: 0L
                            }
                            transferMs += dlResults.sumOf { it.transferMs }
                            httpRequestCount += dlResults.sumOf { it.httpRequests }

                            val procStart = System.currentTimeMillis()
                            preparedFiles = prepareDownloadedNativeItems(
                                dlResults = dlResults,
                                resolution = resolution,
                                statusMode = statusMode,
                                segmentDuration = segmentDuration,
                                keepOriginal = keepOriginal,
                                workDir = workDir,
                            )
                            processingMs += (System.currentTimeMillis() - procStart)
                            break
                        }

                        EngineKind.YT_DLP -> {
                            val initElapsed = LocalEngineManager.ensureYtdlpInitialized(applicationContext)
                            engineInitMs += initElapsed
                            activeEngineName = "yt-dlp"
                            activeEngineVersion = LocalEngineManager.getInstalledYtdlpVersion(applicationContext)
                                ?: LocalEngineManager.getCachedYtdlpVersion(applicationContext)
                            activeStrategy = "yt-dlp-single-pass"

                            val dlStart = System.currentTimeMillis()
                            val ytdlpMeta = executeSinglePassYtdlp(
                                jobId = jobId,
                                url = targetUrl,
                                platform = currentPlatform,
                                format = format,
                                quality = quality,
                                workDir = workDir,
                                engineVersion = activeEngineVersion,
                                onTitleResolved = { meta ->
                                    if (!meta.title.isNullOrBlank()) {
                                        resolvedTitle = meta.title
                                    }
                                },
                                onProgressSnapshot = { snap ->
                                    currentSnapshot = snap
                                    publishSnapshot(snap)
                                },
                            )
                            val ytdlpElapsed = (System.currentTimeMillis() - dlStart).coerceAtLeast(1L)
                            transferMs += ytdlpElapsed

                            if (ytdlpMeta?.title != null && resolvedTitle.isNullOrBlank()) {
                                resolvedTitle = ytdlpMeta.title
                            }

                            val rawFiles = workDir.listFiles { f ->
                                f.isFile &&
                                    !f.name.endsWith(".part") &&
                                    !f.name.endsWith(".ytdl") &&
                                    !f.name.endsWith(".cookies.txt") &&
                                    f.length() > 0L
                            }?.sortedBy { it.name }.orEmpty()

                            if (rawFiles.isEmpty()) {
                                throw ResolverException(
                                    errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                                    technicalReason = "Nenhum arquivo de mídia gerado pelo yt-dlp",
                                    strategyUsed = activeStrategy,
                                )
                            }

                            val procStart = System.currentTimeMillis()
                            preparedFiles = prepareYtdlpFiles(
                                rawFiles = rawFiles,
                                ytdlpMeta = ytdlpMeta,
                                statusMode = statusMode,
                                segmentDuration = segmentDuration,
                                keepOriginal = keepOriginal,
                                workDir = workDir,
                            )
                            processingMs += (System.currentTimeMillis() - procStart)
                            break
                        }
                    }
                } catch (e: Throwable) {
                    val mapped = mapThrowableToResolverException(e, activeStrategy)
                    lastFailure = mapped
                    val nextEngine = enginesToTry.getOrNull(attemptIdx + 1)
                    if (nextEngine != null && EngineRouter.shared.shouldFallback(engineKind, nextEngine, mapped.errorCode)) {
                        continue
                    }
                    throw mapped
                }
            }

            val finalPrepared = preparedFiles ?: throw (lastFailure ?: ResolverException(DevwolfErrorCode.EXTRACTION_FAILED))

            val totalPrepBytes = finalPrepared.sumOf { it.file.length() }
            currentSnapshot = buildSnapshot(
                jobId = jobId,
                url = targetUrl,
                platform = currentPlatform,
                engine = activeEngineName,
                engineVersion = activeEngineVersion,
                strategyUsed = activeStrategy,
                status = "processing",
                phaseLabel = "Preparando arquivo",
                bytesDownloaded = totalPrepBytes,
                totalBytes = totalPrepBytes,
                percent = 95.0,
                isIndeterminate = false,
                title = resolvedTitle,
            )
            publishSnapshot(currentSnapshot)
            promoteForeground(jobId, "Salvando na galeria…", 95, false)

            val publishStart = System.currentTimeMillis()
            val outputFilesJson = JSONArray()
            var totalOutBytes = 0L
            finalPrepared.forEachIndexed { idx, item ->
                val itemNum = (idx + 1).toString().padStart(2, '0')
                val baseTitle = resolvedTitle ?: item.baseNameHint ?: item.file.nameWithoutExtension
                val suffix = when {
                    finalPrepared.size > 1 && !item.isSegment -> "_$itemNum"
                    item.isSegment && item.segmentIndex != null -> "_parte${item.segmentIndex.toString().padStart(2, '0')}"
                    statusMode && !item.isOriginal -> "_status"
                    else -> ""
                }
                val ext = item.file.extension.ifBlank {
                    when (item.type) {
                        "image" -> "jpg"
                        "audio" -> "mp3"
                        else -> "mp4"
                    }
                }
                val preferredName = "${baseTitle}$suffix.$ext"
                val saved = try {
                    MediaStoreHelper.saveFileToGallery(
                        context = applicationContext,
                        sourceFile = item.file,
                        mimeType = item.mimeType,
                        preferredName = preferredName,
                        widthHint = item.width,
                        heightHint = item.height,
                    )
                } catch (storageEx: Throwable) {
                    throw ResolverException(
                        errorCode = DevwolfErrorCode.STORAGE_ERROR,
                        technicalReason = storageEx.message ?: "Falha ao salvar no MediaStore",
                        strategyUsed = activeStrategy,
                        cause = storageEx,
                    )
                }
                totalOutBytes += saved.sizeBytes
                outputFilesJson.put(
                    JSONObject().apply {
                        put("id", "${jobId}_$idx")
                        put("index", idx + 1)
                        put("type", saved.mediaType)
                        put("fileName", saved.fileName)
                        put("filePath", "")
                        put("mediaUri", saved.mediaUri)
                        put("displayPath", saved.displayPath)
                        put("mimeType", saved.mimeType)
                        put("width", saved.width ?: JSONObject.NULL)
                        put("height", saved.height ?: JSONObject.NULL)
                        put("sizeBytes", saved.sizeBytes)
                        put("bytesDownloaded", saved.sizeBytes)
                        put("percent", 100.0)
                        put("status", "ready")
                        put("durationSeconds", item.durationSeconds ?: JSONObject.NULL)
                        put("isSegment", item.isSegment)
                        put("segmentIndex", item.segmentIndex ?: JSONObject.NULL)
                        put("isOriginal", item.isOriginal)
                    },
                )
            }
            galleryPublishMs = (System.currentTimeMillis() - publishStart).coerceAtLeast(1L)

            val totalJobMs = (System.currentTimeMillis() - jobStartMs).coerceAtLeast(1L)
            val perfRecord = PerformanceTracker.JobPerformanceRecord(
                jobId = jobId,
                platform = currentPlatform,
                engineUsed = activeEngineName,
                engineVersion = activeEngineVersion,
                strategyUsed = activeStrategy,
                status = "ready",
                engineInitMs = engineInitMs,
                resolutionMs = resolutionMs,
                ttfbMs = ttfbMs,
                transferMs = transferMs,
                processingMs = processingMs,
                galleryPublishMs = galleryPublishMs,
                totalJobMs = totalJobMs,
                httpRequestCount = httpRequestCount,
            )
            PerformanceTracker.recordJob(perfRecord)

            val readySnapshot = buildSnapshot(
                jobId = jobId,
                url = targetUrl,
                platform = currentPlatform,
                engine = activeEngineName,
                engineVersion = activeEngineVersion,
                strategyUsed = activeStrategy,
                status = "ready",
                phaseLabel = "Pronto para baixar",
                bytesDownloaded = totalOutBytes,
                totalBytes = totalOutBytes,
                percent = 100.0,
                isIndeterminate = false,
                title = resolvedTitle,
                files = outputFilesJson,
                metrics = perfRecord.toMetricsJson(),
            )
            publishSnapshot(readySnapshot)
            showCompletedNotification(jobId, resolvedTitle ?: "Mídia pronta", outputFilesJson)
        } catch (e: InterruptedException) {
            markCancelled(jobId, rawUrl, currentPlatform)
        } catch (e: Throwable) {
            val current = queueStore.getJob(jobId)
            if (current?.optString("status") == "cancelled" || isJobCancelled(jobId)) {
                markCancelled(jobId, rawUrl, currentPlatform)
            } else {
                val resolvedErr = mapThrowableToResolverException(e, activeStrategy)
                val totalJobMs = (System.currentTimeMillis() - jobStartMs).coerceAtLeast(1L)
                val perfRecord = PerformanceTracker.JobPerformanceRecord(
                    jobId = jobId,
                    platform = currentPlatform,
                    engineUsed = activeEngineName,
                    engineVersion = activeEngineVersion,
                    strategyUsed = resolvedErr.strategyUsed ?: activeStrategy,
                    status = "failed",
                    engineInitMs = engineInitMs,
                    resolutionMs = resolutionMs + resolvedErr.metrics.resolutionMs,
                    ttfbMs = ttfbMs,
                    transferMs = transferMs,
                    processingMs = processingMs,
                    galleryPublishMs = galleryPublishMs,
                    totalJobMs = totalJobMs,
                    httpRequestCount = httpRequestCount + resolvedErr.metrics.httpRequestCount,
                    errorCode = resolvedErr.errorCode.name,
                    technicalReason = resolvedErr.technicalReason ?: e.message,
                )
                PerformanceTracker.recordJob(perfRecord)

                val failed = buildSnapshot(
                    jobId = jobId,
                    url = rawUrl,
                    platform = currentPlatform,
                    engine = activeEngineName,
                    engineVersion = activeEngineVersion,
                    strategyUsed = perfRecord.strategyUsed,
                    status = "failed",
                    phaseLabel = "Falhou",
                    bytesDownloaded = currentSnapshot.optLong("bytesDownloaded", 0L),
                    totalBytes = if (currentSnapshot.isNull("totalBytes")) null else currentSnapshot.optLong("totalBytes"),
                    percent = null,
                    isIndeterminate = false,
                    title = resolvedTitle ?: currentSnapshot.optString("title").takeIf { it.isNotBlank() },
                    error = resolvedErr.userMessage,
                    errorCode = resolvedErr.errorCode.name,
                    technicalReason = PerformanceTracker.sanitizeText(resolvedErr.technicalReason ?: e.message),
                    metrics = perfRecord.toMetricsJson(),
                )
                publishSnapshot(failed)
            }
        } finally {
            cancelledJobIds.remove(jobId)
            workDir.deleteRecursively()
            val remaining = LocalEngineManager.decrementActiveDownloads()
            if (remaining == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun executeSinglePassYtdlp(
        jobId: String,
        url: String,
        platform: String,
        format: String,
        quality: String,
        workDir: File,
        engineVersion: String?,
        onTitleResolved: (YtdlpMetadata) -> Unit,
        onProgressSnapshot: (JSONObject) -> Unit,
    ): YtdlpMetadata? {
        val request = buildYoutubeDlRequest(url, platform, format, quality, workDir)
        var capturedMeta: YtdlpMetadata? = null
        var lastNotifMs = 0L
        var lastBytes = 0L
        var lastTotal: Long? = null

        YoutubeDL.getInstance().execute(request, jobId) { progress, etaInSeconds, line ->
            val meta = parseYtdlpMetadataLine(line)
            if (meta != null) {
                capturedMeta = meta
                onTitleResolved(meta)
            }

            val parsed = parseYtdlpProgressLine(line) ?: return@execute
            val pct = parsed.percent ?: if (progress >= 0f && parsed.totalBytes != null) progress.toDouble() else null
            lastBytes = parsed.bytesDownloaded.coerceAtLeast(lastBytes)
            lastTotal = parsed.totalBytes ?: lastTotal
            val isIndet = lastTotal == null

            val snap = buildSnapshot(
                jobId = jobId,
                url = url,
                platform = platform,
                engine = "yt-dlp",
                engineVersion = engineVersion,
                strategyUsed = "yt-dlp-single-pass",
                status = "downloading",
                phaseLabel = "Baixando",
                bytesDownloaded = lastBytes,
                totalBytes = lastTotal,
                percent = pct,
                isIndeterminate = isIndet,
                speedBytesPerSec = parsed.speedBytesPerSec,
                etaSeconds = parsed.etaSeconds ?: etaInSeconds.takeIf { it > 0 },
                title = capturedMeta?.title,
            )
            onProgressSnapshot(snap)

            val now = System.currentTimeMillis()
            if (now - lastNotifMs > 600) {
                lastNotifMs = now
                promoteForeground(
                    jobId,
                    capturedMeta?.title ?: "Baixando via yt-dlp…",
                    pct?.roundToInt(),
                    isIndet,
                )
            }
        }
        return capturedMeta
    }

    private fun prepareDownloadedNativeItems(
        dlResults: List<HttpDownloadResult>,
        resolution: ResolutionResult,
        statusMode: Boolean,
        segmentDuration: Int?,
        keepOriginal: Boolean,
        workDir: File,
    ): List<PreparedLocalFile> {
        val out = mutableListOf<PreparedLocalFile>()
        dlResults.forEachIndexed { idx, dl ->
            val item: ResolvedMediaItem = dl.item
            if ((statusMode || segmentDuration != null) && item.type == MediaItemType.VIDEO) {
                val processed = StatusModeProcessor.processForStatusMode(
                    context = applicationContext,
                    sourceFile = dl.file,
                    workDir = workDir,
                    segmentDurationSeconds = segmentDuration,
                    keepOriginal = keepOriginal,
                )
                processed.forEach { proc ->
                    out.add(
                        PreparedLocalFile(
                            file = proc.file,
                            index = idx + 1,
                            type = MediaStoreHelper.resolveMediaType(proc.mimeType),
                            mimeType = proc.mimeType,
                            width = item.width,
                            height = item.height,
                            durationSeconds = proc.durationSeconds ?: item.durationSeconds,
                            isSegment = proc.isSegment,
                            segmentIndex = proc.segmentIndex,
                            isOriginal = proc.isOriginal,
                            baseNameHint = resolution.shortcode ?: resolution.title,
                        ),
                    )
                }
            } else {
                val dur = if (item.type == MediaItemType.VIDEO || item.type == MediaItemType.AUDIO) {
                    item.durationSeconds ?: StatusModeProcessor.probeDurationLightweight(dl.file)
                } else {
                    null
                }
                out.add(
                    PreparedLocalFile(
                        file = dl.file,
                        index = idx + 1,
                        type = item.type.wireName,
                        mimeType = item.mimeType,
                        width = item.width,
                        height = item.height,
                        durationSeconds = dur,
                        isSegment = false,
                        segmentIndex = null,
                        isOriginal = true,
                        baseNameHint = resolution.shortcode ?: resolution.title,
                    ),
                )
            }
        }
        return out
    }

    private fun prepareYtdlpFiles(
        rawFiles: List<File>,
        ytdlpMeta: YtdlpMetadata?,
        statusMode: Boolean,
        segmentDuration: Int?,
        keepOriginal: Boolean,
        workDir: File,
    ): List<PreparedLocalFile> {
        val out = mutableListOf<PreparedLocalFile>()
        rawFiles.forEachIndexed { idx, rawFile ->
            val guessedMime = StatusModeProcessor.guessMimeType(rawFile.name)
            val isVideo = guessedMime.startsWith("video/")
            if ((statusMode || segmentDuration != null) && isVideo) {
                val processed = StatusModeProcessor.processForStatusMode(
                    context = applicationContext,
                    sourceFile = rawFile,
                    workDir = workDir,
                    segmentDurationSeconds = segmentDuration,
                    keepOriginal = keepOriginal,
                )
                processed.forEach { proc ->
                    out.add(
                        PreparedLocalFile(
                            file = proc.file,
                            index = idx + 1,
                            type = MediaStoreHelper.resolveMediaType(proc.mimeType),
                            mimeType = proc.mimeType,
                            width = ytdlpMeta?.width,
                            height = ytdlpMeta?.height,
                            durationSeconds = proc.durationSeconds ?: ytdlpMeta?.durationSeconds,
                            isSegment = proc.isSegment,
                            segmentIndex = proc.segmentIndex,
                            isOriginal = proc.isOriginal,
                            baseNameHint = ytdlpMeta?.title,
                        ),
                    )
                }
            } else {
                val type = when {
                    guessedMime.startsWith("image/") -> "image"
                    guessedMime.startsWith("audio/") -> "audio"
                    else -> "video"
                }
                val dur = if (type != "image") {
                    ytdlpMeta?.durationSeconds ?: StatusModeProcessor.probeDurationLightweight(rawFile)
                } else {
                    null
                }
                out.add(
                    PreparedLocalFile(
                        file = rawFile,
                        index = idx + 1,
                        type = type,
                        mimeType = guessedMime,
                        width = ytdlpMeta?.width,
                        height = ytdlpMeta?.height,
                        durationSeconds = dur,
                        isSegment = false,
                        segmentIndex = null,
                        isOriginal = true,
                        baseNameHint = ytdlpMeta?.title,
                    ),
                )
            }
        }
        return out
    }

    private fun buildInitialCarouselItemsArray(items: List<ResolvedMediaItem>): JSONArray {
        val arr = JSONArray()
        items.forEachIndexed { idx, item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("index", idx + 1)
                    put("type", item.type.wireName)
                    put("fileName", item.fileName)
                    put("mimeType", item.mimeType)
                    put("width", item.width ?: JSONObject.NULL)
                    put("height", item.height ?: JSONObject.NULL)
                    put("sizeBytes", JSONObject.NULL)
                    put("bytesDownloaded", 0L)
                    put("percent", JSONObject.NULL)
                    put("status", "queued")
                    put("mediaUri", JSONObject.NULL)
                    put("displayPath", JSONObject.NULL)
                },
            )
        }
        return arr
    }

    private fun updateCarouselItemProgress(
        itemsState: JSONArray,
        itemIdx: Int,
        bytesDownloaded: Long,
        totalBytes: Long?,
        percent: Double?,
        status: String,
    ) {
        val obj = itemsState.optJSONObject(itemIdx) ?: return
        obj.put("bytesDownloaded", bytesDownloaded)
        if (totalBytes != null) obj.put("sizeBytes", totalBytes)
        obj.put("percent", percent ?: JSONObject.NULL)
        obj.put("status", status)
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
            addOption("--no-warnings")
            addOption("--no-simulate")
            addOption("--print", """before_dl:${META_PREFIX}{"title":%(title)j,"duration":%(duration)j,"width":%(width)j,"height":%(height)j}""")
            addOption("--max-filesize", "500M")
            addOption("--newline")
            addOption("--progress")
            addOption("--restrict-filenames")
            addOption("--user-agent", SafeHttpClient.DEFAULT_MOBILE_CHROME_UA)

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

            // Host-scoped Netscape cookie file so cookies are never leaked on CLI args or third-party redirects
            val cookie = secureVault.getCookieForPlatform(platform)
            if (!cookie.isNullOrBlank()) {
                val cookieFile = writeScopedCookiesFile(workDir, platform, cookie)
                if (cookieFile != null) {
                    addOption("--cookies", cookieFile.absolutePath)
                }
            }
        }
    }

    private fun writeScopedCookiesFile(workDir: File, platform: String, cookieHeader: String): File? {
        val domain = when (platform.lowercase()) {
            "instagram" -> ".instagram.com"
            "facebook" -> ".facebook.com"
            "tiktok" -> ".tiktok.com"
            "youtube" -> ".youtube.com"
            "twitter" -> ".x.com"
            "reddit" -> ".reddit.com"
            "pinterest" -> ".pinterest.com"
            else -> return null
        }
        return try {
            val file = File(workDir, "scoped.cookies.txt")
            val sb = StringBuilder("# Netscape HTTP Cookie File\n")
            for (part in cookieHeader.split(';')) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    sb.append(domain)
                        .append("\tTRUE\t/\tTRUE\t2147483647\t")
                        .append(key)
                        .append('\t')
                        .append(value)
                        .append('\n')
                }
            }
            file.writeText(sb.toString(), Charsets.UTF_8)
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun mapThrowableToResolverException(error: Throwable, strategyUsed: String?): ResolverException {
        if (error is ResolverException) return error
        val (code, message) = ErrorClassifier.fromThrowable(error)
        return ResolverException(
            errorCode = code,
            userMessage = message,
            technicalReason = ErrorClassifier.extractFatalErrorLines(error.message.orEmpty()).ifBlank { error.javaClass.simpleName },
            strategyUsed = strategyUsed,
            cause = error,
        )
    }

    private fun markCancelled(jobId: String, url: String = "", platform: String = "unknown") {
        val existing = queueStore.getJob(jobId)
        val cancelled = buildSnapshot(
            jobId = jobId,
            url = existing?.optString("url", url) ?: url,
            platform = existing?.optString("platform", platform) ?: platform,
            engine = existing?.optString("engine", "dnr") ?: "dnr",
            engineVersion = existing?.optString("engineVersion")?.takeIf { it.isNotBlank() },
            strategyUsed = existing?.optString("strategyUsed")?.takeIf { it.isNotBlank() },
            status = "cancelled",
            phaseLabel = "Cancelado",
            bytesDownloaded = existing?.optLong("bytesDownloaded", 0L) ?: 0L,
            totalBytes = null,
            percent = null,
            isIndeterminate = false,
            title = existing?.optString("title")?.takeIf { it.isNotBlank() },
            error = DevwolfErrorCode.CANCELLED.defaultMessagePtBr,
            errorCode = DevwolfErrorCode.CANCELLED.name,
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
        engine: String = "dnr",
        engineVersion: String? = null,
        strategyUsed: String? = null,
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
        technicalReason: String? = null,
        files: JSONArray? = null,
        metrics: JSONObject? = null,
    ): JSONObject {
        return JSONObject().apply {
            put("jobId", jobId)
            put("url", url)
            put("status", status)
            put("phaseLabel", phaseLabel)
            put("engine", engine)
            put("engineVersion", engineVersion ?: JSONObject.NULL)
            put("strategyUsed", strategyUsed ?: JSONObject.NULL)
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
            put("technicalReason", technicalReason ?: JSONObject.NULL)
            if (files != null) {
                put("files", files)
                put("items", files)
            }
            if (metrics != null) {
                put("metrics", metrics)
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
        val types = HashSet<String>()
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val uri = f.optString("mediaUri")
            val isSegment = f.optBoolean("isSegment", false)
            val isOriginal = f.optBoolean("isOriginal", false)
            if (uri.isNotBlank() && (!isOriginal || isSegment || files.length() == 1 || !hasAnyNonOriginal(files))) {
                uris.add(uri)
                types.add(f.optString("type", "video"))
            }
        }
        if (uris.isEmpty()) {
            val fallbackUri = first.optString("mediaUri")
            if (fallbackUri.isNotBlank()) uris.add(fallbackUri)
        }
        if (uris.isEmpty()) return

        val mimeType = when {
            types.size > 1 -> "*/*"
            types.firstOrNull() == "image" -> "image/*"
            types.firstOrNull() == "audio" -> "audio/*"
            else -> first.optString("mimeType", "video/mp4")
        }

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
                putExtra(NotificationActionReceiver.EXTRA_MIME_TYPE, first.optString("mimeType", mimeType))
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val summary = if (uris.size > 1) "$title (${uris.size} itens)" else title
        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETED)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download concluído")
            .setContentText(summary)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_share, "Compartilhar no Status / App", shareIntent)
            .addAction(android.R.drawable.ic_menu_view, "Abrir", openMediaIntent)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify((jobId.hashCode() and 0x0FFF) + 5000, notification)
    }

    private fun hasAnyNonOriginal(files: JSONArray): Boolean {
        for (i in 0 until files.length()) {
            if (!files.optJSONObject(i)?.optBoolean("isOriginal", true)!!) return true
        }
        return false
    }
}
