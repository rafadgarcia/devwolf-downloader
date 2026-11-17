package com.devwolf.downloader

import com.devwolf.downloader.resolvers.DevwolfErrorCode
import com.devwolf.downloader.resolvers.ErrorClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
}
