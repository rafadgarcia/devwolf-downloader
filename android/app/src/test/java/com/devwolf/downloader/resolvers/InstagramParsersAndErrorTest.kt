package com.devwolf.downloader.resolvers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramParsersAndErrorTest {

    @Test
    fun parsesInstagramReelFromXdtWebInfoSelectingHighestResolutionVideo() {
        val html = """
            <html><head><title>Instagram</title></head><body>
            <script type="application/json">
            {
              "require": [["ScheduledServerJS", "handle", null, [{
                "__bbox": {
                  "result": {
                    "data": {
                      "xdt_api__v1__media__shortcode__web_info": {
                        "items": [{
                          "pk": "345678901234567890",
                          "code": "C9ReelTest1",
                          "media_type": 2,
                          "original_width": 1080,
                          "original_height": 1920,
                          "video_duration": 18.4,
                          "user": { "username": "devwolf_creator", "is_private": false },
                          "caption": { "text": "Teste de Reel 1080p" },
                          "video_versions": [
                            { "width": 480, "height": 854, "url": "https://scontent.cdninstagram.com/v/low_480.mp4" },
                            { "width": 1080, "height": 1920, "url": "https://scontent.cdninstagram.com/v/hd_1080.mp4" },
                            { "width": 720, "height": 1280, "url": "https://scontent.cdninstagram.com/v/sd_720.mp4" }
                          ]
                        }]
                      }
                    }
                  }
                }
              }]]]
            }
            </script>
            </body></html>
        """.trimIndent()

        val parsed = InstagramParsers.parseWebPageHtml(html, "C9ReelTest1", allowOgImageFallback = false)
        assertNotNull(parsed)
        assertEquals("C9ReelTest1", parsed?.shortcode)
        assertEquals("devwolf_creator", parsed?.author)
        assertEquals("Teste de Reel 1080p", parsed?.title)
        assertFalse(parsed?.isPrivate ?: true)
        assertEquals(1, parsed?.items?.size)

        val item = parsed!!.items.first()
        assertEquals(MediaItemType.VIDEO, item.type)
        assertEquals("https://scontent.cdninstagram.com/v/hd_1080.mp4", item.url)
        assertEquals(1080, item.width)
        assertEquals(1920, item.height)
        assertEquals(18.4, item.durationSeconds ?: 0.0, 0.01)
    }

    @Test
    fun parsesInstagramSinglePhotoFromPolarisMediaJson() {
        val html = """
            <html><body>
            <script type="application/json">
            {
              "xig_polaris_media": {
                "code": "C9PhotoOne1",
                "media_type": 1,
                "user": { "username": "foto_autor", "is_private": false },
                "caption": { "text": "Foto única em alta" },
                "image_versions2": {
                  "candidates": [
                    { "width": 640, "height": 800, "url": "https://scontent.cdninstagram.com/v/photo_640.jpg" },
                    { "width": 1080, "height": 1350, "url": "https://scontent.cdninstagram.com/v/photo_1080.jpg" }
                  ]
                }
              }
            }
            </script>
            </body></html>
        """.trimIndent()

        val parsed = InstagramParsers.parseWebPageHtml(html, "C9PhotoOne1")
        assertNotNull(parsed)
        assertEquals(1, parsed?.items?.size)
        val item = parsed!!.items.first()
        assertEquals(MediaItemType.IMAGE, item.type)
        assertEquals("https://scontent.cdninstagram.com/v/photo_1080.jpg", item.url)
        assertEquals("image/jpeg", item.mimeType)
        assertEquals(1080, item.width)
        assertEquals(1350, item.height)
    }

    @Test
    fun parsesMixedPhotoAndVideoCarouselFromEmbedCaptionedAndWebJsonPreservingOrder() {
        val embedHtml = """
            <html><body>
            <script>
            window.__initEmbed({
              "contextJSON": {
                "gql_data": {
                  "shortcode_media": {
                    "shortcode": "C9MixCar1",
                    "owner": { "username": "carrossel_oficial", "is_private": false },
                    "edge_media_to_caption": { "edges": [{ "node": { "text": "Carrossel misto 3 itens" } }] },
                    "edge_sidecar_to_children": {
                      "edges": [
                        {
                          "node": {
                            "is_video": false,
                            "dimensions": { "width": 1080, "height": 1350 },
                            "display_url": "https://scontent.cdninstagram.com/v/slide1.jpg"
                          }
                        },
                        {
                          "node": {
                            "is_video": true,
                            "dimensions": { "width": 1080, "height": 1350 },
                            "video_duration": 11.2,
                            "video_url": "https://scontent.cdninstagram.com/v/slide2.mp4",
                            "display_url": "https://scontent.cdninstagram.com/v/slide2_thumb.jpg"
                          }
                        },
                        {
                          "node": {
                            "is_video": false,
                            "display_resources": [
                              { "src": "https://scontent.cdninstagram.com/v/slide3_640.jpg", "config_width": 640, "config_height": 640 },
                              { "src": "https://scontent.cdninstagram.com/v/slide3_1080.jpg", "config_width": 1080, "config_height": 1080 }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            });
            </script>
            </body></html>
        """.trimIndent()

        val parsed = InstagramParsers.parseEmbedCaptionedHtml(embedHtml, "C9MixCar1")
        assertNotNull(parsed)
        assertEquals("carrossel_oficial", parsed?.author)
        assertEquals(3, parsed?.items?.size)

        val items = parsed!!.items
        assertEquals(1, items[0].index)
        assertEquals(MediaItemType.IMAGE, items[0].type)
        assertEquals("https://scontent.cdninstagram.com/v/slide1.jpg", items[0].url)
        assertTrue(items[0].fileName.endsWith("_01.jpg"))

        assertEquals(2, items[1].index)
        assertEquals(MediaItemType.VIDEO, items[1].type)
        assertEquals("https://scontent.cdninstagram.com/v/slide2.mp4", items[1].url)
        assertTrue(items[1].fileName.endsWith("_02.mp4"))
        assertEquals(11.2, items[1].durationSeconds ?: 0.0, 0.01)

        assertEquals(3, items[2].index)
        assertEquals(MediaItemType.IMAGE, items[2].type)
        assertEquals("https://scontent.cdninstagram.com/v/slide3_1080.jpg", items[2].url)
        assertTrue(items[2].fileName.endsWith("_03.jpg"))
    }

    @Test
    fun parsesLdJsonAndLegacySharedDataAndAdditionalData() {
        val ldHtml = """
            <html><head>
            <script type="application/ld+json">
            {
              "@type": "VideoObject",
              "headline": "Post via LD-JSON",
              "author": { "alternateName": "@autor_ld" },
              "video": [{ "contentUrl": "https://scontent.cdninstagram.com/v/ld_video.mp4", "width": 720, "height": 1280 }]
            }
            </script>
            </head></html>
        """.trimIndent()
        val ldParsed = InstagramParsers.parseWebPageHtml(ldHtml, "LdCode1")
        assertNotNull(ldParsed)
        assertEquals("autor_ld", ldParsed?.author)
        assertEquals(MediaItemType.VIDEO, ldParsed?.items?.first()?.type)

        val legacyHtml = """
            <html><body><script>
            window._sharedData = {"entry_data":{"PostPage":[{"graphql":{"shortcode_media":{"shortcode":"Leg123","is_video":true,"video_url":"https://scontent.cdninstagram.com/v/legacy.mp4","display_url":"https://scontent.cdninstagram.com/v/legacy.jpg"}}}]}};
            </script></body></html>
        """.trimIndent()
        val legacyParsed = InstagramParsers.parseWebPageHtml(legacyHtml, "Leg123")
        assertNotNull(legacyParsed)
        assertEquals("https://scontent.cdninstagram.com/v/legacy.mp4", legacyParsed?.items?.first()?.url)
    }

    @Test
    fun accuratelyClassifiesStructuredErrorsWithoutMisclassifyingParserMissAsPrivateMedia() {
        // 1. Generic HTML structure change / parser miss -> EXTRACTION_FAILED (NEVER PRIVATE_MEDIA!)
        assertEquals(
            DevwolfErrorCode.EXTRACTION_FAILED,
            InstagramParsers.classifyFailure(
                statusCode = 200,
                finalUrl = "https://www.instagram.com/reel/AbCd123/",
                body = "<html><body><div>New layout without expected JSON keys</div></body></html>",
                hasSessionCookie = false,
            ),
        )

        // 2. Real private account -> PRIVATE_MEDIA (terminal)
        assertEquals(
            DevwolfErrorCode.PRIVATE_MEDIA,
            InstagramParsers.classifyFailure(
                statusCode = 200,
                finalUrl = "https://www.instagram.com/p/Priv123/",
                body = """{"user":{"is_private":true},"message":"This account is private"}""",
                hasSessionCookie = false,
            ),
        )

        // 3. Login wall -> AUTH_REQUIRED (terminal)
        assertEquals(
            DevwolfErrorCode.AUTH_REQUIRED,
            InstagramParsers.classifyFailure(
                statusCode = 200,
                finalUrl = "https://www.instagram.com/accounts/login/?next=/p/Auth123/",
                body = """<html><body>Log in to see photos and videos</body></html>""",
                hasSessionCookie = false,
            ),
        )

        // 4. Rate limit -> RATE_LIMITED (terminal)
        assertEquals(
            DevwolfErrorCode.RATE_LIMITED,
            InstagramParsers.classifyFailure(
                statusCode = 429,
                finalUrl = "https://www.instagram.com/p/Rate123/",
                body = """{"message":"Please wait a few minutes before you try again."}""",
                hasSessionCookie = false,
            ),
        )

        // 5. Challenge / Captcha -> CAPTCHA_REQUIRED (terminal)
        assertEquals(
            DevwolfErrorCode.CAPTCHA_REQUIRED,
            InstagramParsers.classifyFailure(
                statusCode = 400,
                finalUrl = "https://www.instagram.com/challenge/",
                body = """{"message":"checkpoint_required"}""",
                hasSessionCookie = true,
            ),
        )

        // 6. Removed / 404 post -> MEDIA_UNAVAILABLE (terminal)
        assertEquals(
            DevwolfErrorCode.MEDIA_UNAVAILABLE,
            InstagramParsers.classifyFailure(
                statusCode = 404,
                finalUrl = "https://www.instagram.com/p/Gone123/",
                body = """<html><body>Sorry, this page isn't available.</body></html>""",
                hasSessionCookie = false,
            ),
        )
    }

    @Test
    fun ytdlpAgeWarningDoesNotTriggerFalsePrivateMediaOrFatalFailure() {
        val stderr = """
            WARNING: [generic] yt-dlp is more than 90 days old. Please update!
            ERROR: [TikTok] Unable to extract webpage video data; please report this issue
        """.trimIndent()
        val (code, _) = ErrorClassifier.fromYtdlpOutput(stderr)
        assertEquals(DevwolfErrorCode.EXTRACTOR_OUTDATED, code)
        assertFalse(code.isTerminal)

        val fatalOnly = ErrorClassifier.extractFatalErrorLines("WARNING: yt-dlp is more than 90 days old\nERROR: HTTP Error 429: Too Many Requests")
        assertFalse(fatalOnly.contains("90 days"))
        assertTrue(fatalOnly.contains("429"))
        assertNull(InstagramParsers.parseWebPageHtml("<html><body>Empty</body></html>", "None1"))
    }
}
