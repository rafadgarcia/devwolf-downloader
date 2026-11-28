package com.devwolf.downloader.resolvers

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YouTubeImagesTest {

    private val postId = "UgkxT0Mc6a678mZ4nNjaxdB9WpTWuj09btZe"

    private fun thumbs(key: String) = """
        {"thumbnails":[
          {"url":"https://yt3.ggpht.com/$key=s288-c-fcrop64=1,00000000ffffffff-rw-nd-v1","width":288,"height":288},
          {"url":"https://yt3.ggpht.com/$key=s1080-c-fcrop64=1,00000000ffffffff-rw-nd-v1","width":1080,"height":1080}
        ]}
    """.trimIndent()

    private fun postHtml(attachment: String) = """
        <html><head><script nonce="x">var ytInitialData = {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{
          "sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"backstagePostThreadRenderer":{"post":{
            "backstagePostRenderer":{"postId":"$postId",
              "contentText":{"runs":[{"text":"culture in motion "},{"text":"@eric"}]},
              "backstageAttachment":$attachment}}}}]}}]}}}}]}}};</script></head><body></body></html>
    """.trimIndent()

    @Test
    fun extractsPostAndVideoIdsFromAllUrlShapes() {
        assertEquals(postId, GalleryDlLocalResolver.extractYouTubePostId("https://www.youtube.com/post/$postId"))
        assertEquals(postId, GalleryDlLocalResolver.extractYouTubePostId("https://www.youtube.com/@YouTube/community?lb=$postId"))
        assertEquals(postId, GalleryDlLocalResolver.extractYouTubePostId("https://youtube.com/channel/UCx/posts?lb=$postId&si=abc"))
        assertNull(GalleryDlLocalResolver.extractYouTubePostId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&lb=$postId"))
        assertTrue(GalleryDlLocalResolver.isYouTubeCommunityPost("https://m.youtube.com/post/$postId"))

        assertEquals("dQw4w9WgXcQ", GalleryDlLocalResolver.extractYouTubeVideoId("https://www.youtube.com/watch?t=4&v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", GalleryDlLocalResolver.extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=x"))
        assertEquals("abcdefghijk", GalleryDlLocalResolver.extractYouTubeVideoId("https://youtube.com/shorts/abcdefghijk"))
        assertNull(GalleryDlLocalResolver.extractYouTubeVideoId("https://www.youtube.com/@canal"))
    }

    @Test
    fun rewritesGgphtImagesToOriginalSize() {
        assertEquals(
            "https://yt3.ggpht.com/abc=s0",
            GalleryDlLocalResolver.toOriginalGgphtUrl("https://yt3.ggpht.com/abc=s1080-c-fcrop64=1,00000000ffffffff-rw-nd-v1"),
        )
        assertEquals("https://yt3.ggpht.com/abc=s0", GalleryDlLocalResolver.toOriginalGgphtUrl("//yt3.ggpht.com/abc=s420-nd-v1-rwa"))
        assertEquals("https://i.ytimg.com/vi/x/hq.jpg", GalleryDlLocalResolver.toOriginalGgphtUrl("https://i.ytimg.com/vi/x/hq.jpg"))
    }

    @Test
    fun parsesMultiAndSingleImagePosts() {
        val multi = GalleryDlLocalResolver.parseYouTubePost(
            postHtml("""{"postMultiImageRenderer":{"images":[{"backstageImageRenderer":{"image":${thumbs("one")}}},{"backstageImageRenderer":{"image":${thumbs("two")}}}]}}"""),
            postId,
        )!!
        assertEquals(listOf("https://yt3.ggpht.com/one=s0", "https://yt3.ggpht.com/two=s0"), multi.imageUrls)
        assertEquals("culture in motion @eric", multi.text)

        val single = GalleryDlLocalResolver.parseYouTubePost(
            postHtml("""{"backstageImageRenderer":{"image":${thumbs("solo")}}}"""),
            postId,
        )!!
        assertEquals(listOf("https://yt3.ggpht.com/solo=s0"), single.imageUrls)

        val textOnly = GalleryDlLocalResolver.parseYouTubePost(postHtml("""{"pollRenderer":{}}"""), postId)!!
        assertTrue(textOnly.imageUrls.isEmpty())
        assertNull(GalleryDlLocalResolver.parseYouTubePost(postHtml("{}"), "OtherPostId123"))
        assertNull(GalleryDlLocalResolver.parseYouTubePost("<html></html>", postId))
    }

    @Test
    fun routesPostsAndImageFormatToLocalResolverOnly() {
        val router = EngineRouter()
        val post = router.route("https://www.youtube.com/post/$postId")
        assertEquals(EngineKind.GALLERY_DL_LOCAL, post.primaryEngine)
        assertTrue(post.fallbackEngines.isEmpty())
        assertFalse(post.shouldInitializeYtdlpUpfront)

        val thumb = router.route("https://www.youtube.com/watch?v=dQw4w9WgXcQ", format = "image")
        assertEquals(EngineKind.GALLERY_DL_LOCAL, thumb.primaryEngine)

        val video = router.route("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals(EngineKind.YT_DLP, video.primaryEngine)
    }

    @Test
    fun textOnlyPostFailsWithClearMessage() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path.orEmpty().startsWith("/post/")) {
                    MockResponse().setBody(postHtml("""{"pollRenderer":{}}"""))
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
        server.start()
        try {
            val resolver = GalleryDlLocalResolver(
                httpClient = SafeHttpClient(allowLoopbackForTesting = true),
                youtubeBaseUrl = server.url("/").toString().trimEnd('/'),
            )
            try {
                resolver.resolve(ResolutionRequest(url = "https://www.youtube.com/post/$postId", platform = "youtube"))
                fail("Text-only post must not succeed")
            } catch (e: ResolverException) {
                assertEquals(DevwolfErrorCode.UNSUPPORTED_MEDIA, e.errorCode)
                assertTrue(e.userMessage.contains("não tem imagens"))
            }
            assertEquals("SOCS=CAI", server.takeRequest().getHeader("Cookie"))
        } finally {
            server.shutdown()
        }
    }
}
