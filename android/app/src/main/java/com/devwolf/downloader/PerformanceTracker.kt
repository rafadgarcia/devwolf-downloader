package com.devwolf.downloader

import com.devwolf.downloader.resolvers.SafeHttpClient
import com.devwolf.downloader.resolvers.StrategyAttemptMetric
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Local-only performance and diagnostics instrumentation (zero external telemetry).
 * Records per-stage timings, HTTP request counts, active strategy, exit code,
 * identified media type, and sanitized error logs.
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
        val rawErrorSanitized: String? = null,
        val httpStatus: Int? = null,
        val exitCode: Int? = null,
        val mediaTypeIdentified: String = "unknown",
        val strategyAttempts: List<StrategyAttemptMetric> = emptyList(),
        val recordedAt: String = Instant.now().toString(),
    ) {
        fun toMetricsJson(): JSONObject = JSONObject().apply {
            put("platform", platform)
            put("engineUsed", engineUsed)
            put("engineVersion", engineVersion ?: JSONObject.NULL)
            put("strategyUsed", strategyUsed ?: JSONObject.NULL)
            put("mediaTypeIdentified", mediaTypeIdentified)
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
            if (httpStatus != null) {
                put("httpStatus", httpStatus)
            }
            if (exitCode != null) {
                put("exitCode", exitCode)
            }
            if (!rawErrorSanitized.isNullOrBlank()) {
                put("rawErrorSanitized", rawErrorSanitized)
            }
            if (!errorCode.isNullOrBlank()) {
                put("errorCode", errorCode)
            }
            if (strategyAttempts.isNotEmpty()) {
                put("strategyAttempts", strategyAttemptsToJson(strategyAttempts))
            }
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
            rawErrorSanitized = sanitizeText(record.rawErrorSanitized ?: record.technicalReason),
            strategyAttempts = record.strategyAttempts.map { attempt ->
                attempt.copy(note = sanitizeText(attempt.note))
            },
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
            put("platform", rec?.platform ?: JSONObject.NULL)
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
            put("rawErrorSanitized", rec?.rawErrorSanitized ?: JSONObject.NULL)
            put("httpStatus", rec?.httpStatus ?: JSONObject.NULL)
            put("exitCode", rec?.exitCode ?: JSONObject.NULL)
            put("mediaTypeIdentified", rec?.mediaTypeIdentified ?: "unknown")
            put("strategyAttempts", strategyAttemptsToJson(rec?.strategyAttempts.orEmpty()))
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
        sb.appendLine(
            "Último job: ${rec.jobId} | plataforma=${rec.platform} | status=${rec.status} | mídia=${rec.mediaTypeIdentified}",
        )
        sb.appendLine("Motor: ${rec.engineUsed} (${rec.engineVersion ?: "-"}) | Estratégia: ${rec.strategyUsed ?: "-"}")
        sb.appendLine(
            "Tempos (ms): init=${rec.engineInitMs}, resolução=${rec.resolutionMs}, " +
                "TTFB=${rec.ttfbMs}, transferência=${rec.transferMs}, " +
                "processamento=${rec.processingMs}, galeria=${rec.galleryPublishMs}, total=${rec.totalJobMs}",
        )
        sb.appendLine("Requisições HTTP: ${rec.httpRequestCount}")
        if (rec.strategyAttempts.isNotEmpty()) {
            sb.appendLine("Tentativas por estratégia:")
            for (attempt in rec.strategyAttempts) {
                val outcome = if (attempt.success) "OK" else "FALHA(${attempt.errorCode?.name ?: "-"})"
                val notePart = sanitizeText(attempt.note)?.let { " — $it" } ?: ""
                sb.appendLine(
                    "  - ${attempt.strategyName}: $outcome | ${attempt.durationMs} ms | ${attempt.httpRequests} req$notePart",
                )
            }
        }
        if (
            !rec.errorCode.isNullOrBlank() ||
            !rec.technicalReason.isNullOrBlank() ||
            !rec.rawErrorSanitized.isNullOrBlank() ||
            rec.httpStatus != null ||
            rec.exitCode != null
        ) {
            val httpPart = rec.httpStatus?.let { " | http=$it" } ?: ""
            val exitPart = rec.exitCode?.let { " | exitCode=$it" } ?: ""
            val rawPart = sanitizeText(rec.rawErrorSanitized)?.let { " | raw=$it" } ?: ""
            sb.appendLine(
                "Erro: code=${rec.errorCode ?: "-"}$httpPart$exitPart$rawPart | motivo=${sanitizeText(rec.technicalReason) ?: "-"}",
            )
        }
        return sb.toString().trim()
    }

    private fun strategyAttemptsToJson(attempts: List<StrategyAttemptMetric>): JSONArray {
        val arr = JSONArray()
        for (attempt in attempts) {
            arr.put(
                JSONObject().apply {
                    put("strategyName", attempt.strategyName)
                    put("durationMs", attempt.durationMs)
                    put("httpRequests", attempt.httpRequests)
                    put("success", attempt.success)
                    put("errorCode", attempt.errorCode?.name ?: JSONObject.NULL)
                    put("note", sanitizeText(attempt.note) ?: JSONObject.NULL)
                },
            )
        }
        return arr
    }

    /**
     * Strips cookies, bearer tokens, session IDs, and signed CDN query parameters
     * so diagnostic reports can be copied safely without leaking secrets.
     */
    fun sanitizeText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val preCleaned = SafeHttpClient.sanitizeForDiagnostics(raw)
        var cleaned = preCleaned
            .replace(Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-+/=]+"""), "Bearer [REDACTED]")
            .replace(Regex("""(?i)(cookie|set-cookie|authorization|x-csrftoken|x-ig-[a-z0-9-]+)\s*[:=]\s*[^\r\n;|]+"""), "$1=[REDACTED]")
            .replace(
                Regex("""(?i)\b(sessionid|csrftoken|ds_user_id|mid|ig_did|rur|shbid|shbts|reddit_session|token_v2|loid|access_token|refresh_token|client_secret|token|auth|signature|sig|oe|oh)=([^&\s;"'|]+)"""),
                "$1=[REDACTED]",
            )
            .replace(Regex("""(https?://[^\s?#]+)\?[^\s"']+"""), "$1?[REDACTED_QUERY]")
        if (cleaned.length > 600) {
            cleaned = cleaned.take(600) + "…"
        }
        return cleaned.trim().ifBlank { null }
    }
}
