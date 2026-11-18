package com.devwolf.downloader.resolvers

import com.devwolf.downloader.UrlExtractor
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

/**
 * Diagnostic and regression suite validating all v3.0.0-rc.2 fixes:
 * 1. Instagram Polaris HTML/JS shell containing "captcha"/"challenge_required" in <script> returns EXTRACTION_FAILED (not false CAPTCHA_REQUIRED), while genuine challenges remain terminal.
 * 2. Session-first execution in InstagramNativeResolver (WebPageJsonStrategy -> AuthorizedWebApiStrategy -> EmbedCaptionedStrategy) and recoverable yt-dlp fallback.
 * 3. InstagramParsers.extractPageContext reads al:ios:url / al:android:url media?id=... and AuthorizedWebApiStrategy calls /api/v1/media/{id}/info/.
 * 4. Reddit HTTP 403 WAF/Fastly HTML is classified as recoverable EXTRACTION_FAILED (allowing old.reddit.com + yt-dlp fallback), while genuine {"reason":"private"} JSON remains PRIVATE_MEDIA.
 * 5. Reddit /r/<sub>/s/<id> share links send text/html Accept, extract canonical /comments/ URL from redirect or HTML, never append .json to /s/<id>, and fail with EXTRACTION_FAILED if blocked.
 * 6. Full regression across YouTube Shorts, Facebook, Twitter/X, TikTok, Pinterest, and Instagram carousels.
 */
class Rc1DiagnosticAuditTest {

    @Test
    fun instagramClassifyFailureIgnoresPolarisScriptStringsAndDetectsGenuineChallenges() {
        val sanitizedPolarisShell = """
            <!DOCTYPE html>
            <html lang="en">
            <head><title>Instagram</title></head>
            <body>
            <div id="mount_0_0_xx"></div>
            <script type="application/json" data-sjs>
            {
              "require": [["PolarisAuthPlatformReCaptcha", "init", [], [{
                "arkose_captcha": "553648129.polaris.AuthPlatformReCaptcha",
                "google_recaptcha": "enabled",
                "whatsapp_arkose_captcha": false,
                "challenge_route": "/challenge/",
                "error_codes": ["challenge_required", "checkpoint_required"]
              }]]]
            }
            </script>
            </body>
            </html>
        """.trimIndent()

        // 1. Normal Polaris SPA shell without media returns recoverable EXTRACTION_FAILED (NEVER CAPTCHA_REQUIRED)
        val classifiedShell = InstagramParsers.classifyFailure(
            statusCode = 200,
            finalUrl = "https://www.instagram.com/p/C-example/embed/captioned/?cr=1&v=14&wp=1080",
            body = sanitizedPolarisShell,
            hasSessionCookie = true,
        )
        assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, classifiedShell)
        assertFalse(classifiedShell.isTerminal)

        // 2. Login wall wrapped in the same Polaris JS shell is classified accurately as AUTH_REQUIRED
        val loginWall = InstagramParsers.classifyFailure(
            statusCode = 200,
            finalUrl = "https://www.instagram.com/accounts/login/?next=/reel/Abc123/",
            body = sanitizedPolarisShell + "\n<main>Log in to see photos and videos</main>",
            hasSessionCookie = false,
        )
        assertEquals(DevwolfErrorCode.AUTH_REQUIRED, loginWall)

        // 3. 404 wrapped in Polaris JS shell is classified as MEDIA_UNAVAILABLE
        val notFound = InstagramParsers.classifyFailure(
            statusCode = 404,
            finalUrl = "https://www.instagram.com/p/Missing123/",
            body = sanitizedPolarisShell + "\n<main>Sorry, this page isn't available.</main>",
            hasSessionCookie = true,
        )
        assertEquals(DevwolfErrorCode.MEDIA_UNAVAILABLE, notFound)

