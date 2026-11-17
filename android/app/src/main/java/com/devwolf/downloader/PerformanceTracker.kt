package com.devwolf.downloader

import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Local-only performance and diagnostics instrumentation (zero external telemetry).
 * Records per-stage timings, HTTP request counts, active strategy, and sanitized error logs.
 */
object PerformanceTracker {

    data class JobPerformanceRecord(
        val jobId: String,
        val platform: String,
        val engineUsed: String,
        val engineVersion: String?,
        val strategyUsed: String?,
        val status: String,
        val engineInitMs: Long = 0L,
        val resolutionMs: Long = 0L,
        val ttfbMs: Long = 0L,
        val transferMs: Long = 0L,
        val processingMs: Long = 0L,
        val galleryPublishMs: Long = 0L,
        val totalJobMs: Long = 0L,
        val httpRequestCount: Int = 0,
        val errorCode: String? = null,
        val technicalReason: String? = null,
        val recordedAt: String = Instant.now().toString(),
    ) {
        fun toMetricsJson(): JSONObject = JSONObject().apply {
            put("engineInitMs", engineInitMs)
            put("resolutionMs", resolutionMs)
            put("ttfbMs", ttfbMs)
            put("transferMs", transferMs)
            put("processingMs", processingMs)
            put("galleryPublishMs", galleryPublishMs)
            put("totalJobMs", totalJobMs)
            put("httpRequestCount", httpRequestCount)
            put("appOpenMs", getAppOpenMs())
            put("clipboardDetectMs", getClipboardDetectMs())
        }
    }

    private val processBootMs = AtomicLong(System.currentTimeMillis())
    private val appOpenMs = AtomicLong(0L)
    private val clipboardDetectMs = AtomicLong(0L)
    private val latestRecord = AtomicReference<JobPerformanceRecord?>(null)
    private val recentRecords = CopyOnWriteArrayList<JobPerformanceRecord>()
    private val dnrSamples = CopyOnWriteArrayList<Long>()
    private val dnrStrategies = linkedSetOf(
        "EmbedCaptionedStrategy",
        "WebPageJsonStrategy",
        "AuthorizedWebApiStrategy",
    )

    fun markAppOpenStart() {
        processBootMs.compareAndSet(0L, System.currentTimeMillis())
    }

    fun markAppOpenCompleted() {
        val elapsed = (System.currentTimeMillis() - processBootMs.get()).coerceAtLeast(1L)
        appOpenMs.compareAndSet(0L, elapsed)
    }

    fun getAppOpenMs(): Long = appOpenMs.get()

    fun recordClipboardDetect(durationMs: Long) {
        clipboardDetectMs.set(durationMs.coerceAtLeast(0L))
    }

    fun getClipboardDetectMs(): Long = clipboardDetectMs.get()

    fun recordJob(record: JobPerformanceRecord) {
        val sanitized = record.copy(
            technicalReason = sanitizeText(record.technicalReason),
        )
        latestRecord.set(sanitized)
        recentRecords.add(0, sanitized)
        while (recentRecords.size > 20) {
            recentRecords.removeAt(recentRecords.size - 1)
        }
        if (sanitized.engineUsed.equals("dnr", ignoreCase = true) && sanitized.resolutionMs > 0L) {
            dnrSamples.add(sanitized.resolutionMs)
            while (dnrSamples.size > 50) {
                dnrSamples.removeAt(0)
            }
            if (!sanitized.strategyUsed.isNullOrBlank()) {
                synchronized(dnrStrategies) {
                    dnrStrategies.add(sanitized.strategyUsed)
                }
            }
        }
    }

    fun getDnrAvgResolutionMs(): Long? {
        if (dnrSamples.isEmpty()) return null
        return dnrSamples.sum() / dnrSamples.size
    }

    fun getDnrStrategies(): List<String> = synchronized(dnrStrategies) { dnrStrategies.toList() }

    fun getLatestRecord(): JobPerformanceRecord? = latestRecord.get()

