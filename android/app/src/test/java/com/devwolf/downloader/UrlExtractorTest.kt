package com.devwolf.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlExtractorTest {

    @Test
    fun extractsInstagramAndFacebookUrlsFromNoisyShareText() {
        val igText = "Olha esse Reel incrível! https://www.instagram.com/reel/C8x123AbCd/?igsh=abc123 confira!"
        assertEquals(
            "https://www.instagram.com/reel/C8x123AbCd/?igsh=abc123",
            UrlExtractor.extractFirstValidUrl(igText),
        )
        assertEquals("instagram", UrlExtractor.detectPlatform("https://www.instagram.com/reel/C8x123AbCd/"))

        val fbShare = "Veja no Facebook: https://www.facebook.com/share/r/1234567890/."
        assertEquals(
            "https://www.facebook.com/share/r/1234567890/",
            UrlExtractor.extractFirstValidUrl(fbShare),
        )
        assertEquals("facebook", UrlExtractor.detectPlatform("https://www.facebook.com/share/r/1234567890/"))
        assertEquals("facebook", UrlExtractor.detectPlatform("https://fb.watch/abc123/"))
    }

    @Test
    fun classifiesAllSupportedPlatforms() {
        assertEquals("tiktok", UrlExtractor.detectPlatform("https://vm.tiktok.com/ZM6abc123/"))
        assertEquals("youtube", UrlExtractor.detectPlatform("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("twitter", UrlExtractor.detectPlatform("https://x.com/user/status/12345"))
        assertEquals("reddit", UrlExtractor.detectPlatform("https://redd.it/xyz123"))
        assertEquals("pinterest", UrlExtractor.detectPlatform("https://pin.it/abc999"))
        assertEquals("unknown", UrlExtractor.detectPlatform("https://example.com/video.mp4"))
    }

    @Test
    fun rejectsSsrfAndUnsafeUrls() {
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://127.0.0.1/admin"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://[::1]:8000/"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://user:pass@instagram.com/reel/123"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://localhost:8080/test"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("file:///etc/passwd"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("https://instagram.com:8443/reel/123"))
        assertNull(UrlExtractor.extractFirstValidUrl("Acesse http://192.168.1.1/login agora"))
        assertTrue(UrlExtractor.isSafePublicHttpUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }
}
