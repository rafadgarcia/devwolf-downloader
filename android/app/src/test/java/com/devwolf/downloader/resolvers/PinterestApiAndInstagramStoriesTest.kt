package com.devwolf.downloader.resolvers

import com.devwolf.downloader.DevwolfDownloadService
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PinterestApiAndInstagramStoriesTest {

    // ---- Pinterest ----------------------------------------------------------------------------

    private val hash = "dc/e5/93/dce5932417aad819284deaff2238576d"

    @Test
    fun derivesMp4CandidatesFromPinimgHls() {
        val candidates = GalleryDlLocalResolver.derivePinimgMp4Candidates("https://v1.pinimg.com/videos/iht/hls/$hash.m3u8")
        assertEquals("https://v1.pinimg.com/videos/iht/expMp4/${hash}_720w.mp4", candidates.first())
        assertTrue(candidates.contains("https://v1.pinimg.com/videos/mc/720p/$hash.mp4"))
        assertTrue(candidates.contains("https://v1.pinimg.com/videos/iht/720p/$hash.mp4"))
        assertTrue(GalleryDlLocalResolver.derivePinimgMp4Candidates("https://example.com/x.m3u8").isEmpty())
        assertNotNull(GalleryDlLocalResolver.PINIMG_HLS_REGEX.find("\"url\":\"https://v1.pinimg.com/videos/iht/hls/$hash.m3u8\""))
    }

    @Test
    fun apiVideoPinPrefersPublishedMp4ThenDerivedCandidates() {
        val pin = JSONObject(
            """
            {"id":"368450813290050151","title":"Bolo de cenoura",
             "images":{"orig":{"url":"https://i.pinimg.com/originals/73/e4/34/cover.jpg","width":720,"height":1280}},
             "videos":{"video_list":{
               "V_HLSV4":{"url":"https://v1.pinimg.com/videos/iht/hls/$hash.m3u8","width":1080,"height":1920},
               "V_720P":{"url":"https://v1.pinimg.com/videos/iht/expMp4/${hash}_720w.mp4","width":720,"height":1280}
             }}}
            """.trimIndent(),
        )
        val parsed = GalleryDlLocalResolver.parsePinterestApiPin(pin)!!
        assertEquals("Bolo de cenoura", parsed.title)
        assertEquals(1, parsed.media.size) // cover image is NOT returned for a video Pin
        val video = parsed.media.single() as PinterestApiMedia.Video
        assertEquals("https://v1.pinimg.com/videos/iht/expMp4/${hash}_720w.mp4", video.candidates.first())
        assertTrue(video.candidates.size > 1) // HLS-derived fallbacks follow
        assertEquals(1080, video.width)
    }

    @Test
    fun apiHlsOnlyPinStillYieldsVideoCandidates() {
        val pin = JSONObject(
            """{"id":"1","videos":{"video_list":{"V_HLSV3_MOBILE":{"url":"https://v1.pinimg.com/videos/iht/hls/$hash.m3u8"}}},
               "images":{"orig":{"url":"https://i.pinimg.com/originals/a.jpg"}}}""",
        )
        val video = GalleryDlLocalResolver.parsePinterestApiPin(pin)!!.media.single() as PinterestApiMedia.Video
        assertEquals("https://v1.pinimg.com/videos/iht/expMp4/${hash}_720w.mp4", video.candidates.first())
    }

    @Test
    fun apiIdeaPinKeepsBlockOrderAndCarouselImages() {
        val idea = JSONObject(
            """
            {"id":"2","story_pin_data":{"pages":[
              {"blocks":[{"video":{"video_list":{"V_EXP7":{"url":"https://v1.pinimg.com/videos/mc/expMp4/$hash.mp4","width":720,"height":1280}}}}]},
              {"blocks":[{"image":{"images":{"originals":{"url":"https://i.pinimg.com/originals/p2.png","width":1000,"height":1500}}}}]}
            ]},"images":{"orig":{"url":"https://i.pinimg.com/originals/cover.jpg"}}}
            """.trimIndent(),
        )
        val ideaMedia = GalleryDlLocalResolver.parsePinterestApiPin(idea)!!.media
        assertEquals(2, ideaMedia.size)
        assertTrue(ideaMedia[0] is PinterestApiMedia.Video)
        assertEquals("https://i.pinimg.com/originals/p2.png", (ideaMedia[1] as PinterestApiMedia.Image).url)

        val carousel = JSONObject(
            """
            {"id":"3","carousel_data":{"carousel_slots":[
              {"images":{"736x":{"url":"https://i.pinimg.com/736x/s1.jpg","width":736,"height":900}}},
              {"images":{"orig":{"url":"https://i.pinimg.com/originals/s2.jpg","width":1200,"height":1600}}}
            ]},"images":{"orig":{"url":"https://i.pinimg.com/originals/cover.jpg"}}}
            """.trimIndent(),
        )
        val slots = GalleryDlLocalResolver.parsePinterestApiPin(carousel)!!.media.map { (it as PinterestApiMedia.Image).url }
        assertEquals(listOf("https://i.pinimg.com/736x/s1.jpg", "https://i.pinimg.com/originals/s2.jpg"), slots)

        val image = JSONObject("""{"id":"4","images":{"orig":{"url":"https://i.pinimg.com/originals/only.jpg"}}}""")
        assertEquals("https://i.pinimg.com/originals/only.jpg", (GalleryDlLocalResolver.parsePinterestApiPin(image)!!.media.single() as PinterestApiMedia.Image).url)
        assertNull(GalleryDlLocalResolver.parsePinterestApiPin(JSONObject("""{"id":"5"}""")))
    }

    // ---- Instagram ----------------------------------------------------------------------------

    @Test
    fun shortcodeToMediaIdUsesInstagramBase64Alphabet() {
        assertEquals("0", InstagramParsers.shortcodeToMediaId("A"))
        assertEquals("1", InstagramParsers.shortcodeToMediaId("B"))
        assertEquals("64", InstagramParsers.shortcodeToMediaId("BA"))
        assertEquals("63", InstagramParsers.shortcodeToMediaId("_"))
        // Only the first 11 chars carry the id (longer private-link shortcodes).
        assertEquals(
            InstagramParsers.shortcodeToMediaId("BsOGulcndj-"),
            InstagramParsers.shortcodeToMediaId("BsOGulcndj-XYZabc"),
        )
        assertNull(InstagramParsers.shortcodeToMediaId("bad!code"))
    }

    private val reelsJson = """
        {"reels":{"123":{"user":{"username":"alice"},"items":[
          {"pk":"3001","id":"3001_123","media_type":1,
           "image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/s1_small.jpg","width":320,"height":568},
                                             {"url":"https://scontent.cdninstagram.com/s1_big.jpg","width":1080,"height":1920}]}},
          {"pk":"3002","id":"3002_123","media_type":2,
           "video_versions":[{"url":"https://scontent.cdninstagram.com/s2.mp4","width":720,"height":1280}],
           "image_versions2":{"candidates":[{"url":"https://scontent.cdninstagram.com/s2_cover.jpg","width":720,"height":1280}]}}
        ]}}}
    """.trimIndent()

    @Test
    fun parsesAllStoriesOrASingleStory() {
        val all = InstagramParsers.parseStoryReelsJson(reelsJson, storyId = null, fallbackName = "alice")!!
        assertEquals(2, all.items.size)
        assertEquals("https://scontent.cdninstagram.com/s1_big.jpg", all.items[0].url)
        assertEquals(MediaItemType.VIDEO, all.items[1].type)
        assertEquals("alice", all.author)

        val single = InstagramParsers.parseStoryReelsJson(reelsJson, storyId = "3002", fallbackName = "alice")!!
        assertEquals(1, single.items.size)
        assertEquals("https://scontent.cdninstagram.com/s2.mp4", single.items[0].url)

        assertNull(InstagramParsers.parseStoryReelsJson(reelsJson, storyId = "9999", fallbackName = "alice"))
        assertEquals("123", InstagramParsers.parseWebProfileUserId("""{"data":{"user":{"id":"123","username":"alice"}}}"""))
        assertNull(InstagramParsers.parseWebProfileUserId("""{"data":{"user":null}}"""))
    }

    @Test
    fun storiesRequireSessionAndResolveThroughReelsMediaWithCookies() {
        val anonymous = InstagramNativeResolver(httpClient = SafeHttpClient(allowLoopbackForTesting = true), baseUrl = "http://127.0.0.1:1")
        try {
            anonymous.resolve(ResolutionRequest(url = "https://www.instagram.com/stories/alice/3002/", platform = "instagram"))
            fail("Stories without session must be AUTH_REQUIRED")
        } catch (e: ResolverException) {
            assertEquals(DevwolfErrorCode.AUTH_REQUIRED, e.errorCode)
            assertTrue(e.userMessage.contains("Stories"))
        }

        val server = MockWebServer()
        val seen = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v1/users/web_profile_info/") ->
                        MockResponse().setBody("""{"data":{"user":{"id":"123","username":"alice"}}}""")
                    path.startsWith("/api/v1/feed/reels_media/") -> MockResponse().setBody(reelsJson)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val resolver = InstagramNativeResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            val result = resolver.resolve(
                ResolutionRequest(
                    url = "https://www.instagram.com/stories/alice/3002/",
                    platform = "instagram",
                    cookieHeader = "sessionid=abc; csrftoken=tok",
                ),
            )
            assertEquals("InstagramStoriesApiStrategy", result.strategyUsed)
            assertEquals(1, result.items.size)
            assertEquals("https://scontent.cdninstagram.com/s2.mp4", result.items[0].url)
            val reelsCall = seen.first { it.path.orEmpty().startsWith("/api/v1/feed/reels_media/") }
            assertTrue(reelsCall.path!!.contains("reel_ids=123"))
            assertEquals("tok", reelsCall.getHeader("X-CSRFToken"))
            assertEquals("936619743392459", reelsCall.getHeader("X-IG-App-ID"))
            assertTrue(reelsCall.getHeader("Cookie").orEmpty().contains("sessionid=abc"))
        } finally {
            server.shutdown()
        }
    }

    // ---- UI message contract ------------------------------------------------------------------

    @Test
    fun onlyCuratedMessagesAreFlaggedAsSpecific() {
        assertTrue(DevwolfDownloadService.isSpecificUserMessage("Este story expirou.", "MEDIA_UNAVAILABLE", "instagram"))
        assertFalse(
            DevwolfDownloadService.isSpecificUserMessage(
                DevwolfErrorCode.MEDIA_UNAVAILABLE.defaultMessagePtBr,
                "MEDIA_UNAVAILABLE",
                "instagram",
            ),
        )
        assertFalse(DevwolfDownloadService.isSpecificUserMessage("ERROR: [x] raw", "EXTRACTION_FAILED", "instagram"))
        assertFalse(
            DevwolfDownloadService.isSpecificUserMessage(
                ErrorClassifier.authRequiredMessageForPlatform("reddit"),
                "AUTH_REQUIRED",
                "reddit",
            ),
        )
        assertFalse(DevwolfDownloadService.isSpecificUserMessage(null, "AUTH_REQUIRED", "instagram"))
    }
}