        // 4. Genuine challenge URL redirects -> CAPTCHA_REQUIRED (terminal)
        assertEquals(
            DevwolfErrorCode.CAPTCHA_REQUIRED,
            InstagramParsers.classifyFailure(
                statusCode = 200,
                finalUrl = "https://www.instagram.com/challenge/?next=/reel/Abc/",
                body = "<html><body><div>Security Check</div></body></html>",
                hasSessionCookie = true,
            ),
        )
        assertEquals(
            DevwolfErrorCode.CAPTCHA_REQUIRED,
            InstagramParsers.classifyFailure(
                statusCode = 200,
                finalUrl = "https://www.instagram.com/checkpoint/123456/",
                body = "<html><body><div>Confirm it's you</div></body></html>",
                hasSessionCookie = true,
            ),
        )

        // 5. Genuine structured JSON challenge payload -> CAPTCHA_REQUIRED (terminal)
        val jsonChallenge = InstagramParsers.classifyFailure(
            statusCode = 400,
            finalUrl = "https://www.instagram.com/api/v1/media/123/info/",
            body = """{"message":"challenge_required","challenge":{"url":"https://www.instagram.com/challenge/"},"status":"fail"}""",
            hasSessionCookie = true,
        )
        assertEquals(DevwolfErrorCode.CAPTCHA_REQUIRED, jsonChallenge)
        assertTrue(jsonChallenge.isTerminal)

