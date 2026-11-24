package com.devwolf.downloader.resolvers

import android.content.SharedPreferences
import com.devwolf.downloader.SecureVault
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class Rc3EnginesRegressionTest {

    @Test
    fun pinterestVideoPinSelectsHighestMp4AndDelegatesHlsOnlyToYtdlpWithoutReturningCoverImage() {
        // 1. __PWS_DATA__ video Pin with V_720P and V_1080P selects highest-res direct MP4
        val pwsMp4Html = """
            <html><body>
            <script id="__PWS_DATA__" type="application/json">
            {
              "props": {
                "initialReduxState": {
                  "pins": {
                    "111222333": {
                      "id": "111222333",
                      "title": "Pin Vídeo HD",
                      "is_video": true,
                      "images": {
                        "orig": { "width": 1080, "height": 1920, "url": "https://i.pinimg.com/originals/cover.jpg" }
                      },
                      "videos": {
                        "video_list": {
                          "V_HLSV3_MOBILE": { "width": 720, "height": 1280, "url": "https://v.pinimg.com/videos/mc/hls/vid.m3u8" },
                          "V_720P": { "width": 720, "height": 1280, "url": "https://v.pinimg.com/videos/mc/720p/vid.mp4" },
                          "V_1080P": { "width": 1080, "height": 1920, "url": "https://v.pinimg.com/videos/mc/1080p/vid.mp4" }
                        }
                      }
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()
        val mp4Res = GalleryDlLocalResolver.parsePinterestHtml(pwsMp4Html, "111222333")
        assertNotNull(mp4Res)
        assertEquals(1, mp4Res!!.items.size)
        assertEquals(MediaItemType.VIDEO, mp4Res.items[0].type)
        assertEquals("https://v.pinimg.com/videos/mc/1080p/vid.mp4", mp4Res.items[0].url)
        assertEquals(1080, mp4Res.items[0].width)

        // 2. __PWS_DATA__ video Pin with ONLY .m3u8 HLS + cover image MUST NOT return cover thumbnail!
        val pwsHlsOnlyHtml = """
            <html><head>
              <meta property="og:image" content="https://i.pinimg.com/originals/cover.jpg" />
            </head><body>
            <script id="__PWS_DATA__" type="application/json">
            {
              "props": {
                "initialReduxState": {
                  "pins": {
                    "444555666": {
                      "id": "444555666",
                      "is_video": true,
                      "images": {
                        "orig": { "width": 1080, "height": 1920, "url": "https://i.pinimg.com/originals/cover.jpg" }
                      },
                      "videos": {
                        "video_list": {
                          "V_HLSV3_MOBILE": { "width": 720, "height": 1280, "url": "https://v.pinimg.com/videos/mc/hls/only_hls.m3u8?token=abc" }
                        }
                      }
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()
        try {
            GalleryDlLocalResolver.parsePinterestHtml(pwsHlsOnlyHtml, "444555666")
            fail("Expected EXTRACTION_FAILED for HLS-only Pinterest video Pin")
        } catch (e: ResolverException) {
            assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
            assertFalse(e.errorCode.isTerminal)
            assertTrue(EngineRouter.shared.shouldFallback(EngineKind.GALLERY_DL_LOCAL, EngineKind.YT_DLP, e.errorCode))
        }

        // 3. ld+json VideoObject without direct MP4 (or .m3u8) delegates to yt-dlp instead of returning cover image
        val ldVideoHls = """
            <html><head>
            <script type="application/ld+json">
            {
              "@type": "VideoObject",
              "name": "Video Pin LD",
              "contentUrl": "https://v.pinimg.com/videos/mc/hls/ld.m3u8",
              "image": "https://i.pinimg.com/originals/thumb.jpg"
            }
            </script>
            </head></html>
        """.trimIndent()
        try {
            GalleryDlLocalResolver.parsePinterestHtml(ldVideoHls, "777888999")
            fail("Expected EXTRACTION_FAILED for VideoObject HLS")
        } catch (e: ResolverException) {
            assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
        }

        // 4. Story Pin with video block containing only HLS throws EXTRACTION_FAILED
        val storyHlsHtml = """
            <html><body>
            <script id="__PWS_DATA__" type="application/json">
            {
              "props": {
                "initialReduxState": {
                  "pins": {
                    "999000111": {
                      "id": "999000111",
                      "story_pin_data": {
                        "pages": [{
                          "blocks": [{
                            "block_type": 3,
                            "video": {
                              "video_list": {
                                "V_HLSV4": { "url": "https://v.pinimg.com/videos/mc/hls/story.m3u8" }
                              }
                            }
                          }]
                        }]
                      }
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()
        try {
            GalleryDlLocalResolver.parsePinterestHtml(storyHlsHtml, "999000111")
            fail("Expected EXTRACTION_FAILED for Story Pin HLS video block")
        } catch (e: ResolverException) {
            assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
        }

        // 5. OpenGraph og:type=video with only og:image (no MP4) delegates to yt-dlp
        val ogVideoNoMp4 = """
            <html><head>
              <meta property="og:type" content="video.other" />
              <meta property="og:image" content="https://i.pinimg.com/originals/thumb_only.jpg" />
            </head><body></body></html>
        """.trimIndent()
        try {
            GalleryDlLocalResolver.parsePinterestHtml(ogVideoNoMp4, "123123123")
            fail("Expected EXTRACTION_FAILED for og:type=video without MP4")
        } catch (e: ResolverException) {
            assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
        }

        // 6. Video metadata outside the parsed Pin node still blocks an OpenGraph cover fallback.
        val unmatchedVideoMetadataHtml = """
            <html><head>
              <meta property="og:image" content="https://i.pinimg.com/originals/video-cover.jpg" />
            </head><body>
              <script type="application/json">
                {"resource_response":{"data":{"videos":{"video_list":{"V_HLS":{"url":"https://v.pinimg.com/videos/mc/hls/video.m3u8"}}}}}}
              </script>
            </body></html>
        """.trimIndent()
        try {
            GalleryDlLocalResolver.parsePinterestHtml(unmatchedVideoMetadataHtml, "998877665")
            fail("Expected video metadata to prevent returning the Pinterest cover image")
        } catch (e: ResolverException) {
            assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
        }

        // 7. Pinterest Relay Story Pin response uses v1.pinimg.com and camelCase video fields.
        val relayVideoHtml = """
            <html><head>
              <meta property="og:image" content="https://i.pinimg.com/736x/cover.jpg" />
            </head><body>
              <script data-relay-completed-request="true" type="text/javascript">
                window.__PWS_RELAY_REGISTER_COMPLETED_REQUEST__("%7B%22pinId%22%3A%22639722322120866703%22%7D", {"data":{"storyPage":{"__typename":"StoryPinVideoBlock","videoDataV2":{"videoList720P":{"v720P":{"url":"https://v1.pinimg.com/videos/iht/expMp4/clip_720w.mp4"}}}}}});
              </script>
            </body></html>
        """.trimIndent()
        val relayVideo = GalleryDlLocalResolver.parsePinterestHtml(relayVideoHtml, "639722322120866703")
        assertNotNull(relayVideo)
        assertEquals(MediaItemType.VIDEO, relayVideo!!.items.single().type)
        assertEquals("https://v1.pinimg.com/videos/iht/expMp4/clip_720w.mp4", relayVideo.items.single().url)

        // 8. Genuine photo Story Pin gallery continues resolving fast locally
        val storyImagesHtml = """
            <html><body>
            <script id="__PWS_DATA__" type="application/json">
            {
              "props": {
                "initialReduxState": {
                  "pins": {
                    "222333444": {
                      "id": "222333444",
                      "story_pin_data": {
                        "pages": [
                          { "blocks": [{ "image": { "images": { "orig": { "width": 1080, "height": 1920, "url": "https://i.pinimg.com/originals/p1.jpg" } } } }] },
                          { "blocks": [{ "image": { "images": { "orig": { "width": 1080, "height": 1920, "url": "https://i.pinimg.com/originals/p2.png" } } } }] }
                        ]
                      }
                    }
                  }
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()
        val storyRes = GalleryDlLocalResolver.parsePinterestHtml(storyImagesHtml, "222333444")
        assertNotNull(storyRes)
        assertEquals(2, storyRes!!.items.size)
        assertEquals(MediaItemType.IMAGE, storyRes.items[0].type)
        assertEquals(MediaItemType.IMAGE, storyRes.items[1].type)
    }

    @Test
    fun httpMediaDownloaderRejectsImageThumbnailDisguisedAsVideo() {
        val server = MockWebServer()
        server.start()
        val tempDir = Files.createTempDirectory("devwolf_magic_guard").toFile()
        try {
            // Case A: Content-Type image/jpeg for MediaItemType.VIDEO
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "image/jpeg")
                    .setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))),
            )
            // Case B: Generic octet-stream Content-Type but JPEG magic bytes
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))),
            )

            val downloader = HttpMediaDownloader(SafeHttpClient(allowLoopbackForTesting = true))
            val item = ResolvedMediaItem(
                index = 1,
                id = "vid_1",
                type = MediaItemType.VIDEO,
                url = server.url("/thumb.mp4").toString(),
                fileName = "pin_01.mp4",
                mimeType = "video/mp4",
            )

            for (attempt in 1..2) {
                try {
                    downloader.downloadAll(listOf(item), tempDir)
                    fail("Expected EXTRACTION_FAILED on attempt $attempt when JPEG returned for VIDEO")
                } catch (e: ResolverException) {
                    assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
                }
            }
        } finally {
            tempDir.deleteRecursively()
            server.shutdown()
        }
    }

    @Test
    fun tiktokErrorClassifierDoesNotTriggerFalseExtractorOutdatedOnBoilerplate() {
        val err1 = "ERROR: [TikTok] 739123456789: Unable to extract webpage video data; please report this issue on https://github.com/yt-dlp/yt-dlp/issues?q= , filling out the appropriate issue template. Confirm you are on the latest version using yt-dlp -U"
        val (code1, msg1) = ErrorClassifier.fromYtdlpOutput(err1)
        assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, code1)
        assertTrue(msg1.contains("Unable to extract webpage video data"))
        assertFalse(msg1.contains("latest version"))

        val err2 = "ERROR: [TikTok] status code 10204; Make sure you are using the latest version"
        val (code2, msg2) = ErrorClassifier.fromYtdlpOutput(err2)
        assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, code2)
        assertTrue(msg2.contains("10204"))

        val errWithAgeWarning = """
            WARNING: Your yt-dlp version (2024.11.04) is older than 90 days!
            ERROR: [TikTok] Unable to extract video data
        """.trimIndent()
        val (code3, _) = ErrorClassifier.fromYtdlpOutput(errWithAgeWarning)
        assertEquals(DevwolfErrorCode.EXTRACTOR_OUTDATED, code3)
    }

    @Test
    fun threadsNativeResolverSupportsVideoPhotoCarouselSessionsAndRouting() {
        val router = EngineRouter.shared
        val planCom = router.route("https://www.threads.com/@devwolf/post/C9VidThread1?xmt=abc&slof=1")
        assertEquals("threads", planCom.platform)
        assertEquals(EngineKind.DNR, planCom.primaryEngine)
        assertEquals(listOf(EngineKind.YT_DLP), planCom.fallbackEngines)
        assertFalse(planCom.shouldInitializeYtdlpUpfront)

        val planNet = router.route("https://threads.net/t/C9ShortThread/")
        assertEquals("threads", planNet.platform)
        assertEquals(EngineKind.DNR, planNet.primaryEngine)

        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val recordedReqs = mutableListOf<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recordedReqs.add(request)
                    val path = request.path.orEmpty()
                    return when {
                        path.contains("/@devwolf/post/C9CarThread") -> {
                            MockResponse().setResponseCode(200).setBody(
                                """
                                <html><body>
                                <script type="application/json" data-sjs>
                                {
                                  "require": [["ScheduledServerJS", "handle", null, [{
                                    "__bbox": {
                                      "result": {
                                        "data": {
                                          "data": {
                                            "edges": [{
                                              "node": {
                                                "thread_items": [{
                                                  "post": {
                                                    "code": "C9CarThread",
                                                    "user": { "username": "devwolf", "is_private": false },
                                                    "caption": { "text": "Carrossel Threads RC3" },
                                                    "carousel_media": [
                                                      {
                                                        "image_versions2": {
                                                          "candidates": [
                                                            { "width": 640, "height": 640, "url": "$base/cdn/t_low.jpg" },
                                                            { "width": 1080, "height": 1080, "url": "$base/cdn/t_hd.jpg" }
                                                          ]
                                                        }
                                                      },
                                                      {
                                                        "video_versions": [
                                                          { "width": 480, "height": 854, "url": "$base/cdn/t_480.mp4" },
                                                          { "width": 1080, "height": 1920, "url": "$base/cdn/t_1080.mp4" }
                                                        ]
                                                      }
                                                    ]
                                                  }
                                                }]
                                              }
                                            }]
                                          }
                                        }
                                      }
                                    }
                                  }]]]
                                }
                                </script>
                                </body></html>
                                """.trimIndent(),
                            )
                        }
                        path.contains("/@devwolf/post/C9PhotoThread") -> {
                            MockResponse().setResponseCode(200).setBody(
                                """
                                <html><head>
                                  <meta property="og:title" content="Foto Threads" />
                                  <meta property="og:image" content="$base/cdn/photo.jpg" />
                                </head><body></body></html>
                                """.trimIndent(),
                            )
                        }
                        path.startsWith("/login") -> {
                            MockResponse().setResponseCode(200).setBody("<html><body>Log in with Instagram</body></html>")
                        }
                        path.contains("/@devwolf/post/C9AuthThread") -> {
                            MockResponse()
                                .setResponseCode(302)
                                .addHeader("Location", "$base/login?next=/@devwolf/post/C9AuthThread")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val resolver = ThreadsNativeResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                baseUrl = base,
            )

            // 1. Mixed carousel + optional Threads session cookie
            val carouselRes = resolver.resolve(
                ResolutionRequest(
                    url = "https://www.threads.com/@devwolf/post/C9CarThread",
                    platform = "threads",
                    cookieHeader = "sessionid=threads_sess_123",
                ),
            )
            assertEquals("dnr", carouselRes.engineUsed)
            assertEquals("ThreadsWebPageJsonStrategy", carouselRes.strategyUsed)
            assertEquals("devwolf", carouselRes.author)
            assertEquals(2, carouselRes.items.size)
            assertEquals(MediaItemType.IMAGE, carouselRes.items[0].type)
            assertEquals("$base/cdn/t_hd.jpg", carouselRes.items[0].url)
            assertEquals(MediaItemType.VIDEO, carouselRes.items[1].type)
            assertEquals("$base/cdn/t_1080.mp4", carouselRes.items[1].url)
            assertEquals("sessionid=threads_sess_123", recordedReqs.first().getHeader("Cookie"))

            // 2. Single photo via OpenGraph
            val photoRes = resolver.resolve(
                ResolutionRequest(
                    url = "https://www.threads.net/@devwolf/post/C9PhotoThread",
                    platform = "threads",
                ),
            )
            assertEquals(1, photoRes.items.size)
            assertEquals(MediaItemType.IMAGE, photoRes.items[0].type)
            assertEquals("$base/cdn/photo.jpg", photoRes.items[0].url)

            // 3. Login wall -> AUTH_REQUIRED with Threads message
            try {
                resolver.resolve(
                    ResolutionRequest(
                        url = "https://www.threads.com/@devwolf/post/C9AuthThread",
                        platform = "threads",
                    ),
                )
                fail("Expected AUTH_REQUIRED for Threads login wall")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.AUTH_REQUIRED, e.errorCode)
                assertTrue(e.userMessage.contains("Threads", ignoreCase = true))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun multiPlatformSecureVaultPreservesInstagramSessionAndIsolatesRedditAndThreads() {
        val encrypted = InMemorySharedPreferences()
        val meta = InMemorySharedPreferences()

        // Simulate pre-existing RC2 Instagram session already stored on user's device
        encrypted.edit()
            .putString("session_cookie_instagram", "sessionid=ig_legacy_123; csrftoken=ig_csrf")
            .putString("session_ua_instagram", "LegacyWebViewUA/1.0")
            .putString("session_updated_instagram", "2026-11-17T12:00:00Z")
            .putString("session_source_instagram", "webview")
            .apply()

        val vault = SecureVault(
            encryptedPrefsProvider = { encrypted },
            metaPrefsProvider = { meta },
        )

        // 1. Existing Instagram session is 100% intact via both legacy and new APIs
        val legacyIg = vault.getInstagramSession()
        assertNotNull(legacyIg)
        assertEquals("sessionid=ig_legacy_123; csrftoken=ig_csrf", legacyIg!!.cookieHeader)
        assertEquals("LegacyWebViewUA/1.0", legacyIg.userAgent)
        assertTrue(legacyIg.hasSessionId)

        val igUnified = vault.getPlatformSession("instagram")
        assertNotNull(igUnified)
        assertEquals(SecureVault.AUTH_TYPE_WEBVIEW_COOKIE, igUnified!!.authType)
        assertEquals("sessionid=ig_legacy_123; csrftoken=ig_csrf", igUnified.cookieHeader)

        // 2. Save Reddit OAuth2 token & Threads WebView cookie independently
        vault.savePlatformOAuthSession(
            platform = "reddit",
            accessToken = "reddit_access_secret",
            refreshToken = "reddit_refresh_secret",
            clientId = "reddit_app_client_id",
            username = "rafa_dev",
            expiresAtEpochMs = 1_900_000_000_000L,
        )
        vault.savePlatformCookieSession(
            platform = "threads",
            cookieHeader = "sessionid=threads_cookie_secret",
            userAgent = "ThreadsWebViewUA/1.0",
            source = "webview",
        )

        val redditSess = vault.getPlatformSession("reddit")
        assertNotNull(redditSess)
        assertEquals(SecureVault.AUTH_TYPE_OAUTH_TOKEN, redditSess!!.authType)
        assertEquals("reddit_access_secret", redditSess.accessToken)
        assertEquals("rafa_dev", redditSess.username)
        assertNull(redditSess.cookieHeader)

        val threadsSess = vault.getPlatformSession("threads")
        assertNotNull(threadsSess)
        assertEquals(SecureVault.AUTH_TYPE_WEBVIEW_COOKIE, threadsSess!!.authType)
        assertEquals("sessionid=threads_cookie_secret", threadsSess.cookieHeader)

        // 3. Sanitized JSONArray summary for JS bridge never leaks raw secrets
        val jsonSummary = vault.getPlatformSessions().toString()
        assertFalse(jsonSummary.contains("ig_legacy_123"))
        assertFalse(jsonSummary.contains("reddit_access_secret"))
        assertFalse(jsonSummary.contains("reddit_refresh_secret"))
        assertFalse(jsonSummary.contains("threads_cookie_secret"))
        assertTrue(jsonSummary.contains("rafa_dev"))
        assertTrue(jsonSummary.contains("oauth_token"))

        // 4. Revoking Reddit leaves Instagram and Threads untouched
        assertTrue(vault.revokePlatformSession("reddit"))
        assertNull(vault.getPlatformSession("reddit"))
        assertNotNull(vault.getInstagramSession())
        assertNotNull(vault.getPlatformSession("threads"))
    }

    @Test
    fun unauthenticatedReddit403ReturnsRedditAuthRequiredNeverInstagram() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .addHeader("Content-Type", "text/html")
                    .setBody("<html><body><h1>whoa there, pardner!</h1><p>You've been blocked by network security.</p></body></html>"),
            )
            val resolver = GalleryDlLocalResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                redditBaseUrl = server.url("/").toString().trimEnd('/'),
            )
            try {
                resolver.resolve(
                    ResolutionRequest(
                        url = "https://www.reddit.com/r/pics/comments/1abc2de/sample/",
                        platform = "reddit",
                    ),
                )
                fail("Expected AUTH_REQUIRED for unauthenticated Reddit 403")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.AUTH_REQUIRED, e.errorCode)
                assertTrue(e.userMessage.contains("Reddit", ignoreCase = true))
                assertFalse(e.userMessage.contains("Instagram", ignoreCase = true))
                assertEquals(1, server.requestCount)
            }
        } finally {
            server.shutdown()
        }
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") ((data[key] as? MutableSet<String>) ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = REMOVED }
            override fun clear() = apply { clearAll = true }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in pending) {
                    if (v === REMOVED || v == null) data.remove(k) else data[k] = v
                }
            }
        }

        private object REMOVED
    }
}