    fun buildDiagnosticsReport(
        ytdlpVersion: String?,
        lastUpdateAt: String?,
        wrapperVersion: String = LocalEngineManager.WRAPPER_VERSION,
    ): JSONObject {
        val rec = latestRecord.get()
        val sanitizedLog = buildSanitizedLog(rec, ytdlpVersion, wrapperVersion, lastUpdateAt)
        return JSONObject().apply {
            put("engineUsed", rec?.engineUsed ?: JSONObject.NULL)
            put("engineVersion", rec?.engineVersion ?: ytdlpVersion ?: JSONObject.NULL)
            put("lastUpdateAt", lastUpdateAt ?: JSONObject.NULL)
            put("engineInitMs", rec?.engineInitMs ?: 0L)
            put("resolutionMs", rec?.resolutionMs ?: 0L)
            put("ttfbMs", rec?.ttfbMs ?: 0L)
            put("transferMs", rec?.transferMs ?: 0L)
            put("processingMs", rec?.processingMs ?: 0L)
            put("galleryPublishMs", rec?.galleryPublishMs ?: 0L)
            put("totalJobMs", rec?.totalJobMs ?: 0L)
            put("httpRequestCount", rec?.httpRequestCount ?: 0)
            put("appOpenMs", getAppOpenMs())
            put("clipboardDetectMs", getClipboardDetectMs())
            put("strategyUsed", rec?.strategyUsed ?: JSONObject.NULL)
            put("errorCode", rec?.errorCode ?: JSONObject.NULL)
            put("technicalReason", rec?.technicalReason ?: JSONObject.NULL)
            put("sanitizedLog", sanitizedLog)
        }
    }

    fun buildSanitizedLog(
        rec: JobPerformanceRecord?,
        ytdlpVersion: String?,
        wrapperVersion: String,
        lastUpdateAt: String?,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== Devwolf Downloader — Diagnóstico Local ===")
        sb.appendLine("yt-dlp binário: ${ytdlpVersion ?: "não inicializado"} (wrapper $wrapperVersion)")
        sb.appendLine("Última atualização: ${lastUpdateAt ?: "nunca"}")
        sb.appendLine("Abertura app: ${getAppOpenMs()} ms | Clipboard: ${getClipboardDetectMs()} ms")
        if (rec == null) {
            sb.appendLine("Nenhum job registrado nesta sessão.")
            return sb.toString().trim()
        }
        sb.appendLine("Último job: ${rec.jobId} | plataforma=${rec.platform} | status=${rec.status}")
        sb.appendLine("Motor: ${rec.engineUsed} (${rec.engineVersion ?: "-"}) | Estratégia: ${rec.strategyUsed ?: "-"}")
        sb.appendLine(
            "Tempos (ms): init=${rec.engineInitMs}, resolução=${rec.resolutionMs}, " +
                "TTFB=${rec.ttfbMs}, transferência=${rec.transferMs}, " +
                "processamento=${rec.processingMs}, galeria=${rec.galleryPublishMs}, total=${rec.totalJobMs}",
        )
        sb.appendLine("Requisições HTTP: ${rec.httpRequestCount}")
        if (!rec.errorCode.isNullOrBlank() || !rec.technicalReason.isNullOrBlank()) {
            sb.appendLine("Erro: code=${rec.errorCode ?: "-"} | motivo=${sanitizeText(rec.technicalReason) ?: "-"}")
        }
        return sb.toString().trim()
    }

    /**
     * Strips cookies, bearer tokens, session IDs, and signed CDN query parameters
     * so diagnostic reports can be copied safely without leaking secrets.
     */
    fun sanitizeText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var cleaned = raw
            .replace(Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-+/=]+"""), "Bearer [REDACTED]")
            .replace(Regex("""(?i)(cookie|set-cookie|authorization|x-csrftoken|x-ig-[a-z0-9-]+)\s*[:=]\s*[^\r\n;|]+"""), "$1=[REDACTED]")
            .replace(Regex("""(?i)\b(sessionid|csrftoken|ds_user_id|mid|ig_did|rur|token|auth|signature|sig|oe|oh)=([^&\s;"'|]+)"""), "$1=[REDACTED]")
            .replace(Regex("""(https?://[^\s?#]+)\?[^\s"']+"""), "$1?[REDACTED_QUERY]")
        if (cleaned.length > 600) {
            cleaned = cleaned.take(600) + "…"
        }
        return cleaned.trim()
    }
}