        // 6. Genuine visible DOM CAPTCHA form / widget -> CAPTCHA_REQUIRED (terminal)
        val domCaptcha = InstagramParsers.classifyFailure(
            statusCode = 200,
            finalUrl = "https://www.instagram.com/p/C-example/",
            body = """
                <html><body>
                  <form action="/challenge/action/">
                    <div class="g-recaptcha"></div>
                    <p>Verify you are human to continue</p>
                  </form>
                </body></html>
            """.trimIndent(),
            hasSessionCookie = true,
        )
        assertEquals(DevwolfErrorCode.CAPTCHA_REQUIRED, domCaptcha)
        assertTrue(domCaptcha.isTerminal)
    }

    @Test
    fun instagramDnrPrioritizesAuthenticatedSessionFirstAndAllowsYtdlpFallbackOnShell() {
        // Part A: With an active session, WebPageJsonStrategy runs FIRST with Cookie & WebView User-Agent
        val successServer = MockWebServer()
        successServer.start()
        try {
            val base = successServer.url("/").toString().trimEnd('/')
            val recordedPaths = mutableListOf<String>()
            val recordedCookies = mutableListOf<String?>()
            val recordedUserAgents = mutableListOf<String?>()

            successServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    recordedPaths.add(path)
                    recordedCookies.add(request.getHeader("Cookie"))
                    recordedUserAgents.add(request.getHeader("User-Agent"))
                    return when {
                        path.startsWith("/reel/C9AuthReel1/") -> {
                            MockResponse().setResponseCode(200).setBody(
                                """
                                <html><body><script type="application/json">
                                {"xdt_api__v1__media__shortcode__web_info":{"items":[{"code":"C9AuthReel1","media_type":2,"video_versions":[{"width":1080,"height":1920,"url":"$base/cdn/ok.mp4"}]}]}}
                                </script></body></html>
                                """.trimIndent(),
                            )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val resolver = InstagramNativeResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                baseUrl = base,
            )
            val result = resolver.resolve(
                ResolutionRequest(
                    url = "https://www.instagram.com/reel/C9AuthReel1/",
                    platform = "instagram",
                    cookieHeader = "sessionid=valid_session_123; csrftoken=csrf_abc",
                    userAgent = "CustomWebViewUA/1.0",
                ),
            )

            assertEquals("WebPageJsonStrategy", result.strategyUsed)
            assertEquals(1, result.metrics.httpRequestCount)
            assertEquals(1, recordedPaths.size)
            assertEquals("/reel/C9AuthReel1/", recordedPaths[0])
            assertEquals("sessionid=valid_session_123; csrftoken=csrf_abc", recordedCookies[0])
            assertEquals("CustomWebViewUA/1.0", recordedUserAgents[0])
            assertFalse(recordedPaths.any { it.contains("/embed/captioned/") })
        } finally {
            successServer.shutdown()
        }

        // Part B: If all DNR strategies return empty Polaris shells (and /embed/captioned/ hits /accounts/login/),
        // DNR ends with recoverable EXTRACTION_FAILED so EngineRouter falls back to yt-dlp.
        val shellServer = MockWebServer()
        shellServer.start()
        try {
            val base = shellServer.url("/").toString().trimEnd('/')
            val paths = mutableListOf<String>()
            val polarisEmptyShell = """
                <html><body>
                <div id="mount_0_0_xx"></div>
                <script>window.__polaris={"arkose_captcha":"553648129","google_recaptcha":"1"};</script>
                </body></html>
            """.trimIndent()

            shellServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    paths.add(path)
                    return when {
                        path.contains("/embed/captioned/") -> {
                            MockResponse()
                                .setResponseCode(302)
                                .addHeader("Location", "$base/accounts/login/?next=/p/C9ShellOnly/")
                        }
                        path.startsWith("/accounts/login/") -> {
                            MockResponse()
                                .setResponseCode(200)
                                .setBody("<html><body>Log in to see photos and videos</body></html>")
                        }
                        else -> MockResponse().setResponseCode(200).setBody(polarisEmptyShell)
                    }
                }
            }

            val resolver = InstagramNativeResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                baseUrl = base,
            )
            try {
                resolver.resolve(
                    ResolutionRequest(
                        url = "https://www.instagram.com/reel/C9ShellOnly/",
                        platform = "instagram",
                        cookieHeader = "sessionid=valid_session_123; csrftoken=csrf_abc",
                        userAgent = "CustomWebViewUA/1.0",
                    ),
                )
                fail("Expected EXTRACTION_FAILED after all strategies finish")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
                assertFalse(e.errorCode.isTerminal)
                assertEquals(3, e.metrics.strategyAttempts.size)
                assertEquals("WebPageJsonStrategy", e.metrics.strategyAttempts[0].strategyName)
                assertEquals("AuthorizedWebApiStrategy", e.metrics.strategyAttempts[1].strategyName)
                assertEquals("EmbedCaptionedStrategy", e.metrics.strategyAttempts[2].strategyName)
                assertTrue(
                    "EngineRouter must fall back to yt-dlp when DNR returns EXTRACTION_FAILED",
                    EngineRouter.shared.shouldFallback(
                        EngineKind.DNR,
                        EngineKind.YT_DLP,
                        e.errorCode,
                    ),
                )
            }
        } finally {
            shellServer.shutdown()
        }
    }

    @Test
    fun instagramExtractsMediaIdFromMetaTagsAndCallsMediaInfoApi() {
        val htmlWithIosMeta = """
            <html><head>
              <meta property="al:ios:url" content="instagram://media?id=345678901234567890" />
            </head><body></body></html>
        """.trimIndent()
        val ctxIos = InstagramParsers.extractPageContext(htmlWithIosMeta)
        assertEquals("345678901234567890", ctxIos.mediaId)

        val htmlWithAndroidMeta = """
            <html><head>
              <meta content="instagram://media?id=987654321098765432" property="al:android:url" />
            </head><body></body></html>
        """.trimIndent()
        val ctxAndroid = InstagramParsers.extractPageContext(htmlWithAndroidMeta)
        assertEquals("987654321098765432", ctxAndroid.mediaId)

        val server = MockWebServer()
        server.start()
        try {
            val base = server.url("/").toString().trimEnd('/')
            val apiRequests = mutableListOf<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path.startsWith("/reel/C9MetaId1/")) {
                        return MockResponse().setResponseCode(200).setBody(htmlWithIosMeta)
                    }
                    if (path.startsWith("/api/v1/media/345678901234567890/info/")) {
                        apiRequests.add(request)
                        return MockResponse().setResponseCode(200).setBody(
                            """
                            {
                              "items": [{
                                "pk": "345678901234567890",
                                "code": "C9MetaId1",
                                "media_type": 2,
                                "video_versions": [{ "width": 1080, "height": 1920, "url": "$base/cdn/api_reel.mp4" }]
                              }]
                            }
                            """.trimIndent(),
                        )
                    }
                    return MockResponse().setResponseCode(404)
                }
            }

            val resolver = InstagramNativeResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                baseUrl = base,
            )
            val result = resolver.resolve(
                ResolutionRequest(
                    url = "https://www.instagram.com/reel/C9MetaId1/",
                    platform = "instagram",
                    cookieHeader = "sessionid=valid_sess; csrftoken=csrf_xyz",
                    userAgent = "CustomWebViewUA/2.0",
                ),
            )

            assertEquals("AuthorizedWebApiStrategy", result.strategyUsed)
            assertEquals("$base/cdn/api_reel.mp4", result.items.first().url)
            assertEquals(1, apiRequests.size)
            val apiReq = apiRequests.first()
            assertEquals("sessionid=valid_sess; csrftoken=csrf_xyz", apiReq.getHeader("Cookie"))
            assertEquals("csrf_xyz", apiReq.getHeader("X-CSRFToken"))
            assertEquals("CustomWebViewUA/2.0", apiReq.getHeader("User-Agent"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun redditClassifiesFastly403WafAsRecoverableExtractionFailedAndRealPrivateAsTerminal() {
        val fastlyBlockHtml = """
            <!doctype html>
            <html>
            <head><title>Blocked</title></head>
            <body>
              <h1>whoa there, pardner!</h1>
              <p>You've been blocked by network security. To continue, log in to your Reddit account or use your developer token.</p>
            </body>
            </html>
        """.trimIndent()

        // Direct unit check on classifyRedditHttpError:
        // Unauthenticated 403 WAF/block -> AUTH_REQUIRED (prompts Reddit login/OAuth without host cycling)
        assertEquals(
            DevwolfErrorCode.AUTH_REQUIRED,
            GalleryDlLocalResolver.classifyRedditHttpError(403, fastlyBlockHtml, "text/html; charset=utf-8", hasAuth = false),
        )
        // Authenticated 403 -> EXTRACTION_FAILED (recoverable fallback to yt-dlp)
        assertEquals(
            DevwolfErrorCode.EXTRACTION_FAILED,
            GalleryDlLocalResolver.classifyRedditHttpError(403, fastlyBlockHtml, "text/html; charset=utf-8", hasAuth = true),
        )
        assertEquals(
            DevwolfErrorCode.PRIVATE_MEDIA,
            GalleryDlLocalResolver.classifyRedditHttpError(
                403,
                """{"reason": "private", "message": "Forbidden", "error": 403}""",
                "application/json",
            ),
        )
        assertEquals(
            DevwolfErrorCode.PRIVATE_MEDIA,
            GalleryDlLocalResolver.classifyRedditHttpError(
                403,
                """{"subreddit_type": "private", "message": "Forbidden"}""",
                "application/json",
            ),
        )
        assertEquals(
            DevwolfErrorCode.AUTH_REQUIRED,
            GalleryDlLocalResolver.classifyRedditHttpError(401, """{"message": "Unauthorized"}""", "application/json"),
        )
        assertEquals(
            DevwolfErrorCode.AUTH_REQUIRED,
            GalleryDlLocalResolver.classifyRedditHttpError(403, """{"reason": "quarantined"}""", "application/json"),
        )
        assertEquals(
            DevwolfErrorCode.RATE_LIMITED,
            GalleryDlLocalResolver.classifyRedditHttpError(429, "Too Many Requests", "text/plain"),
        )
        assertEquals(
            DevwolfErrorCode.MEDIA_UNAVAILABLE,
            GalleryDlLocalResolver.classifyRedditHttpError(404, """{"message": "Not Found"}""", "application/json"),
        )

        // Integration check: WAF 403 -> EXTRACTION_FAILED, forwards cookieHeader, triggers yt-dlp fallback
        val wafServer = MockWebServer()
        wafServer.start()
        try {
            val base = wafServer.url("/").toString().trimEnd('/')
            val requests = mutableListOf<RecordedRequest>()
            wafServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    return MockResponse()
                        .setResponseCode(403)
                        .addHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody(fastlyBlockHtml)
                }
            }

            val resolver = GalleryDlLocalResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                redditBaseUrl = base,
            )
            try {
                resolver.resolve(
                    ResolutionRequest(
                        url = "https://www.reddit.com/r/aww/comments/1abc2de/public_cat_video/",
                        platform = "reddit",
                        cookieHeader = "reddit_session=user_cookie_123",
                    ),
                )
                fail("Expected recoverable EXTRACTION_FAILED")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
                assertFalse(e.errorCode.isTerminal)
                assertEquals(403, e.httpStatus)
                assertTrue(
                    EngineRouter.shared.shouldFallback(
                        EngineKind.GALLERY_DL_LOCAL,
                        EngineKind.YT_DLP,
                        e.errorCode,
                    ),
                )
                assertEquals("reddit_session=user_cookie_123", requests[0].getHeader("Cookie"))
                assertEquals(SafeHttpClient.DEFAULT_MOBILE_CHROME_UA, requests[0].getHeader("User-Agent"))
            }
        } finally {
            wafServer.shutdown()
        }

        // Integration check: OAuth2 Bearer token succeeds without cycling hosts
        val oauthServer = MockWebServer()
        oauthServer.start()
        try {
            val recordedOAuthReqs = mutableListOf<RecordedRequest>()
            oauthServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recordedOAuthReqs.add(request)
                    return MockResponse()
                        .setResponseCode(200)
                        .addHeader("Content-Type", "application/json")
                        .setBody(
                            """[{"data":{"children":[{"data":{"id":"1abc2de","title":"Reddit OAuth Rescue","url_overridden_by_dest":"https://i.redd.it/rescue.jpg"}}]}}]""",
                        )
                }
            }

            val resolver = GalleryDlLocalResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                oauthRedditBaseUrl = oauthServer.url("/").toString().trimEnd('/'),
            )
            val result = resolver.resolve(
                ResolutionRequest(
                    url = "https://www.reddit.com/r/aww/comments/1abc2de/public_cat_video/",
                    platform = "reddit",
                    accessToken = "reddit_oauth_access_token_xyz",
                    username = "devwolf_user",
                ),
            )
            assertEquals("https://i.redd.it/rescue.jpg", result.items.first().url)
            assertEquals("RedditImageJsonOAuth", result.strategyUsed)
            assertEquals(1, oauthServer.requestCount)
            assertEquals("Bearer reddit_oauth_access_token_xyz", recordedOAuthReqs[0].getHeader("Authorization"))
            assertNull(recordedOAuthReqs[0].getHeader("Cookie"))
            assertTrue(recordedOAuthReqs[0].getHeader("User-Agent").orEmpty().contains("(by /u/devwolf_user)"))
        } finally {
            oauthServer.shutdown()
        }

        // Integration check: genuine private JSON -> PRIVATE_MEDIA (terminal, no fallback)
        val privateServer = MockWebServer()
        privateServer.start()
        try {
            privateServer.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .addHeader("Content-Type", "application/json")
                    .setBody("""{"reason": "private", "message": "Forbidden", "error": 403}"""),
            )
            val resolver = GalleryDlLocalResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                redditBaseUrl = privateServer.url("/").toString().trimEnd('/'),
            )
            try {
                resolver.resolve(
                    ResolutionRequest(
                        url = "https://www.reddit.com/r/secret/comments/9priv/post/",
                        platform = "reddit",
                    ),
                )
                fail("Expected terminal PRIVATE_MEDIA")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.PRIVATE_MEDIA, e.errorCode)
                assertTrue(e.errorCode.isTerminal)
                assertFalse(
                    EngineRouter.shared.shouldFallback(
                        EngineKind.GALLERY_DL_LOCAL,
                        EngineKind.YT_DLP,
                        e.errorCode,
                    ),
                )
            }
        } finally {
            privateServer.shutdown()
        }
    }

    @Test
    fun redditMobileShareLinksSendHtmlAcceptExtractCanonicalAndNeverAppendJsonToSharePath() {
        // 1. UrlExtractor strips tracking params cleanly while keeping /r/<sub>/s/<id>
        assertEquals(
            "https://www.reddit.com/r/brasil/s/XyZ987Share",
            UrlExtractor.normalizeUrl("https://www.reddit.com/r/brasil/s/XyZ987Share?utm_source=share&share_id=abc123&context=3"),
        )
        // 2. buildRedditJsonUrl never appends .json onto an unresolved /s/<id> path
        assertNull(GalleryDlLocalResolver.buildRedditJsonUrl("https://www.reddit.com/r/brasil/s/XyZ987Share"))

        // 3. Blocked /s/ link sends Accept: text/html... and fails with EXTRACTION_FAILED (not PRIVATE_MEDIA)
        val blockedServer = MockWebServer()
        blockedServer.start()
        try {
            val base = blockedServer.url("/").toString().trimEnd('/')
            val recorded = mutableListOf<RecordedRequest>()
            blockedServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    recorded.add(request)
                    return MockResponse()
                        .setResponseCode(403)
                        .addHeader("Content-Type", "text/html")
                        .setBody("<html><body><h1>403 Blocked</h1><p>whoa there, pardner!</p></body></html>")
                }
            }

            val resolver = GalleryDlLocalResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                redditBaseUrl = base,
            )
            try {
                resolver.resolve(
                    ResolutionRequest(
                        url = "https://www.reddit.com/r/brasil/s/XyZ987Share?utm_source=share",
                        platform = "reddit",
                        cookieHeader = "reddit_session=cookie_ok",
                    ),
                )
                fail("Expected EXTRACTION_FAILED")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, e.errorCode)
                assertFalse(e.errorCode.isTerminal)
                assertTrue(recorded.isNotEmpty())
                assertTrue(recorded[0].getHeader("Accept").orEmpty().contains("text/html"))
                assertEquals("reddit_session=cookie_ok", recorded[0].getHeader("Cookie"))
                assertFalse(
                    "Must never append .json to /s/<id>",
                    recorded.any { it.path.orEmpty().contains("/s/XyZ987Share.json") },
                )
            }
        } finally {
            blockedServer.shutdown()
        }

        // 4. HTML <link rel="canonical"> fallback on HTTP 200 /s/ share page resolves the canonical /comments/ JSON
        val canonicalServer = MockWebServer()
        canonicalServer.start()
        try {
            val base = canonicalServer.url("/").toString().trimEnd('/')
            val paths = mutableListOf<String>()
            canonicalServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    paths.add(path)
                    return when {
                        path == "/r/brasil/s/XyZ987Share" -> {
                            MockResponse()
                                .setResponseCode(200)
                                .addHeader("Content-Type", "text/html")
                                .setBody(
                                    """
                                    <html><head>
                                      <link rel="canonical" href="https://www.reddit.com/r/brasil/comments/1xyz99/foto_legal/" />
                                    </head><body></body></html>
                                    """.trimIndent(),
                                )
                        }
                        path == "/r/brasil/comments/1xyz99/foto_legal.json?raw_json=1" -> {
                            MockResponse()
                                .setResponseCode(200)
                                .addHeader("Content-Type", "application/json")
                                .setBody(
                                    """[{"data":{"children":[{"data":{"id":"1xyz99","title":"Foto Legal","url_overridden_by_dest":"https://i.redd.it/legal.jpg"}}]}}]""",
                                )
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val resolver = GalleryDlLocalResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                redditBaseUrl = base,
            )
            val result = resolver.resolve(
                ResolutionRequest(
                    url = "https://www.reddit.com/r/brasil/s/XyZ987Share?share_id=abc",
                    platform = "reddit",
                ),
            )
            assertEquals("1xyz99", result.shortcode)
            assertEquals("https://i.redd.it/legal.jpg", result.items.first().url)
            assertTrue(paths.contains("/r/brasil/comments/1xyz99/foto_legal.json?raw_json=1"))
        } finally {
            canonicalServer.shutdown()
        }
    }

    @Test
    fun regressionAcrossAllSupportedPlatformsAndInstagramCarousels() {
        val router = EngineRouter.shared

        // YouTube Shorts
        val ytShorts = router.route("https://www.youtube.com/shorts/dQw4w9WgXcQ?si=tracking")
        assertEquals("youtube", ytShorts.platform)
        assertEquals(EngineKind.YT_DLP, ytShorts.primaryEngine)
        assertTrue(ytShorts.shouldInitializeYtdlpUpfront)
        assertEquals("https://www.youtube.com/shorts/dQw4w9WgXcQ", ytShorts.normalizedUrl)

        // Facebook
        val fb = router.route("https://www.facebook.com/share/r/123456/?fbclid=xyz")
        assertEquals("facebook", fb.platform)
        assertEquals(EngineKind.YT_DLP, fb.primaryEngine)

        // TikTok
        val tt = router.route("https://vm.tiktok.com/ZM6abc123/?_r=1&_t=2")
        assertEquals("tiktok", tt.platform)
        assertEquals(EngineKind.YT_DLP, tt.primaryEngine)

        // Twitter / X routing + syndication images
        val xPlan = router.route("https://x.com/user/status/987654321?utm_source=x")
        assertEquals("twitter", xPlan.platform)
        assertEquals(EngineKind.YT_DLP, xPlan.primaryEngine)
        assertEquals(listOf(EngineKind.GALLERY_DL_LOCAL), xPlan.fallbackEngines)
        assertTrue(router.shouldFallback(EngineKind.YT_DLP, EngineKind.GALLERY_DL_LOCAL, DevwolfErrorCode.UNSUPPORTED_MEDIA))
        val twitterImgs = GalleryDlLocalResolver.parseTwitterSyndicationJson(
            """
            {
              "text": "Galeria X",
              "user": { "screen_name": "devwolf" },
              "photos": [
                { "url": "https://pbs.twimg.com/media/one.jpg", "width": 1200, "height": 800 },
                { "url": "https://pbs.twimg.com/media/two.png", "width": 1000, "height": 1000 }
              ]
            }
            """.trimIndent(),
            "987654321",
        )
        assertNotNull(twitterImgs)
        assertEquals(2, twitterImgs!!.items.size)
        assertEquals(1, twitterImgs.items[0].index)
        assertEquals(2, twitterImgs.items[1].index)

        // Pinterest routing + parser
        val pinPlan = router.route("https://www.pinterest.com/pin/555444333/")
        assertEquals("pinterest", pinPlan.platform)
        assertEquals(EngineKind.GALLERY_DL_LOCAL, pinPlan.primaryEngine)
        assertEquals(listOf(EngineKind.YT_DLP), pinPlan.fallbackEngines)
        val pinParsed = GalleryDlLocalResolver.parsePinterestHtml(
            """
            <html><head>
              <meta property="og:title" content="Pin Teste" />
              <meta property="og:image" content="https://i.pinimg.com/originals/11/22/33.jpg" />
            </head><body></body></html>
            """.trimIndent(),
            "555444333",
        )
        assertNotNull(pinParsed)
        assertEquals("https://i.pinimg.com/originals/11/22/33.jpg", pinParsed!!.items.first().url)

        // Instagram Carousel (API JSON preserving order 1..N)
        val igCarousel = InstagramParsers.parseApiMediaJson(
            """
            {
              "items": [{
                "code": "C9CarTest",
                "user": { "username": "car_owner", "is_private": false },
                "carousel_media": [
                  { "media_type": 1, "image_versions2": { "candidates": [{ "width": 1080, "height": 1080, "url": "https://cdn.example.com/1.jpg" }] } },
                  { "media_type": 2, "video_versions": [{ "width": 1080, "height": 1920, "url": "https://cdn.example.com/2.mp4" }] }
                ]
              }]
            }
            """.trimIndent(),
            "C9CarTest",
        )
        assertNotNull(igCarousel)
        assertEquals(2, igCarousel!!.items.size)
        assertEquals(1, igCarousel.items[0].index)
        assertEquals(MediaItemType.IMAGE, igCarousel.items[0].type)
        assertEquals(2, igCarousel.items[1].index)
        assertEquals(MediaItemType.VIDEO, igCarousel.items[1].type)

        // yt-dlp 403 Blocked on Reddit remains recoverable
        val (ytdlpCode, _) = ErrorClassifier.fromYtdlpOutput(
            "ERROR: [Reddit] 1abc2de: Unable to download JSON metadata: HTTP Error 403: Blocked",
        )
        assertEquals(DevwolfErrorCode.EXTRACTION_FAILED, ytdlpCode)
        assertFalse(ytdlpCode.isTerminal)
    }
}
