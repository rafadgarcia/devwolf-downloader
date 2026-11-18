package com.devwolf.downloader

import com.devwolf.downloader.resolvers.DevwolfErrorCode
import com.devwolf.downloader.resolvers.ErrorClassifier
import com.devwolf.downloader.resolvers.MediaItemType
import com.devwolf.downloader.resolvers.ResolvedMediaItem
import com.devwolf.downloader.resolvers.StrategyAttemptMetric
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PerformanceAndErrorContractTest {

    @Test
    fun ignoresYtdlp90DayWarningAndDoesNotMisclassifyAsPrivateMedia() {
        val stderr = """
            WARNING: Your yt-dlp version (2025.03.31) is older than 90 days!
            WARNING: [TikTok] Please pass --cookies if needed.
            ERROR: [TikTok] Unable to extract video data; please report this issue on https://yt-dlp.org
        """.trimIndent()
        val (code, message) = ErrorClassifier.fromYtdlpOutput(stderr)
        assertEquals(DevwolfErrorCode.EXTRACTOR_OUTDATED, code)
        assertFalse(code == DevwolfErrorCode.PRIVATE_MEDIA)
        assertFalse(code == DevwolfErrorCode.AUTH_REQUIRED)
        assertTrue(message.contains("desatualizado", ignoreCase = true))
    }

    @Test
    fun extractsFatalLinesOnlyWhenWarningsPrecededError() {
        val onlyWarning = ErrorClassifier.extractFatalErrorLines(
            "WARNING: Your yt-dlp version is more than 90 days old\nERROR: [Instagram] HTTP Error 429: Too Many Requests",
        )
        assertEquals("ERROR: [Instagram] HTTP Error 429: Too Many Requests", onlyWarning)
        val (code, _) = ErrorClassifier.fromYtdlpOutput(onlyWarning)
        assertEquals(DevwolfErrorCode.RATE_LIMITED, code)
        assertTrue(code.isTerminal)
    }

    @Test
    fun classifiesAllElevenStructuredErrorCodesAccurately() {
        assertEquals(DevwolfErrorCode.AUTH_REQUIRED, ErrorClassifier.fromHttpStatus(401))
        assertEquals(DevwolfErrorCode.MEDIA_UNAVAILABLE, ErrorClassifier.fromHttpStatus(404))
        assertEquals(DevwolfErrorCode.RATE_LIMITED, ErrorClassifier.fromHttpStatus(429))
        assertEquals(DevwolfErrorCode.NETWORK_ERROR, ErrorClassifier.fromHttpStatus(503))
        assertEquals(DevwolfErrorCode.CAPTCHA_REQUIRED, ErrorClassifier.fromHttpStatus(403, "checkpoint captcha required"))
        assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, ErrorClassifier.fromHttpStatus(403, "cloudflare html changed"))

        assertEquals(
            DevwolfErrorCode.PRIVATE_MEDIA,
            ErrorClassifier.fromYtdlpOutput("ERROR: This account is private").first,
        )
        assertEquals(
            DevwolfErrorCode.CANCELLED,
            ErrorClassifier.fromThrowable(InterruptedException("cancelled")).first,
        )
        assertEquals(
            DevwolfErrorCode.STORAGE_ERROR,
            ErrorClassifier.fromThrowable(IllegalStateException("MediaStore write failed: ENOSPC")).first,
        )
        assertEquals(
            DevwolfErrorCode.UNSUPPORTED_MEDIA,
            ErrorClassifier.fromYtdlpOutput("ERROR: Unsupported URL").first,
        )
    }

    @Test
    fun rejectsZipAndUnknownFilesFromGalleryMediaStore() {
        var zipRejected = false
        try {
            MediaStoreHelper.resolveEffectiveMimeType(File("carousel.zip"), "application/zip")
        } catch (_: IllegalArgumentException) {
            zipRejected = true
        }
        assertTrue("ZIP files must never be registered in MediaStore.Video", zipRejected)

        var unknownRejected = false
        try {
            MediaStoreHelper.resolveEffectiveMimeType(File("payload.bin"), "application/octet-stream")
        } catch (_: IllegalArgumentException) {
            unknownRejected = true
        }
        assertTrue(unknownRejected)

        assertEquals("image/jpeg", MediaStoreHelper.resolveEffectiveMimeType(File("photo_01.jpg"), null))
        assertEquals("video/mp4", MediaStoreHelper.resolveEffectiveMimeType(File("reel_01.mp4"), "application/octet-stream"))
        assertEquals("audio/mpeg", MediaStoreHelper.resolveEffectiveMimeType(File("audio.mp3"), ""))
    }

    @Test
    fun sanitizesCookiesTokensAndSignedUrlsFromDiagnosticsLog() {
        val raw = "Cookie: sessionid=secret123; csrftoken=abc999 | Authorization: Bearer mySecretJwtToken | https://cdn.example.com/v/video.mp4?oh=deadbeef&oe=123456"
        val sanitized = PerformanceTracker.sanitizeText(raw)
        assertNotNull(sanitized)
        assertFalse(sanitized!!.contains("secret123"))
        assertFalse(sanitized.contains("abc999"))
        assertFalse(sanitized.contains("mySecretJwtToken"))
        assertFalse(sanitized.contains("deadbeef"))
        assertTrue(sanitized.contains("[REDACTED]"))
        assertTrue(sanitized.contains("[REDACTED_QUERY]"))
        assertNull(PerformanceTracker.sanitizeText("   "))
    }

    @Test
    fun comparesDateBasedYtdlpVersionsCorrectly() {
        assertTrue(LocalEngineManager.compareVersions("2025.03.31", "2026.11.14") < 0)
        assertTrue(LocalEngineManager.compareVersions("2026.11.14", "2026.11.14") == 0)
        assertTrue(LocalEngineManager.compareVersions("2026.11.15", "2026.11.14") > 0)
        assertTrue(LocalEngineManager.compareVersions("v2026.01.05", "2026.1.5") == 0)
    }

    @Test
    fun recordsPerformanceMetricsAndBuildsSanitizedDiagnosticsReport() {
        PerformanceTracker.recordClipboardDetect(14L)
        PerformanceTracker.recordJob(
            PerformanceTracker.JobPerformanceRecord(
                jobId = "job-test-1",
                platform = "instagram",
                engineUsed = "dnr",
                engineVersion = "3.0.0",
                strategyUsed = "EmbedCaptionedStrategy",
                status = "ready",
                engineInitMs = 0L,
                resolutionMs = 185L,
                ttfbMs = 92L,
                transferMs = 410L,
                processingMs = 0L,
                galleryPublishMs = 35L,
                totalJobMs = 722L,
                httpRequestCount = 2,
            ),
        )
        val report = PerformanceTracker.buildDiagnosticsReport("2026.11.14", "2026-11-17T20:00:00Z")
        assertEquals("dnr", report.getString("engineUsed"))
        assertEquals("EmbedCaptionedStrategy", report.getString("strategyUsed"))
        assertEquals(185L, report.getLong("resolutionMs"))
        assertEquals(92L, report.getLong("ttfbMs"))
        assertEquals(2, report.getInt("httpRequestCount"))
        assertEquals(14L, report.getLong("clipboardDetectMs"))
        assertTrue(report.getString("sanitizedLog").contains("EmbedCaptionedStrategy"))
    }

    @Test
    fun parsesSinglePassYtdlpMetadataLineWithoutGetInfoCall() {
        val line = """DEVWOLF_META:{"title":"Reel de teste #1","duration":27.4,"width":1080,"height":1920}"""
        val meta = DevwolfDownloadService.parseYtdlpMetadataLine(line)
        assertNotNull(meta)
        assertEquals("Reel de teste #1", meta!!.title)
        assertEquals(27.4, meta.durationSeconds!!, 0.01)
        assertEquals(1080, meta.width)
        assertEquals(1920, meta.height)

        assertNull(DevwolfDownloadService.parseYtdlpMetadataLine("[download] 10% of 5.0MiB"))
    }

    @Test
    fun alignsYtdlpUserAgentWithStoredSessionAndAvoidsOverridingTikTokUa() {
        val webViewUa = "Mozilla/5.0 (Linux; Android 15; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36"
        assertEquals(
            webViewUa,
            DevwolfDownloadService.resolveYtdlpUserAgent("instagram", webViewUa),
        )
        assertEquals(
            webViewUa,
            DevwolfDownloadService.resolveYtdlpUserAgent("threads", webViewUa),
        )
        assertNull(DevwolfDownloadService.resolveYtdlpUserAgent("instagram", "   "))
        assertNull(DevwolfDownloadService.resolveYtdlpUserAgent("instagram", "BadUA\r\nX-Injected: 1"))
        // TikTok, YouTube, Reddit must use yt-dlp's own tuned User-Agent unless explicitly required
        assertNull(DevwolfDownloadService.resolveYtdlpUserAgent("tiktok", webViewUa))
        assertNull(DevwolfDownloadService.resolveYtdlpUserAgent("youtube", webViewUa))
        assertNull(DevwolfDownloadService.resolveYtdlpUserAgent("reddit", webViewUa))
    }

    @Test
    fun readsPersistedYtdlpVersionDirectlyFromZipappBinary() {
        val tempFile = File.createTempFile("yt-dlp-test-", ".bin")
        try {
            ZipOutputStream(tempFile.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("yt_dlp/version.py"))
                zos.write("__version__ = '2026.11.17'\n".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            assertEquals("2026.11.17", LocalEngineManager.readBinaryVersionFromZipapp(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun parsesYtdlpHttpStatusExitCodeAndClassifiesMediaTypes() {
        assertEquals(403, DevwolfDownloadService.parseHttpStatusFromYtdlpError("ERROR: [TikTok] HTTP Error 403: Forbidden"))
        assertNull(DevwolfDownloadService.parseHttpStatusFromYtdlpError("ERROR: Unable to extract video data"))
        assertEquals(2, DevwolfDownloadService.parseExitCodeFromYtdlpError("yt-dlp failed with exit code 2"))
        assertEquals(1, DevwolfDownloadService.parseExitCodeFromYtdlpError("ERROR: Generic failure"))

        val singleVideo = listOf(
            ResolvedMediaItem(1, "1", MediaItemType.VIDEO, "https://example.com/v.mp4", "v.mp4", "video/mp4"),
        )
        val carousel = listOf(
            ResolvedMediaItem(1, "1", MediaItemType.IMAGE, "https://example.com/1.jpg", "1.jpg", "image/jpeg"),
            ResolvedMediaItem(2, "2", MediaItemType.VIDEO, "https://example.com/2.mp4", "2.mp4", "video/mp4"),
        )
        assertEquals("video", DevwolfDownloadService.classifyMediaTypeFromItems(singleVideo))
        assertEquals("carousel (2 itens)", DevwolfDownloadService.classifyMediaTypeFromItems(carousel))
        assertEquals("unknown", DevwolfDownloadService.classifyMediaTypeFromItems(emptyList()))
    }

    @Test
    fun recordsAllNineDiagnosticsFieldsAndSanitizesSecrets() {
        PerformanceTracker.recordJob(
            PerformanceTracker.JobPerformanceRecord(
                jobId = "job-fail-diag",
                platform = "reddit",
                engineUsed = "yt-dlp",
                engineVersion = "2026.11.17",
                strategyUsed = "yt-dlp-single-pass",
                status = "failed",
                resolutionMs = 310L,
                transferMs = 120L,
                totalJobMs = 430L,
                httpRequestCount = 2,
                errorCode = "AUTH_REQUIRED",
                technicalReason = "HTTP 401 Unauthorized; Authorization: Bearer secretOAuth99; reddit_session=topsecret",
                rawErrorSanitized = "ERROR: [Reddit] HTTP Error 401: access_token=tok123&signature=sig999",
                httpStatus = 401,
                exitCode = 1,
                mediaTypeIdentified = "carousel (3 itens)",
                strategyAttempts = listOf(
                    StrategyAttemptMetric(
                        strategyName = "RedditJsonStrategy",
                        durationMs = 140L,
                        httpRequests = 2,
                        success = false,
                        errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                        note = "HTTP 403 Fastly WAF?token=leak123",
                    ),
                    StrategyAttemptMetric(
                        strategyName = "yt-dlp-single-pass",
                        durationMs = 170L,
                        httpRequests = 0,
                        success = false,
                        errorCode = DevwolfErrorCode.AUTH_REQUIRED,
                        note = "HTTP Error 401: Unauthorized",
                    ),
                ),
            ),
        )

        val report = PerformanceTracker.buildDiagnosticsReport("2026.11.17", "2026-11-18T01:00:00Z")
        assertEquals("reddit", report.getString("platform"))
        assertEquals("yt-dlp", report.getString("engineUsed"))
        assertEquals("2026.11.17", report.getString("engineVersion"))
        assertEquals(401, report.getInt("httpStatus"))
        assertEquals(1, report.getInt("exitCode"))
        assertEquals("AUTH_REQUIRED", report.getString("errorCode"))
        assertEquals("carousel (3 itens)", report.getString("mediaTypeIdentified"))
        assertEquals(310L, report.getLong("resolutionMs"))
        assertEquals(120L, report.getLong("transferMs"))
        assertEquals(430L, report.getLong("totalJobMs"))

        val rawSanitized = report.getString("rawErrorSanitized")
        assertFalse(rawSanitized.contains("tok123"))
        assertFalse(rawSanitized.contains("sig999"))

        val attemptsJson = report.getJSONArray("strategyAttempts")
        assertEquals(2, attemptsJson.length())
        assertEquals("RedditJsonStrategy", attemptsJson.getJSONObject(0).getString("strategyName"))
        assertFalse(attemptsJson.getJSONObject(0).getString("note").contains("leak123"))

        val log = report.getString("sanitizedLog")
        assertTrue(log.contains("plataforma=reddit"))
        assertTrue(log.contains("mídia=carousel (3 itens)"))
        assertTrue(log.contains("Tentativas por estratégia:"))
        assertTrue(log.contains("http=401"))
        assertTrue(log.contains("exitCode=1"))
        assertFalse(log.contains("secretOAuth99"))
        assertFalse(log.contains("topsecret"))
        assertFalse(log.contains("leak123"))
        assertFalse(log.contains("tok123"))
    }

    @Test
    fun computesThumbnailSampleSizeAndAspectPreservingDimensions() {
        assertEquals(1, MediaStoreHelper.calculateInSampleSize(480, 480))
        assertEquals(1, MediaStoreHelper.calculateInSampleSize(720, 1280))
        assertEquals(4, MediaStoreHelper.calculateInSampleSize(3840, 2160))
        assertEquals(4, MediaStoreHelper.calculateInSampleSize(4000, 3000))

        // Smaller images must not be upscaled
        assertEquals(320 to 240, MediaStoreHelper.computeScaledDimensions(320, 240))
        // Portrait 1080x1920 -> 270x480
        assertEquals(270 to 480, MediaStoreHelper.computeScaledDimensions(1080, 1920))
        // Landscape 1920x1080 -> 480x270
        assertEquals(480 to 270, MediaStoreHelper.computeScaledDimensions(1920, 1080))

        // Audio / nonexistent files safely return null without throwing
        assertNull(MediaStoreHelper.generateThumbnailDataUrl(File("missing.mp4"), "video/mp4"))
        val tempAudio = File.createTempFile("thumb-audio-", ".mp3")
        try {
            assertNull(MediaStoreHelper.generateThumbnailDataUrl(tempAudio, "audio/mpeg"))
        } finally {
            tempAudio.delete()
        }
    }

    @Test
    fun stripsOldQueueThumbnailsWithoutMutatingOriginalSnapshot() {
        val fileObj = JSONObject().apply {
            put("id", "job-1_0")
            put("type", "video")
            put("thumbnailDataUrl", "data:image/jpeg;base64,/9j/4AAQ")
        }
        val original = JSONObject().apply {
            put("jobId", "job-1")
            put("status", "ready")
            put("thumbnailDataUrl", "data:image/jpeg;base64,/9j/4AAQ")
            put("files", JSONArray().put(fileObj))
            put("items", JSONArray().put(JSONObject(fileObj.toString())))
        }

        val copy = LocalQueueStore.stripThumbnailsCopy(original)

        assertFalse(copy.has("thumbnailDataUrl"))
        assertFalse(copy.getJSONArray("files").getJSONObject(0).has("thumbnailDataUrl"))
        assertFalse(copy.getJSONArray("items").getJSONObject(0).has("thumbnailDataUrl"))
        assertTrue(original.has("thumbnailDataUrl"))
        assertTrue(original.getJSONArray("files").getJSONObject(0).has("thumbnailDataUrl"))
        assertEquals(24, LocalQueueStore.MAX_JOBS_WITH_THUMBNAILS)
    }
}
