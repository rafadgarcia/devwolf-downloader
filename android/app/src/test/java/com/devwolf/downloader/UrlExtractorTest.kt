package com.devwolf.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

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
    fun normalizesTrackingParamsWhilePreservingFunctionalQueryParams() {
        assertEquals(
            "https://www.instagram.com/reel/C8x123AbCd/",
            UrlExtractor.normalizeUrl("https://www.instagram.com/reel/C8x123AbCd/?igsh=abc123&utm_source=ig_web_copy_link"),
        )
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s",
            UrlExtractor.normalizeUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ&si=xyz999&t=42s&utm_medium=share"),
        )
    }

    @Test
    fun strictlyParsesInstagramUrlVariantsAndRejectsImpostors() {
        val reel = UrlExtractor.parseInstagramUrl("https://www.instagram.com/reels/Cy99AbCdEf_/?igsh=123")
        assertNotNull(reel)
        assertEquals(InstagramUrlKind.REEL, reel?.kind)
        assertEquals("Cy99AbCdEf_", reel?.shortcode)
        assertEquals("https://www.instagram.com/reel/Cy99AbCdEf_/", reel?.canonicalUrl)

        val post = UrlExtractor.parseInstagramUrl("https://instagram.com/p/C7zCarousel1/")
        assertNotNull(post)
        assertEquals(InstagramUrlKind.POST, post?.kind)
        assertEquals("C7zCarousel1", post?.shortcode)

        val tv = UrlExtractor.parseInstagramUrl("https://m.instagram.com/tv/B9TvCode99/")
        assertNotNull(tv)
        assertEquals(InstagramUrlKind.TV, tv?.kind)
        assertEquals("B9TvCode99", tv?.shortcode)

        val share = UrlExtractor.parseInstagramUrl("https://www.instagram.com/share/reel/BAL123abc/")
        assertNotNull(share)
        assertEquals(InstagramUrlKind.SHARE, share?.kind)

        assertNull(UrlExtractor.parseInstagramUrl("https://instagram.com.evil.com/reel/C8x123AbCd/"))
        assertNull(UrlExtractor.parseInstagramUrl("https://evilinstagram.com/p/C8x123AbCd/"))
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
    fun rejectsSsrfAndUnsafeUrlsAndPrivateIpRanges() {
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://127.0.0.1/admin"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://169.254.169.254/latest/meta-data/"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://[::1]:8000/"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://2130706433/"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://0x7f000001/"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://user:pass@instagram.com/reel/123"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("http://localhost:8080/test"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("file:///etc/passwd"))
        assertFalse(UrlExtractor.isSafePublicHttpUrl("https://instagram.com:8443/reel/123"))
        assertNull(UrlExtractor.extractFirstValidUrl("Acesse http://192.168.1.1/login agora"))
        assertTrue(UrlExtractor.isSafePublicHttpUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))

        assertFalse(UrlExtractor.isPublicRoutableAddress(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))))
        assertFalse(UrlExtractor.isPublicRoutableAddress(InetAddress.getByAddress(byteArrayOf(172.toByte(), 16, 0, 1))))
        assertFalse(UrlExtractor.isPublicRoutableAddress(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))))
        assertFalse(UrlExtractor.isPublicRoutableAddress(InetAddress.getByAddress(byteArrayOf(100, 64, 0, 1))))
        assertFalse(UrlExtractor.isPublicRoutableAddress(InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))))
        assertTrue(UrlExtractor.isPublicRoutableAddress(InetAddress.getByAddress(byteArrayOf(157.toByte(), 240.toByte(), 1, 35))))
    }
}
