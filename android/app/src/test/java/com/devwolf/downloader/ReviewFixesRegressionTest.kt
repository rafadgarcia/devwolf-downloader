package com.devwolf.downloader

import com.devwolf.downloader.resolvers.DevwolfErrorCode
import com.devwolf.downloader.resolvers.ErrorClassifier
import com.devwolf.downloader.resolvers.SafeHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

/**
 * Regression coverage for the consistency/performance/security review fixes
 * (error classification, queue persistence, cancellation, progress aggregation, SSRF, sanitizing).
 */
class ReviewFixesRegressionTest {

    // ---- ErrorClassifier ----------------------------------------------------------------------

    @Test
    fun numericMediaIdsNeverTriggerTerminalHttpClassifications() {
        val rateLikeId = "ERROR: [TikTok] 7340429123456789012: Unable to extract webpage video data"
        assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, ErrorClassifier.fromYtdlpOutput(rateLikeId).first)

        val notFoundLikeId = "ERROR: [TikTok] 7311404987654321098: Unable to extract universal data for rehydration"
        assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, ErrorClassifier.fromYtdlpOutput(notFoundLikeId).first)
    }

    @Test
    fun explicitHttpStatusesAreStillClassified() {
        assertEquals(
            DevwolfErrorCode.RATE_LIMITED,
            ErrorClassifier.fromYtdlpOutput("ERROR: [Instagram] abc: HTTP Error 429: Too Many Requests").first,
        )
        assertEquals(
            DevwolfErrorCode.MEDIA_UNAVAILABLE,
            ErrorClassifier.fromYtdlpOutput("ERROR: [generic] Unable to download webpage: HTTP Error 404: Not Found").first,
        )
        assertEquals(
            DevwolfErrorCode.NETWORK_ERROR,
            ErrorClassifier.fromYtdlpOutput("ERROR: Unable to download webpage: <urlopen error [Errno 111] Connection refused>").first,
        )
    }

    @Test
    fun missingFfmpegIsLocalFailureNotRemovedMedia() {
        val (code, msg) = ErrorClassifier.fromYtdlpOutput("ERROR: Postprocessing: ffprobe and ffmpeg not found. Please install or provide the path")
        assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, code)
        assertFalse(code.isTerminal)
        assertTrue(msg.contains("FFmpeg"))
    }

    @Test
    fun ssrfSecurityExceptionIsNotRecoverable() {
        val (code, _) = ErrorClassifier.fromThrowable(SecurityException("Endereço IP não público bloqueado pela política SSRF"))
        assertEquals(DevwolfErrorCode.UNSUPPORTED_MEDIA, code)
    }

    // ---- Progress aggregation -----------------------------------------------------------------

    @Test
    fun progressAggregatorNeverRegressesAcrossVideoAndAudioStreams() {
        val agg = DevwolfDownloadService.YtdlpProgressAggregator()
        agg.onNewStream()
        val v1 = agg.onProgress(DevwolfDownloadService.parseYtdlpProgressLine("[download]  50.0% of 10.00MiB at 1.00MiB/s ETA 00:05")!!)
        val v2 = agg.onProgress(DevwolfDownloadService.parseYtdlpProgressLine("[download] 100.0% of 10.00MiB at 1.00MiB/s ETA 00:00")!!)
        assertEquals(50.0, v1.percent!!, 0.5)
        assertEquals(100.0, v2.percent!!, 0.01)

        agg.onNewStream()
        val a1 = agg.onProgress(DevwolfDownloadService.parseYtdlpProgressLine("[download]   1.0% of 1.00MiB at 500.00KiB/s ETA 00:02")!!)
        assertTrue("percent must not drop back to ~0", a1.percent!! >= v2.percent!!)
        assertTrue("bytes accumulate across streams", a1.bytesDownloaded > v2.bytesDownloaded)
        assertEquals(2, agg.streamCount)
    }

    @Test
    fun maxFilesizeSkipLineIsDetected() {
        assertTrue(
            DevwolfDownloadService.YTDLP_MAX_FILESIZE_REGEX.containsMatchIn(
                "[download] File is larger than max-filesize (734003200 bytes > 524288000 bytes). Aborting.",
            ),
        )
    }

    // ---- LocalQueueStore ----------------------------------------------------------------------

    private fun withTempStore(block: (LocalQueueStore, File, () -> Unit) -> Unit) {
        val dir = File.createTempFile("devwolf-review-", "").apply { delete(); mkdirs() }
        var now = 10_000L
        try {
            val queueFile = File(dir, "queue.json")
            val store = LocalQueueStore(queueFile, File(dir, "share.json")) { now }
            block(store, queueFile) { now += LocalQueueStore.PROGRESS_PERSIST_INTERVAL_MS }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun progressTicksAreThrottledButStatusChangesPersistImmediately() {
        withTempStore { store, queueFile, advance ->
            store.upsertJob(JSONObject().put("jobId", "j1").put("status", "downloading").put("percent", 1))
            val afterStatusChange = queueFile.readText()
            assertTrue(afterStatusChange.contains("\"percent\":1"))

            store.upsertJob(JSONObject().put("jobId", "j1").put("status", "downloading").put("percent", 2))
            assertEquals("same-status tick within interval must not rewrite the file", afterStatusChange, queueFile.readText())
            // In-memory view is always current.
            assertEquals(2, store.getJob("j1")!!.getInt("percent"))

            advance()
            store.upsertJob(JSONObject().put("jobId", "j1").put("status", "downloading").put("percent", 3))
            assertTrue(queueFile.readText().contains("\"percent\":3"))

            store.upsertJob(JSONObject().put("jobId", "j1").put("status", "ready").put("percent", 100))
            assertTrue(queueFile.readText().contains("\"ready\""))
        }
    }

    @Test
    fun flushWritesPendingProgress() {
        withTempStore { store, queueFile, _ ->
            store.upsertJob(JSONObject().put("jobId", "j2").put("status", "downloading").put("percent", 10))
            store.upsertJob(JSONObject().put("jobId", "j2").put("status", "downloading").put("percent", 20))
            assertFalse(queueFile.readText().contains("\"percent\":20"))
            store.flush()
            assertTrue(queueFile.readText().contains("\"percent\":20"))
        }
    }

    @Test
    fun lateCancellationNeverOverwritesFinishedJobs() {
        withTempStore { store, _, _ ->
            store.upsertJob(JSONObject().put("jobId", "done").put("status", "ready"))
            assertFalse(store.upsertJobIfNotFinal(JSONObject().put("jobId", "done").put("status", "cancelled")))
            assertEquals("ready", store.getJob("done")!!.getString("status"))

            store.upsertJob(JSONObject().put("jobId", "live").put("status", "downloading"))
            assertTrue(store.upsertJobIfNotFinal(JSONObject().put("jobId", "live").put("status", "cancelled")))
            // A second cancel snapshot is ignored (no duplicate event).
            assertFalse(store.upsertJobIfNotFinal(JSONObject().put("jobId", "live").put("status", "cancelled")))
        }
    }

    @Test
    fun startupReconciliationKeepsJobsStillRunningInProcess() {
        withTempStore { store, _, _ ->
            store.upsertJob(JSONObject().put("jobId", "running").put("status", "downloading"))
            store.upsertJob(JSONObject().put("jobId", "orphan").put("status", "resolving"))
            store.markInterruptedJobsOnStartup(isStillRunning = { it == "running" })
            assertEquals("downloading", store.getJob("running")!!.getString("status"))
            assertEquals("failed", store.getJob("orphan")!!.getString("status"))
        }
    }

    @Test
    fun storesSharingAFileShareTheSameCache() {
        val dir = File.createTempFile("devwolf-review-shared-", "").apply { delete(); mkdirs() }
        try {
            val queueFile = File(dir, "queue.json")
            val a = LocalQueueStore(queueFile, File(dir, "share.json"))
            val b = LocalQueueStore(queueFile, File(dir, "share.json"))
            a.upsertJob(JSONObject().put("jobId", "x").put("status", "downloading").put("percent", 5))
            a.upsertJob(JSONObject().put("jobId", "x").put("status", "downloading").put("percent", 6))
            assertEquals(6, b.getJob("x")!!.getInt("percent"))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- SSRF ---------------------------------------------------------------------------------

    private fun isPublic(literal: String) = UrlExtractor.isPublicRoutableAddress(InetAddress.getByName(literal))

    @Test
    fun ssrfRangesMatchTheirRealCidrs() {
        // Documentation / reserved ranges are blocked...
        assertFalse(isPublic("192.0.2.10"))
        assertFalse(isPublic("192.0.0.8"))
        assertFalse(isPublic("198.51.100.7"))
        assertFalse(isPublic("203.0.113.9"))
        assertFalse(isPublic("198.18.0.1"))
        // ...but their public neighbours are no longer over-blocked.
        assertTrue(isPublic("192.0.3.1"))
        assertTrue(isPublic("198.51.1.1"))
        assertTrue(isPublic("8.8.8.8"))
    }

    @Test
    fun ipv6EmbeddedPrivateIpv4IsBlocked() {
        assertFalse(isPublic("64:ff9b::a00:1")) // NAT64 -> 10.0.0.1
        assertFalse(isPublic("2002:c0a8:0101::1")) // 6to4 -> 192.168.1.1
        assertFalse(isPublic("2001:db8::1")) // documentation
        assertFalse(isPublic("2001:0::1")) // Teredo
        assertTrue(isPublic("64:ff9b::808:808")) // NAT64 -> 8.8.8.8
        assertTrue(isPublic("2606:4700:4700::1111"))
    }

    // ---- SafeHttpClient -----------------------------------------------------------------------

    @Test
    fun httpUrlsAreUpgradedToHttps() {
        val client = SafeHttpClient()
        assertEquals("https://www.reddit.com/r/pics", client.upgradeToHttpsIfNeeded("http://www.reddit.com/r/pics"))
        assertEquals("https://i.redd.it/a.jpg", client.upgradeToHttpsIfNeeded("https://i.redd.it/a.jpg"))
        val testClient = SafeHttpClient(allowLoopbackForTesting = true)
        assertEquals("http://127.0.0.1:8080/x", testClient.upgradeToHttpsIfNeeded("http://127.0.0.1:8080/x"))
    }

    @Test
    fun diagnosticsSanitizerRedactsWholeCookieHeaderAndRedditTokens() {
        val raw = "Cookie: loid=abc; token_v2=eyJsecret; reddit_session=sess123 | token_v2=eyJother&x=1"
        val sanitized = SafeHttpClient.sanitizeForDiagnostics(raw)
        assertNotNull(sanitized)
        assertFalse(sanitized.contains("eyJsecret"))
        assertFalse(sanitized.contains("sess123"))
        assertFalse(sanitized.contains("eyJother"))
    }
}
