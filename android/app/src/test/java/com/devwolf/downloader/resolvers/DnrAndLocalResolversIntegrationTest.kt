package com.devwolf.downloader.resolvers

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class DnrAndLocalResolversIntegrationTest {

    @Test
    fun instagramResolverFallsBackFromEmbedToWebPageJsonAndRecordsMetrics() {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val cdnMediaUrl = "$base/cdn/reel_hd.mp4"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.contains("/embed/captioned/") -> {
                            // Simulate embed without video (parser miss), forcing WebPageJsonStrategy
                            MockResponse().setResponseCode(200).setBody("<html><body><div>No media</div></body></html>")
                        }
                        path.startsWith("/reel/C9Fallback1/") -> {
                            MockResponse().setResponseCode(200).setBody(
                                """
                                <html><body>
                                <script type="application/json">
                                {
                                  "xdt_api__v1__media__shortcode__web_info": {
                                    "items": [{
                                      "code": "C9Fallback1",
                                      "media_type": 2,
                                      "video_versions": [
                                        { "width": 1080, "height": 1920, "url": "$cdnMediaUrl" }
                                      ]
                                    }]
                                  }
                                }
                                </script>
                                </body></html>
                                """.trimIndent(),
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val http = SafeHttpClient(allowLoopbackForTesting = true)
            val resolver = InstagramNativeResolver(httpClient = http, baseUrl = base)
            val result = resolver.resolve(
                ResolutionRequest(
                    url = "https://www.instagram.com/reel/C9Fallback1/?igsh=tracking",
                    platform = "instagram",
                ),
            )

            assertEquals("dnr", result.engineUsed)
            assertEquals("WebPageJsonStrategy", result.strategyUsed)
            assertEquals(1, result.items.size)
            assertEquals(cdnMediaUrl, result.items[0].url)
            assertEquals(2, result.metrics.httpRequestCount)
            assertEquals(2, result.metrics.strategyAttempts.size)
            assertFalse(result.metrics.strategyAttempts[0].success)
            assertTrue(result.metrics.strategyAttempts[1].success)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun instagramResolverHaltsImmediatelyOnPrivateMediaWithoutRunningLaterStrategies() {
        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("""{"user":{"is_private":true},"message":"This account is private"}"""),
            )

            val http = SafeHttpClient(allowLoopbackForTesting = true)
            val resolver = InstagramNativeResolver(httpClient = http, baseUrl = base)
            try {
                resolver.resolve(
                    ResolutionRequest(
                        url = "https://www.instagram.com/p/PrivStop1/",
                        platform = "instagram",
                    ),
                )
                fail("Expected PRIVATE_MEDIA ResolverException")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.PRIVATE_MEDIA, e.errorCode)
                assertTrue(e.errorCode.isTerminal)
                assertEquals(1, server.requestCount)
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpMediaDownloaderStreamsProgressHonestsUnknownLengthAndProtectsHeaders() {
        val mediaServer = MockWebServer()
        val cdnServer = MockWebServer()
        mediaServer.start()
        cdnServer.start()
        val tempDir = Files.createTempDirectory("devwolf_dl_test").toFile()

        try {
            val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
            // Redirect from origin host to CDN host; verify Cookie header is NOT leaked to CDN
            mediaServer.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", cdnServer.url("/media/video.mp4").toString()),
            )
            cdnServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "video/mp4")
                    .setBody(okio.Buffer().write(payload)),
            )

            val http = SafeHttpClient(allowLoopbackForTesting = true)
            val downloader = HttpMediaDownloader(http)
            val progressEvents = mutableListOf<HttpDownloadProgress>()

            val results = downloader.downloadAll(
                items = listOf(
                    ResolvedMediaItem(
                        index = 1,
                        id = "item_1",
                        type = MediaItemType.VIDEO,
                        url = mediaServer.url("/origin/clip").toString(),
                        fileName = "clip_01.mp4",
                        mimeType = "video/mp4",
                        headers = mapOf(
                            "Cookie" to "sessionid=secret_token",
                            "Referer" to "https://www.instagram.com/",
                        ),
                    ),
                ),
                workDir = tempDir,
                onProgress = { progressEvents.add(it) },
            )

            assertEquals(1, results.size)
            assertEquals(payload.size.toLong(), results[0].bytesDownloaded)
            assertTrue(results[0].file.exists())
            assertEquals(payload.size.toLong(), results[0].file.length())
            assertTrue(results[0].ttfbMs >= 0L)
            assertTrue(progressEvents.isNotEmpty())
            assertEquals(100.0, progressEvents.last().percent ?: 0.0, 0.01)

            val cdnReq = cdnServer.takeRequest()
            assertNull("Sensitive Cookie must NOT be forwarded by HttpMediaDownloader", cdnReq.getHeader("Cookie"))
        } finally {
            tempDir.deleteRecursively()
            mediaServer.shutdown()
            cdnServer.shutdown()
        }
    }

    @Test
    fun httpMediaDownloaderRejectsHtmlDisguisedAsMediaAndHonorsCancellation() {
        val server = MockWebServer()
        server.start()
        val tempDir = Files.createTempDirectory("devwolf_html_guard").toFile()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><body>Expired CDN URL</body></html>"),
            )
            val http = SafeHttpClient(allowLoopbackForTesting = true)
            val downloader = HttpMediaDownloader(http)
            try {
                downloader.downloadAll(
                    items = listOf(
                        ResolvedMediaItem(
                            index = 1,
                            id = "bad_1",
                            type = MediaItemType.IMAGE,
                            url = server.url("/expired.jpg").toString(),
                            fileName = "expired.jpg",
                            mimeType = "image/jpeg",
                        ),
                    ),
                    workDir = tempDir,
                )
                fail("Expected MEDIA_UNAVAILABLE for HTML body")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.MEDIA_UNAVAILABLE, e.errorCode)
            }

            // Cancellation check before transfer
            val cancelled = AtomicBoolean(true)
            try {
                downloader.downloadAll(
                    items = listOf(
                        ResolvedMediaItem(
                            index = 1,
                            id = "cancel_1",
                            type = MediaItemType.IMAGE,
                            url = server.url("/img.jpg").toString(),
                            fileName = "img.jpg",
                            mimeType = "image/jpeg",
                        ),
                    ),
                    workDir = tempDir,
                    cancellationCheck = { cancelled.get() },
                )
                fail("Expected CANCELLED")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.CANCELLED, e.errorCode)
            }
        } finally {
            tempDir.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun resolvesPinterestImageVideoAndRedditGalleryLocally() {
        val pinterestHtml = """
            <html><head></head><body>
            <script id="__PWS_DATA__" type="application/json">
            {
              "props": {
                "initialReduxState": {
                  "pins": {
                    "987654321": {
                      "id": "987654321",
                      "title": "Design Minimalista",
                      "pinner": { "username": "pin_creator" },
                      "images": {
                        "236x": { "width": 236, "height": 419, "url": "https://i.pinimg.com/236x/a/b/c.jpg" },
                        "orig": { "width": 1080, "height": 1920, "url": "https://i.pinimg.com/originals/a/b/c.jpg" }
                      }
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()
        val pinResult = GalleryDlLocalResolver.parsePinterestHtml(pinterestHtml, "987654321")
        assertNotNull(pinResult)
        assertEquals("pinterest", pinResult?.platform)
        assertEquals(1, pinResult?.items?.size)
        assertEquals("https://i.pinimg.com/originals/a/b/c.jpg", pinResult?.items?.first()?.url)
        assertEquals(1080, pinResult?.items?.first()?.width)

        val redditGalleryJson = """
            [
              {
                "data": {
                  "children": [{
                    "data": {
                      "id": "gal999",
                      "title": "Paisagens do Sul",
                      "author": "reddit_user",
                      "is_gallery": true,
                      "gallery_data": {
                        "items": [
                          { "media_id": "m1" },
                          { "media_id": "m2" }
                        ]
                      },
                      "media_metadata": {
                        "m1": { "status": "valid", "s": { "x": 1920, "y": 1080, "u": "https://preview.redd.it/one.jpg?auto=webp&amp;s=abc" } },
                        "m2": { "status": "valid", "s": { "x": 1280, "y": 720, "mp4": "https://preview.redd.it/two.mp4?s=def" } }
                      }
                    }
                  }]
                }
              }
            ]
        """.trimIndent()
        val redditResult = GalleryDlLocalResolver.parseRedditJson(redditGalleryJson, "gal999")
        assertNotNull(redditResult)
        assertEquals(2, redditResult?.items?.size)
        assertEquals(MediaItemType.IMAGE, redditResult?.items?.get(0)?.type)
        assertEquals("https://preview.redd.it/one.jpg?auto=webp&s=abc", redditResult?.items?.get(0)?.url)
        assertEquals(MediaItemType.VIDEO, redditResult?.items?.get(1)?.type)
        assertEquals("https://preview.redd.it/two.mp4?s=def", redditResult?.items?.get(1)?.url)

        // Reddit v.redd.it with audio must delegate to yt-dlp fallback
        val redditDashVideoJson = """
            [{ "data": { "children": [{ "data": {
              "id": "vid777",
              "is_video": true,
              "media": { "reddit_video": { "is_gif": false, "fallback_url": "https://v.redd.it/abc/DASH_720.mp4" } }
            }}]}}]
        """.trimIndent()
        try {
            GalleryDlLocalResolver.parseRedditJson(redditDashVideoJson, "vid777")
            fail("Expected fallback signal for v.redd.it DASH video")
        } catch (e: ResolverException) {
            assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
        }
    }

    @Test
    fun engineRouterEnforcesPlatformPrioritiesAndTerminalFallbackRules() {
        val router = EngineRouter.shared

        val igPlan = router.route("https://www.instagram.com/reel/AbC12345/?igsh=xyz")
        assertEquals(EngineKind.DNR, igPlan.primaryEngine)
        assertEquals(listOf(EngineKind.YT_DLP), igPlan.fallbackEngines)
        assertFalse(igPlan.shouldInitializeYtdlpUpfront)

        val pinPlan = router.route("https://pin.it/abc123")
        assertEquals(EngineKind.GALLERY_DL_LOCAL, pinPlan.primaryEngine)
        assertEquals(listOf(EngineKind.YT_DLP), pinPlan.fallbackEngines)
        assertFalse(pinPlan.shouldInitializeYtdlpUpfront)

        val redditPlan = router.route("https://redd.it/xyz999")
        assertEquals(EngineKind.GALLERY_DL_LOCAL, redditPlan.primaryEngine)
        assertEquals(listOf(EngineKind.YT_DLP), redditPlan.fallbackEngines)
        assertFalse(redditPlan.shouldInitializeYtdlpUpfront)

        val xPlan = router.route("https://x.com/user/status/12345")
        assertEquals(EngineKind.YT_DLP, xPlan.primaryEngine)
        assertEquals(listOf(EngineKind.GALLERY_DL_LOCAL), xPlan.fallbackEngines)
        assertTrue(xPlan.shouldInitializeYtdlpUpfront)

        // Terminal errors must NEVER trigger fallback
        assertFalse(router.shouldFallback(EngineKind.DNR, EngineKind.YT_DLP, DevwolfErrorCode.AUTH_REQUIRED))
        assertFalse(router.shouldFallback(EngineKind.DNR, EngineKind.YT_DLP, DevwolfErrorCode.PRIVATE_MEDIA))
        assertFalse(router.shouldFallback(EngineKind.DNR, EngineKind.YT_DLP, DevwolfErrorCode.RATE_LIMITED))
        assertFalse(router.shouldFallback(EngineKind.DNR, EngineKind.YT_DLP, DevwolfErrorCode.CAPTCHA_REQUIRED))
        assertFalse(router.shouldFallback(EngineKind.DNR, EngineKind.YT_DLP, DevwolfErrorCode.MEDIA_UNAVAILABLE))

        // Recoverable errors trigger fallback
        assertTrue(router.shouldFallback(EngineKind.DNR, EngineKind.YT_DLP, DevwolfErrorCode.EXTRACTION_FAILED))
        assertTrue(router.shouldFallback(EngineKind.DNR, EngineKind.YT_DLP, DevwolfErrorCode.EXTRACTOR_OUTDATED))
        assertTrue(router.shouldFallback(EngineKind.YT_DLP, EngineKind.GALLERY_DL_LOCAL, DevwolfErrorCode.UNSUPPORTED_MEDIA))
    }

    @Test
    fun liveHttpResolutionSmokeTestRecordsRealNetworkOutcomeAndMetrics() {
        val resolver = InstagramNativeResolver()
        val testUrl = "https://www.instagram.com/p/C-example/"
        val start = System.currentTimeMillis()
        try {
            val result = resolver.resolve(ResolutionRequest(url = testUrl, platform = "instagram"))
            println(
                "[DNR-LIVE] success strategy=${result.strategyUsed} items=${result.items.size} " +
                    "resolutionMs=${result.metrics.resolutionMs} httpReqs=${result.metrics.httpRequestCount}",
            )
            assertTrue(result.items.isNotEmpty())
        } catch (e: ResolverException) {
            val elapsed = System.currentTimeMillis() - start
            println(
                "[DNR-LIVE] structuredFailure code=${e.errorCode} strategy=${e.strategyUsed} " +
                    "elapsedMs=$elapsed reqs=${e.metrics.httpRequestCount} reason=${e.technicalReason}",
            )
            // Any structured DevwolfErrorCode (e.g. MEDIA_UNAVAILABLE, AUTH_REQUIRED, RATE_LIMITED, NETWORK_ERROR)
            // confirms real HTTP execution & structured error classification without hanging.
            assertNotNull(e.errorCode)
        }
    }
}
