package com.devwolf.downloader.resolvers

import android.content.Context
import com.devwolf.downloader.UrlExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.URI

data class LocalGalleryCapabilities(
    val engineId: String = "gallery-dl-local",
    val version: String = "3.0.0-native",
    val supportedPlatforms: List<String> = listOf("pinterest", "reddit", "twitter-images"),
    val pythonRuntimePresent: Boolean = false,
)

/** Media extracted from a Pinterest `PinResource` pin (see `GalleryDlLocalResolver.parsePinterestApiPin`). */
internal sealed class PinterestApiMedia {
    /** Ordered MP4 candidates (published MP4 first, then MP4s derived from the HLS URL). */
    data class Video(val candidates: List<String>, val width: Int?, val height: Int?) : PinterestApiMedia()
    data class Image(val url: String, val width: Int?, val height: Int?) : PinterestApiMedia()
}

internal data class PinterestApiParse(val title: String?, val media: List<PinterestApiMedia>)

/** Images (original resolution) and text of a YouTube community post. */
internal data class YouTubePost(val postId: String, val text: String?, val imageUrls: List<String>)

/**
 * Local zero-bloat resolver for image platforms and galleries (Pinterest, Reddit galleries/images,
 * and Twitter/X image fallback), avoiding a duplicate Python runtime while preserving exact
 * ordered media items. For `v.redd.it` DASH audio+video streams, delegates to `yt-dlp`.
 */
class GalleryDlLocalResolver(
    private val httpClient: SafeHttpClient = SafeHttpClient.shared,
    private val pinterestBaseUrl: String? = null,
    private val redditBaseUrl: String? = null,
    private val twitterSyndicationBaseUrl: String = "https://cdn.syndication.twimg.com",
    private val oauthRedditBaseUrl: String? = null,
    private val youtubeBaseUrl: String? = null,
) : MediaResolver {

    override val engineKind: EngineKind = EngineKind.GALLERY_DL_LOCAL
    override val engineId: String = "gallery-dl"
    override val engineVersion: String = "3.0.0-native"

    override fun supports(url: String, platform: String): Boolean {
        return platform in setOf("pinterest", "reddit", "twitter", "youtube") && UrlExtractor.isSafePublicHttpUrl(url)
    }

    override fun resolve(request: ResolutionRequest): ResolutionResult {
        val startMs = System.currentTimeMillis()
        val platform = request.platform.ifBlank { UrlExtractor.detectPlatform(request.url) }
        return when (platform) {
            "pinterest" -> resolvePinterest(request, startMs)
            "reddit" -> resolveReddit(request, startMs)
            "twitter" -> resolveTwitterImages(request, startMs)
            "youtube" -> resolveYouTubeImages(request, startMs)
            else -> throw ResolverException(
                errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                technicalReason = "Plataforma $platform não suportada pelo resolvedor local de imagens",
            )
        }
    }

    private fun resolvePinterest(request: ResolutionRequest, startMs: Long): ResolutionResult {
        var totalReqs = 0
        try {
            val normalized = UrlExtractor.normalizeUrl(request.url)
            val target = if (pinterestBaseUrl != null && !normalized.startsWith(pinterestBaseUrl)) {
                rewriteHost(normalized, pinterestBaseUrl)
            } else {
                normalized
            }

            val headers = mapOf(
                "User-Agent" to SafeHttpClient.DEFAULT_DESKTOP_CHROME_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            )
            val resp = httpClient.getText(target, headers, cancellationCheck = request.cancellationCheck)
            totalReqs += resp.httpRequests

            if (resp.statusCode == 404 || resp.statusCode == 410) {
                throw ResolverException(
                    errorCode = DevwolfErrorCode.MEDIA_UNAVAILABLE,
                    httpStatus = resp.statusCode,
                    metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                )
            }
            if (resp.statusCode == 429) {
                throw ResolverException(
                    errorCode = DevwolfErrorCode.RATE_LIMITED,
                    httpStatus = 429,
                    metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                )
            }

            val pinId = extractPinterestPinId(resp.finalUrl) ?: extractPinterestPinId(normalized) ?: "pin"

            // 1. Public PinResource JSON API: the pin page HTML frequently omits video data
            //    entirely (only og:image), which used to make video Pins download the cover image.
            resolvePinterestViaApi(pinId, request, startMs)?.let { apiResult ->
                return apiResult.copy(
                    metrics = apiResult.metrics.copy(httpRequestCount = apiResult.metrics.httpRequestCount + totalReqs),
                )
            }

            // 2. Embedded page data (legacy path). HLS-only video pages get an MP4 derived from the
            //    pinimg HLS URL before delegating to yt-dlp.
            val parsed = try {
                parsePinterestHtml(resp.body, pinId, request.format)
            } catch (e: ResolverException) {
                if (e.errorCode != DevwolfErrorCode.EXTRACTION_FAILED) throw e
                val hls = PINIMG_HLS_REGEX.find(resp.body.replace("\\/", "/").replace("\\u002F", "/", ignoreCase = true))?.value
                val mp4 = hls?.let { probeFirstPlayableMp4(derivePinimgMp4Candidates(it), request) }
                    ?: throw e
                ResolutionResult(
                    platform = "pinterest",
                    shortcode = pinId,
                    title = null,
                    engineUsed = "gallery-dl",
                    engineVersion = "3.0.0-native",
                    strategyUsed = "PinterestHlsDerivedMp4",
                    items = listOf(pinterestVideoItem(pinId, 1, mp4, null, null)),
                )
            }
                ?: throw ResolverException(
                    errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                    technicalReason = "Pinterest HTML (HTTP ${resp.statusCode}) sem JSON/mídia reconhecida",
                    httpStatus = resp.statusCode,
                    metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                )

            return parsed.copy(
                metrics = ResolutionMetrics(
                    resolutionMs = System.currentTimeMillis() - startMs,
                    httpRequestCount = totalReqs,
                    strategyAttempts = listOf(
                        StrategyAttemptMetric(
                            strategyName = parsed.strategyUsed,
                            durationMs = System.currentTimeMillis() - startMs,
                            httpRequests = totalReqs,
                            success = true,
                        ),
                    ),
                ),
            )
        } catch (e: ResolverException) {
            throw e
        } catch (e: IOException) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.NETWORK_ERROR,
                technicalReason = "Pinterest IO: ${e.message}",
                metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                cause = e,
            )
        }
    }

    /**
     * Resolves a numeric Pin through the public `PinResource` endpoint (same data the Pinterest web
     * app uses, no login). Returns `null` when the API is unavailable so the HTML path can try.
     * Throws EXTRACTION_FAILED when the Pin is a video but no playable MP4 could be found, so the
     * router falls back to yt-dlp instead of returning the cover image.
     */
    private fun resolvePinterestViaApi(pinId: String, request: ResolutionRequest, startMs: Long): ResolutionResult? {
        if (!pinId.all { it.isDigit() } || pinId.length < 6) return null
        val base = (pinterestBaseUrl ?: "https://www.pinterest.com").trimEnd('/')
        val data = """{"options":{"id":"$pinId","field_set_key":"unauth_react_main_pin"},"context":{}}"""
        val apiUrl = "$base/resource/PinResource/get/?source_url=" +
            UrlExtractor.urlEncode("/pin/$pinId/") + "&data=" + UrlExtractor.urlEncode(data)
        val resp = try {
            httpClient.getText(
                apiUrl,
                mapOf(
                    "User-Agent" to SafeHttpClient.DEFAULT_DESKTOP_CHROME_UA,
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Pinterest-PWS-Handler" to "www/pin/[id].js",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                ),
                cancellationCheck = request.cancellationCheck,
            )
        } catch (e: ResolverException) {
            if (e.errorCode == DevwolfErrorCode.CANCELLED) throw e
            return null
        } catch (_: IOException) {
            return null
        }
        // A 404 here is ambiguous (opaque/ad ids, API changes); the page request already
        // classified truly removed Pins, so let the HTML path decide.
        if (resp.statusCode == 429) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.RATE_LIMITED,
                httpStatus = 429,
                metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, resp.httpRequests),
            )
        }
        if (resp.statusCode !in 200..299) return null
        val pinData = try {
            JSONObject(resp.body).optJSONObject("resource_response")?.optJSONObject("data")
        } catch (_: Exception) {
            null
        } ?: return null
        val parsed = parsePinterestApiPin(pinData) ?: return null
        val forceVideo = request.format.equals("video", ignoreCase = true)

        val items = mutableListOf<ResolvedMediaItem>()
        var index = 1
        var unresolvedVideo = false
        for (media in parsed.media) {
            when (media) {
                is PinterestApiMedia.Video -> {
                    val mp4 = probeFirstPlayableMp4(media.candidates, request)
                    if (mp4 == null) {
                        unresolvedVideo = true
                    } else {
                        items += pinterestVideoItem(pinId, index++, mp4, media.width, media.height)
                    }
                }
                is PinterestApiMedia.Image -> {
                    if (!forceVideo || parsed.media.none { it is PinterestApiMedia.Video }) {
                        items += pinterestImageItem(pinId, index++, media.url, media.width, media.height)
                    }
                }
            }
        }
        if (unresolvedVideo && items.none { it.type == MediaItemType.VIDEO }) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                technicalReason = "Pinterest video Pin without reachable MP4 (HLS only); delegating to yt-dlp",
                metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, resp.httpRequests),
            )
        }
        if (items.isEmpty()) return null
        val hasVideo = items.any { it.type == MediaItemType.VIDEO }
        return ResolutionResult(
            platform = "pinterest",
            shortcode = pinId,
            title = parsed.title?.take(120),
            engineUsed = "gallery-dl",
            engineVersion = "3.0.0-native",
            strategyUsed = if (hasVideo) "PinterestApiVideo" else "PinterestApiImage",
            items = items,
            metrics = ResolutionMetrics(
                resolutionMs = System.currentTimeMillis() - startMs,
                httpRequestCount = resp.httpRequests,
            ),
        )
    }

    /**
     * YouTube images (yt-dlp only handles videos):
     * - community posts (`/post/<id>`, `.../community?lb=<id>`): every attached image at original
     *   resolution (`yt3.ggpht.com/...=s0`), in order;
     * - video links with format "image": the highest available thumbnail.
     * The real image type is read from the response so files get the right extension (GIF/WebP/PNG/JPEG).
     */
    private fun resolveYouTubeImages(request: ResolutionRequest, startMs: Long): ResolutionResult {
        var totalReqs = 0
        val headers = mapOf(
            "User-Agent" to SafeHttpClient.DEFAULT_DESKTOP_CHROME_UA,
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            // Consent cookie that only declines optional tracking, so EU consent walls are skipped.
            "Cookie" to "SOCS=CAI",
        )
        fun metrics() = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs)
        try {
            val postId = extractYouTubePostId(request.url)
            val urls: List<String>
            val title: String?
            val shortcode: String
            val strategy: String
            if (postId != null) {
                val base = (youtubeBaseUrl ?: "https://www.youtube.com").trimEnd('/')
                val resp = httpClient.getText("$base/post/$postId", headers, cancellationCheck = request.cancellationCheck)
                totalReqs += resp.httpRequests
                if (resp.statusCode == 404) {
                    throw ResolverException(DevwolfErrorCode.MEDIA_UNAVAILABLE, httpStatus = 404, metrics = metrics())
                }
                if (resp.statusCode == 429) {
                    throw ResolverException(DevwolfErrorCode.RATE_LIMITED, httpStatus = 429, metrics = metrics())
                }
                val post = parseYouTubePost(resp.body, postId)
                    ?: throw ResolverException(
                        errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                        technicalReason = "YouTube post sem ytInitialData/backstagePostRenderer (HTTP ${resp.statusCode})",
                        httpStatus = resp.statusCode,
                        metrics = metrics(),
                    )
                if (post.imageUrls.isEmpty()) {
                    throw ResolverException(
                        errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                        userMessage = "Esta publicação do YouTube não tem imagens para baixar.",
                        technicalReason = "YouTube post without image attachments",
                        metrics = metrics(),
                    )
                }
                urls = post.imageUrls
                title = post.text
                shortcode = postId
                strategy = "YouTubeCommunityPostImages"
            } else {
                val videoId = extractYouTubeVideoId(request.url)
                    ?: throw ResolverException(
                        errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                        userMessage = "Link do YouTube sem vídeo ou publicação reconhecível.",
                        technicalReason = "No YouTube video id or post id",
                        metrics = metrics(),
                    )
                val thumb = youTubeThumbnailCandidates(videoId).firstOrNull { probeImage(it, request) != null }
                    ?: throw ResolverException(
                        errorCode = DevwolfErrorCode.MEDIA_UNAVAILABLE,
                        technicalReason = "No YouTube thumbnail available for $videoId",
                        metrics = metrics(),
                    )
                urls = listOf(thumb)
                title = null
                shortcode = videoId
                strategy = "YouTubeThumbnail"
            }

            val items = urls.mapIndexed { i, url ->
                val index = i + 1
                val mime = probeImage(url, request) ?: InstagramParsers.guessImageMime(url)
                val ext = when (mime) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "jpg"
                }
                ResolvedMediaItem(
                    index = index,
                    id = "${shortcode}_$index",
                    type = MediaItemType.IMAGE,
                    url = url,
                    fileName = "youtube_${shortcode.take(40)}_%02d.$ext".format(index),
                    mimeType = mime,
                    headers = mapOf("Referer" to "https://www.youtube.com/"),
                )
            }
            totalReqs += items.size
            return ResolutionResult(
                platform = "youtube",
                shortcode = shortcode,
                title = title?.take(120),
                engineUsed = "gallery-dl",
                engineVersion = "3.0.0-native",
                strategyUsed = strategy,
                items = items,
                metrics = ResolutionMetrics(
                    resolutionMs = System.currentTimeMillis() - startMs,
                    httpRequestCount = totalReqs,
                    strategyAttempts = listOf(
                        StrategyAttemptMetric(strategy, System.currentTimeMillis() - startMs, totalReqs, true),
                    ),
                ),
            )
        } catch (e: ResolverException) {
            throw e
        } catch (e: IOException) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.NETWORK_ERROR,
                technicalReason = "YouTube IO: ${e.message}",
                metrics = metrics(),
                cause = e,
            )
        }
    }

    /** Returns the `image/...` content type of [url] (1-byte range probe), or null when not an image. */
    private fun probeImage(url: String, request: ResolutionRequest): String? {
        return try {
            httpClient.executeStreamingGet(
                url = url,
                headers = mapOf("User-Agent" to SafeHttpClient.DEFAULT_DESKTOP_CHROME_UA, "Range" to "bytes=0-0"),
                cancellationCheck = request.cancellationCheck,
            ) { response, _, _, _ ->
                val type = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                type.takeIf { response.code in 200..299 && it.startsWith("image/") }
            }
        } catch (e: ResolverException) {
            if (e.errorCode == DevwolfErrorCode.CANCELLED) throw e
            null
        } catch (_: IOException) {
            null
        }
    }

    /** Returns the first candidate that answers 2xx with a `video/...` body (range probe of 1 byte). */
    private fun probeFirstPlayableMp4(candidates: List<String>, request: ResolutionRequest): String? {
        for (url in candidates.distinct()) {
            if (request.cancellationCheck()) throw ResolverException(DevwolfErrorCode.CANCELLED)
            val ok = try {
                httpClient.executeStreamingGet(
                    url = url,
                    headers = mapOf(
                        "User-Agent" to SafeHttpClient.DEFAULT_DESKTOP_CHROME_UA,
                        "Referer" to "https://www.pinterest.com/",
                        "Range" to "bytes=0-0",
                    ),
                    cancellationCheck = request.cancellationCheck,
                ) { response, _, _, _ ->
                    response.code in 200..299 &&
                        response.header("Content-Type").orEmpty().lowercase().startsWith("video/")
                }
            } catch (e: ResolverException) {
                if (e.errorCode == DevwolfErrorCode.CANCELLED) throw e
                false
            } catch (_: IOException) {
                false
            }
            if (ok) return url
        }
        return null
    }

    private fun resolveReddit(request: ResolutionRequest, startMs: Long): ResolutionResult {
        var totalReqs = 0
        try {
            val normalized = UrlExtractor.normalizeUrl(request.url)
            val accessToken = request.accessToken?.trim()?.takeIf { it.isNotEmpty() }
            val cookieHeader = request.cookieHeader?.trim()?.takeIf { it.isNotEmpty() }
            val hasAuth = accessToken != null || cookieHeader != null
            val browserUserAgent = request.userAgent?.takeIf { it.isNotBlank() } ?: SafeHttpClient.DEFAULT_MOBILE_CHROME_UA

            val htmlHeaders = LinkedHashMap<String, String>().apply {
                put("User-Agent", browserUserAgent)
                put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                put("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                if (cookieHeader != null) put("Cookie", cookieHeader)
            }
            val apiHeaders = LinkedHashMap<String, String>().apply {
                if (accessToken != null) {
                    put("Authorization", "Bearer $accessToken")
                    put("User-Agent", buildRedditApiUserAgent(request.username))
                } else {
                    put("User-Agent", browserUserAgent)
                    if (cookieHeader != null) put("Cookie", cookieHeader)
                }
                put("Accept", "application/json, text/plain, */*")
                put("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
            }
            val initialTarget = if (redditBaseUrl != null) {
                rewriteHost(normalized, redditBaseUrl)
            } else {
                normalized
            }

            var resolvedUrl = initialTarget
            if (isRedditShareOrShortUrl(initialTarget)) {
                val (hopUrl, hopReqs) = httpClient.resolveFinalUrl(initialTarget, htmlHeaders, request.cancellationCheck)
                totalReqs += hopReqs
                resolvedUrl = hopUrl

                if (isUnresolvedRedditSharePath(resolvedUrl)) {
                    val shareResp = httpClient.getText(resolvedUrl, htmlHeaders, cancellationCheck = request.cancellationCheck)
                    totalReqs += shareResp.httpRequests
                    if (shareResp.statusCode !in 200..299) {
                        val errCode = classifyRedditHttpError(shareResp.statusCode, shareResp.body, shareResp.contentType, hasAuth)
                        val msg = if (errCode == DevwolfErrorCode.AUTH_REQUIRED) {
                            ErrorClassifier.authRequiredMessageForPlatform("reddit")
                        } else {
                            errCode.defaultMessagePtBr
                        }
                        throw ResolverException(
                            errorCode = errCode,
                            userMessage = msg,
                            httpStatus = shareResp.statusCode,
                            technicalReason = "Reddit share link /s/ HTTP ${shareResp.statusCode} ($errCode)",
                            metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                        )
                    }
                    val canonicalFromHtml = extractRedditCanonicalUrl(shareResp.body)
                    if (canonicalFromHtml != null) {
                        resolvedUrl = if (redditBaseUrl != null) {
                            rewriteHost(canonicalFromHtml, redditBaseUrl)
                        } else {
                            canonicalFromHtml
                        }
                    }
                }

                if (isUnresolvedRedditSharePath(resolvedUrl)) {
                    throw ResolverException(
                        errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                        technicalReason = "Link de compartilhamento /s/ do Reddit não resolveu para /comments/",
                        metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                    )
                }
            }

            val jsonEndpoint = if (accessToken != null) {
                buildRedditOAuthJsonUrl(resolvedUrl)
            } else {
                buildRedditJsonUrl(resolvedUrl)
            } ?: throw ResolverException(
                errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                technicalReason = "URL do Reddit não aponta para um post /comments/ reconhecido",
                metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
            )

            val resp = httpClient.getText(jsonEndpoint, apiHeaders, cancellationCheck = request.cancellationCheck)
            totalReqs += resp.httpRequests

            if (resp.statusCode !in 200..299) {
                val finalErr = classifyRedditHttpError(resp.statusCode, resp.body, resp.contentType, hasAuth)
                val msg = if (finalErr == DevwolfErrorCode.AUTH_REQUIRED) {
                    ErrorClassifier.authRequiredMessageForPlatform("reddit")
                } else {
                    finalErr.defaultMessagePtBr
                }
                throw ResolverException(
                    errorCode = finalErr,
                    userMessage = msg,
                    httpStatus = resp.statusCode,
                    technicalReason = "Reddit HTTP ${resp.statusCode} ($finalErr)",
                    metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                )
            }

            val postId = extractRedditPostId(resolvedUrl) ?: "reddit"
            val parsed = parseRedditJson(resp.body, postId)
                ?: throw ResolverException(
                    errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                    technicalReason = "Reddit JSON não contém mídia de imagem ou galeria direta",
                    metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                )

            val effectiveStrategy = if (accessToken != null) "${parsed.strategyUsed}OAuth" else parsed.strategyUsed
            return parsed.copy(
                strategyUsed = effectiveStrategy,
                metrics = ResolutionMetrics(
                    resolutionMs = System.currentTimeMillis() - startMs,
                    httpRequestCount = totalReqs,
                    strategyAttempts = listOf(
                        StrategyAttemptMetric(
                            strategyName = effectiveStrategy,
                            durationMs = System.currentTimeMillis() - startMs,
                            httpRequests = totalReqs,
                            success = true,
                        ),
                    ),
                ),
            )
        } catch (e: ResolverException) {
            throw e
        } catch (e: IOException) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.NETWORK_ERROR,
                technicalReason = "Reddit IO: ${e.message}",
                metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                cause = e,
            )
        }
    }

    private fun buildRedditOAuthJsonUrl(url: String): String? {
        val baseJson = buildRedditJsonUrl(url) ?: return null
        val oauthBase = oauthRedditBaseUrl ?: redditBaseUrl ?: "https://oauth.reddit.com"
        return rewriteHost(baseJson, oauthBase)
    }

    private fun resolveTwitterImages(request: ResolutionRequest, startMs: Long): ResolutionResult {
        var totalReqs = 0
        try {
            val tweetId = extractTweetId(request.url)
                ?: throw ResolverException(
                    errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                    technicalReason = "ID do post no X/Twitter não encontrado",
                )
            val endpoint = "${twitterSyndicationBaseUrl.trimEnd('/')}/tweet-result?id=$tweetId&lang=en&token=a"
            val headers = mapOf(
                "User-Agent" to SafeHttpClient.DEFAULT_DESKTOP_CHROME_UA,
                "Accept" to "application/json",
            )
            val resp = httpClient.getText(endpoint, headers, cancellationCheck = request.cancellationCheck)
            totalReqs += resp.httpRequests
            if (resp.statusCode == 404 || resp.statusCode == 410) {
                throw ResolverException(DevwolfErrorCode.MEDIA_UNAVAILABLE, httpStatus = resp.statusCode)
            }
            if (resp.statusCode == 429) {
                throw ResolverException(DevwolfErrorCode.RATE_LIMITED, httpStatus = 429)
            }
            val parsed = parseTwitterSyndicationJson(resp.body, tweetId)
                ?: throw ResolverException(
                    errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                    technicalReason = "Resposta X/Twitter sem imagens diretas (HTTP ${resp.statusCode})",
                    httpStatus = resp.statusCode,
                )
            return parsed.copy(
                metrics = ResolutionMetrics(
                    resolutionMs = System.currentTimeMillis() - startMs,
                    httpRequestCount = totalReqs,
                    strategyAttempts = listOf(
                        StrategyAttemptMetric(
                            strategyName = parsed.strategyUsed,
                            durationMs = System.currentTimeMillis() - startMs,
                            httpRequests = totalReqs,
                            success = true,
                        ),
                    ),
                ),
            )
        } catch (e: ResolverException) {
            throw e
        } catch (e: IOException) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.NETWORK_ERROR,
                technicalReason = "Twitter/X IO: ${e.message}",
                metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                cause = e,
            )
        }
    }

    companion object {
        fun inspectCapabilities(context: Context): LocalGalleryCapabilities {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val hasPython = File(nativeDir, "libpython.so").exists() ||
                File(context.noBackupFilesDir, "youtubedl-android/packages/python").exists()
            return LocalGalleryCapabilities(pythonRuntimePresent = hasPython)
        }

        fun parsePinterestHtml(
            html: String,
            pinId: String,
            requestedFormat: String = "auto",
        ): ResolutionResult? {
            if (html.isBlank()) return null
            val doc = Jsoup.parse(html)
            val forceVideo = requestedFormat.equals("video", ignoreCase = true)

            // 1. __PWS_DATA__ / __PWS_INITIAL_PROPS__ / relay JSON state
            val jsonCandidates = LinkedHashSet<String>()
            doc.selectFirst("script#__PWS_DATA__")?.data()?.trim()?.takeIf { it.isNotBlank() }?.let { jsonCandidates.add(it) }
            doc.selectFirst("script#__PWS_INITIAL_PROPS__")?.data()?.trim()?.takeIf { it.isNotBlank() }?.let { jsonCandidates.add(it) }
            for (s in doc.select("script[data-relay-response], script[type=application/json]")) {
                val raw = s.data().trim()
                if (raw.startsWith("{") && (
                        raw.contains("video_list") ||
                            raw.contains("story_pin_data") ||
                            raw.contains("initialReduxState") ||
                            raw.contains("resourceResponses") ||
                            raw.contains("images")
                        )
                ) {
                    jsonCandidates.add(raw)
                }
            }
            for (jsonText in jsonCandidates) {
                parsePinterestPwsJson(jsonText, pinId, forceVideo)?.let { return it }
            }

            // 2. application/ld+json
            for (ld in doc.select("script[type=application/ld+json]")) {
                parsePinterestLdJson(ld.data().trim(), pinId, forceVideo)?.let { return it }
            }

            // 3. Direct HTML / OpenGraph / v.pinimg.com MP4 inspection
            val ogVideo = doc.selectFirst("meta[property=og:video:secure_url], meta[property=og:video], meta[property=og:video:url]")
                ?.attr("content")?.trim()?.takeIf { it.startsWith("http") }
            val domVideo = doc.selectFirst("video[src], video source[src]")
                ?.attr("src")?.trim()?.takeIf { it.startsWith("http") }
            val ogTypeVideo = doc.selectFirst("meta[property=og:type]")
                ?.attr("content")?.contains("video", ignoreCase = true) == true
            val hasPinimgVideoRef = html.contains("v.pinimg.com/videos/", ignoreCase = true) ||
                html.contains("pinimg.com/videos/", ignoreCase = true) ||
                html.contains("v.pinimg.com\\/videos\\/", ignoreCase = true) ||
                html.contains("v.pinimg.com\\u002Fvideos\\u002F", ignoreCase = true)
            val hasStructuredVideoRef = Regex(
                """(?is)(?:"video_list"\s*:|"is_video"\s*:\s*true|"block_type"\s*:\s*3|"@type"\s*:\s*"VideoObject"|"video_status"\s*:\s*"VIDEO_OK"|"videoDataV2"\s*:|"__typename"\s*:\s*"StoryPinVideoBlock"|"blockType"\s*:\s*3|"videoList(?:Mobile|720P|EXP\d+)"\s*:|"v_hlsv\d+_video_list"\s*:)""",
            ).containsMatchIn(html)
            val isVideoPage = forceVideo || ogTypeVideo || ogVideo != null || domVideo != null ||
                hasPinimgVideoRef || hasStructuredVideoRef

            val directVideoUrl = listOfNotNull(ogVideo, domVideo)
                .firstOrNull { isDirectMp4Url(it) }
                ?: extractBestDirectPinimgMp4(html)

            val ogImage = doc.selectFirst("meta[property=og:image]")
                ?.attr("content")?.trim()?.takeIf { it.startsWith("http") }
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }

            val items = mutableListOf<ResolvedMediaItem>()
            if (directVideoUrl != null) {
                items.add(
                    ResolvedMediaItem(
                        index = 1,
                        id = "${pinId}_1",
                        type = MediaItemType.VIDEO,
                        url = directVideoUrl,
                        fileName = "pinterest_${pinId}_01.mp4",
                        mimeType = "video/mp4",
                        headers = mapOf("Referer" to "https://www.pinterest.com/"),
                    ),
                )
            } else if (isVideoPage) {
                // Video Pin detected (HLS .m3u8 or dynamic stream) — never return the cover thumbnail as success!
                throw ResolverException(
                    errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                    technicalReason = "Video Pin requires yt-dlp HLS/video extractor",
                )
            } else if (ogImage != null && !ogImage.contains("pinterest.com/images/")) {
                val mime = InstagramParsers.guessImageMime(ogImage)
                val ext = if (mime == "image/png") "png" else if (mime == "image/webp") "webp" else "jpg"
                items.add(
                    ResolvedMediaItem(
                        index = 1,
                        id = "${pinId}_1",
                        type = MediaItemType.IMAGE,
                        url = ogImage,
                        fileName = "pinterest_${pinId}_01.$ext",
                        mimeType = mime,
                        headers = mapOf("Referer" to "https://www.pinterest.com/"),
                    ),
                )
            }

            if (items.isEmpty()) return null
            return ResolutionResult(
                platform = "pinterest",
                shortcode = pinId,
                title = title?.take(120),
                engineUsed = "gallery-dl",
                engineVersion = "3.0.0-native",
                strategyUsed = if (directVideoUrl != null) "PinterestDirectVideo" else "PinterestOpenGraph",
                items = items,
            )
        }

        private fun parsePinterestPwsJson(
            jsonText: String,
            pinId: String,
            forceVideo: Boolean = false,
        ): ResolutionResult? {
            val root = try {
                JSONObject(jsonText)
            } catch (_: Exception) {
                return null
            }
            val pinNode = findPinterestPinNode(root, pinId, 0) ?: return null
            val title = pinNode.optString("title").takeIf { it.isNotBlank() }
                ?: pinNode.optString("grid_title").takeIf { it.isNotBlank() }
                ?: pinNode.optString("description").takeIf { it.isNotBlank() }
            val author = pinNode.optJSONObject("pinner")?.optString("username")?.takeIf { it.isNotBlank() }

            val items = mutableListOf<ResolvedMediaItem>()
            var missingVideoStream = false

            // Story / Idea Pin multi-page carousel
            val storyPages = pinNode.optJSONObject("story_pin_data")?.optJSONArray("pages")
            if (storyPages != null && storyPages.length() > 0) {
                for (i in 0 until storyPages.length()) {
                    val page = storyPages.optJSONObject(i) ?: continue
                    val blocks = page.optJSONArray("blocks") ?: continue
                    for (b in 0 until blocks.length()) {
                        val block = blocks.optJSONObject(b) ?: continue
                        val blockVideo = block.optJSONObject("video")
                        val isVideoBlock = blockVideo != null ||
                            block.optInt("block_type", -1) == 3 ||
                            block.optString("type").equals("story_pin_video_block", ignoreCase = true)
                        if (isVideoBlock) {
                            val vidUrl = extractBestPinterestVideo(blockVideo)
                                ?: extractBestDirectPinimgMp4(block.toString())?.let { Triple(it, null, null) }
                            if (vidUrl != null) {
                                items.add(
                                    ResolvedMediaItem(
                                        index = items.size + 1,
                                        id = "${pinId}_${items.size + 1}",
                                        type = MediaItemType.VIDEO,
                                        url = vidUrl.first,
                                        fileName = "pinterest_${pinId}_${(items.size + 1).toString().padStart(2, '0')}.mp4",
                                        mimeType = "video/mp4",
                                        width = vidUrl.second,
                                        height = vidUrl.third,
                                    ),
                                )
                            } else {
                                missingVideoStream = true
                            }
                        } else {
                            val img = extractPinterestOrigImage(block.optJSONObject("image"))
                            if (img != null) {
                                val mime = InstagramParsers.guessImageMime(img.first)
                                val ext = if (mime == "image/png") "png" else if (mime == "image/webp") "webp" else "jpg"
                                items.add(
                                    ResolvedMediaItem(
                                        index = items.size + 1,
                                        id = "${pinId}_${items.size + 1}",
                                        type = MediaItemType.IMAGE,
                                        url = img.first,
                                        fileName = "pinterest_${pinId}_${(items.size + 1).toString().padStart(2, '0')}.$ext",
                                        mimeType = mime,
                                        width = img.second,
                                        height = img.third,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (missingVideoStream) {
                    throw ResolverException(
                        errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                        technicalReason = "Video Pin requires yt-dlp HLS/video extractor",
                    )
                }
            }

            // Single video Pin
            val videosObj = pinNode.optJSONObject("videos")
            val hasVideoList = (videosObj?.optJSONObject("video_list")?.length() ?: 0) > 0
            val pinNodeRaw = pinNode.toString()
            val isVideoPin = forceVideo ||
                pinNode.optBoolean("is_video", false) ||
                hasVideoList ||
                (videosObj != null && videosObj.length() > 0) ||
                pinNodeRaw.contains("v.pinimg.com/videos/", ignoreCase = true)

            if (items.isEmpty()) {
                val video = extractBestPinterestVideo(videosObj)
                    ?: extractBestDirectPinimgMp4(pinNodeRaw)?.let { Triple(it, null, null) }
                if (video != null) {
                    items.add(
                        ResolvedMediaItem(
                            index = 1,
                            id = "${pinId}_1",
                            type = MediaItemType.VIDEO,
                            url = video.first,
                            fileName = "pinterest_${pinId}_01.mp4",
                            mimeType = "video/mp4",
                            width = video.second,
                            height = video.third,
                        ),
                    )
                } else if (isVideoPin) {
                    // Video Pin detected (e.g. HLS .m3u8 only) — delegate to yt-dlp instead of returning thumbnail
                    throw ResolverException(
                        errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                        technicalReason = "Video Pin requires yt-dlp HLS/video extractor",
                    )
                }
            }

            // Single image Pin (`images.orig`) — only for genuine photo Pins
            if (items.isEmpty()) {
                val img = extractPinterestOrigImage(pinNode.optJSONObject("images"))
                if (img != null) {
                    val mime = InstagramParsers.guessImageMime(img.first)
                    val ext = if (mime == "image/png") "png" else if (mime == "image/webp") "webp" else "jpg"
                    items.add(
                        ResolvedMediaItem(
                            index = 1,
                            id = "${pinId}_1",
                            type = MediaItemType.IMAGE,
                            url = img.first,
                            fileName = "pinterest_${pinId}_01.$ext",
                            mimeType = mime,
                            width = img.second,
                            height = img.third,
                        ),
                    )
                }
            }

            if (items.isEmpty()) return null
            return ResolutionResult(
                platform = "pinterest",
                shortcode = pinId,
                title = title?.take(120),
                author = author,
                engineUsed = "gallery-dl",
                engineVersion = "3.0.0-native",
                strategyUsed = "PinterestPwsJson",
                items = items,
            )
        }

        private fun parsePinterestLdJson(
            raw: String,
            pinId: String,
            forceVideo: Boolean = false,
        ): ResolutionResult? {
            if (raw.isBlank()) return null
            val obj: JSONObject = try {
                val trimmed = raw.trim()
                if (trimmed.startsWith("[")) {
                    JSONArray(trimmed).optJSONObject(0) ?: return null
                } else {
                    JSONObject(trimmed)
                }
            } catch (_: Exception) {
                return null
            }

            val ldType = obj.optString("@type")
            val nestedVideoUrl = obj.optJSONObject("video")?.optString("contentUrl")?.takeIf { it.startsWith("http") }
            val contentUrl = nestedVideoUrl ?: obj.optString("contentUrl").takeIf { it.startsWith("http") }
            val imageUrl = obj.optString("image").takeIf { it.startsWith("http") }
            val isVideoObject = ldType.equals("VideoObject", ignoreCase = true) ||
                obj.has("video") ||
                (contentUrl != null && isDirectMp4Url(contentUrl)) ||
                forceVideo

            if (isVideoObject) {
                if (contentUrl != null && isDirectMp4Url(contentUrl)) {
                    return ResolutionResult(
                        platform = "pinterest",
                        shortcode = pinId,
                        title = obj.optString("name").takeIf { it.isNotBlank() }?.take(120),
                        engineUsed = "gallery-dl",
                        engineVersion = "3.0.0-native",
                        strategyUsed = "PinterestLdJson",
                        items = listOf(
                            ResolvedMediaItem(
                                index = 1,
                                id = "${pinId}_1",
                                type = MediaItemType.VIDEO,
                                url = contentUrl,
                                fileName = "pinterest_${pinId}_01.mp4",
                                mimeType = "video/mp4",
                            ),
                        ),
                    )
                }
                throw ResolverException(
                    errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                    technicalReason = "Video Pin requires yt-dlp HLS/video extractor",
                )
            }

            val mediaUrl = contentUrl ?: imageUrl ?: return null
            val mime = InstagramParsers.guessImageMime(mediaUrl)
            val ext = if (mime == "image/png") "png" else if (mime == "image/webp") "webp" else "jpg"
            return ResolutionResult(
                platform = "pinterest",
                shortcode = pinId,
                title = obj.optString("name").takeIf { it.isNotBlank() }?.take(120),
                engineUsed = "gallery-dl",
                engineVersion = "3.0.0-native",
                strategyUsed = "PinterestLdJson",
                items = listOf(
                    ResolvedMediaItem(
                        index = 1,
                        id = "${pinId}_1",
                        type = MediaItemType.IMAGE,
                        url = mediaUrl,
                        fileName = "pinterest_${pinId}_01.$ext",
                        mimeType = mime,
                    ),
                ),
            )
        }

        private fun findPinterestPinNode(node: Any?, pinId: String, depth: Int): JSONObject? {
            val exact = if (pinId != "pin") findPinterestPinNodeExact(node, pinId, depth) else null
            return exact ?: findPinterestPinNodeAny(node, depth)
        }

        private fun findPinterestPinNodeExact(node: Any?, pinId: String, depth: Int): JSONObject? {
            if (node == null || depth > 14) return null
            when (node) {
                is JSONObject -> {
                    val keyed = node.optJSONObject(pinId)
                    if (keyed != null && (keyed.has("images") || keyed.has("videos") || keyed.has("story_pin_data"))) {
                        return keyed
                    }
                    if (node.optString("id") == pinId &&
                        (node.has("images") || node.has("videos") || node.has("story_pin_data"))
                    ) {
                        return node
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val child = node.opt(keys.next())
                        if (child is JSONObject || child is JSONArray) {
                            findPinterestPinNodeExact(child, pinId, depth + 1)?.let { return it }
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        findPinterestPinNodeExact(node.opt(i), pinId, depth + 1)?.let { return it }
                    }
                }
            }
            return null
        }

        private fun findPinterestPinNodeAny(node: Any?, depth: Int): JSONObject? {
            if (node == null || depth > 14) return null
            when (node) {
                is JSONObject -> {
                    if ((node.has("images") || node.has("videos") || node.has("story_pin_data")) &&
                        (node.has("id") || node.has("grid_title") || node.has("pinner"))
                    ) {
                        return node
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val child = node.opt(keys.next())
                        if (child is JSONObject || child is JSONArray) {
                            findPinterestPinNodeAny(child, depth + 1)?.let { return it }
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        findPinterestPinNodeAny(node.opt(i), depth + 1)?.let { return it }
                    }
                }
            }
            return null
        }

        private fun isDirectMp4Url(url: String): Boolean {
            if (!url.startsWith("http", ignoreCase = true)) return false
            val path = try {
                URI(url).path.orEmpty().lowercase()
            } catch (_: Exception) {
                url.substringBefore('?').lowercase()
            }
            if (path.endsWith(".m3u8") || path.endsWith(".ts")) return false
            return path.endsWith(".mp4")
        }

        private fun extractBestDirectPinimgMp4(rawText: String): String? {
            if (rawText.isBlank()) return null
            val unescaped = rawText
                .replace("\\/", "/")
                .replace("\\u002F", "/", ignoreCase = true)
            val matches = Regex("""https://v\d*\.pinimg\.com/videos/[^\s"'<>\\]+\.mp4(?:\?[^\s"'<>\\]+)?""", RegexOption.IGNORE_CASE)
                .findAll(unescaped)
                .map { it.value }
                .distinct()
                .toList()
            if (matches.isEmpty()) return null
            return matches.maxByOrNull { url ->
                val lower = url.lowercase()
                when {
                    lower.contains("1080p") || lower.contains("1080w") -> 1080
                    lower.contains("720p") || lower.contains("720w") -> 720
                    lower.contains("540p") || lower.contains("540w") -> 540
                    lower.contains("480p") || lower.contains("480w") -> 480
                    lower.contains("360p") || lower.contains("360w") -> 360
                    else -> 240
                }
            }
        }

        // ---- YouTube images (unit-tested pure helpers) ---------------------------------------

        private val YT_POST_PATH_REGEX = Regex("""/post/([A-Za-z0-9_-]{10,})""")
        private val YT_LB_PARAM_REGEX = Regex("""[?&]lb=([A-Za-z0-9_-]{10,})""")
        private val YT_VIDEO_ID_REGEX = Regex(
            """(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/|/v/)([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])""",
        )

        /** Community post id from `/post/<id>` or `.../community?lb=<id>` URLs. */
        fun extractYouTubePostId(url: String): String? {
            YT_POST_PATH_REGEX.find(url)?.let { return it.groupValues[1] }
            if (url.contains("/community", ignoreCase = true) || url.contains("/posts", ignoreCase = true)) {
                YT_LB_PARAM_REGEX.find(url)?.let { return it.groupValues[1] }
            }
            return null
        }

        fun isYouTubeCommunityPost(url: String): Boolean = extractYouTubePostId(url) != null

        fun extractYouTubeVideoId(url: String): String? = YT_VIDEO_ID_REGEX.find(url)?.groupValues?.get(1)

        fun youTubeThumbnailCandidates(videoId: String): List<String> = listOf(
            "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/sddefault.jpg",
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        )

        /** `yt3.ggpht.com/<key>=s1080-c-...` → `=s0` (original size and format, including GIF). */
        fun toOriginalGgphtUrl(url: String): String {
            val clean = url.trim().let { if (it.startsWith("//")) "https:$it" else it }
            if (!clean.contains("ggpht.com/", ignoreCase = true) && !clean.contains("googleusercontent.com/", ignoreCase = true)) {
                return clean
            }
            val eq = clean.lastIndexOf('=')
            val slash = clean.lastIndexOf('/')
            return if (eq > slash) clean.substring(0, eq) + "=s0" else "$clean=s0"
        }

        private fun extractJsonAssignment(html: String, marker: String): JSONObject? {
            val start = html.indexOf(marker)
            if (start < 0) return null
            val braceStart = html.indexOf('{', start + marker.length)
            if (braceStart < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in braceStart until html.length) {
                val c = html[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return try { JSONObject(html.substring(braceStart, i + 1)) } catch (_: Exception) { null }
                        }
                    }
                }
            }
            return null
        }

        private fun findBackstagePost(node: Any?, postId: String, depth: Int = 0): JSONObject? {
            if (node == null || depth > 60) return null
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("backstagePostRenderer")?.let { r ->
                        if (r.optString("postId") == postId) return r
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) findBackstagePost(node.opt(keys.next()), postId, depth + 1)?.let { return it }
                }
                is JSONArray -> for (i in 0 until node.length()) findBackstagePost(node.opt(i), postId, depth + 1)?.let { return it }
            }
            return null
        }

        private fun largestThumbnail(imageRenderer: JSONObject?): String? {
            val thumbs = imageRenderer?.optJSONObject("image")?.optJSONArray("thumbnails") ?: return null
            var best: String? = null
            var bestArea = -1L
            for (i in 0 until thumbs.length()) {
                val t = thumbs.optJSONObject(i) ?: continue
                val url = t.optString("url").takeIf { it.isNotBlank() } ?: continue
                val area = t.optLong("width") * t.optLong("height")
                if (area >= bestArea) {
                    bestArea = area
                    best = url
                }
            }
            return best
        }

        /** Parses `ytInitialData` of `/post/<id>` into ordered original-resolution image URLs. */
        internal fun parseYouTubePost(html: String, postId: String): YouTubePost? {
            val data = extractJsonAssignment(html, "var ytInitialData =")
                ?: extractJsonAssignment(html, "window[\"ytInitialData\"] =")
                ?: extractJsonAssignment(html, "ytInitialData")
                ?: return null
            val post = findBackstagePost(data, postId) ?: return null
            val text = post.optJSONObject("contentText")?.optJSONArray("runs")?.let { runs ->
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }?.trim()?.takeIf { it.isNotBlank() }
            val attachment = post.optJSONObject("backstageAttachment")
            val images = mutableListOf<String>()
            largestThumbnail(attachment?.optJSONObject("backstageImageRenderer"))?.let { images += it }
            attachment?.optJSONObject("postMultiImageRenderer")?.optJSONArray("images")?.let { arr ->
                for (i in 0 until arr.length()) {
                    largestThumbnail(arr.optJSONObject(i)?.optJSONObject("backstageImageRenderer"))?.let { images += it }
                }
            }
            return YouTubePost(postId, text, images.map { toOriginalGgphtUrl(it) }.distinct())
        }

        // ---- Pinterest PinResource API (unit-tested pure helpers) ---------------------------

        internal val PINIMG_HLS_REGEX = Regex(
            """https://v\d*\.pinimg\.com/videos/[A-Za-z0-9/_-]+/hls/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{32}[^"'\s<>\\]*\.m3u8""",
            RegexOption.IGNORE_CASE,
        )
        private val PINIMG_HASH_PATH_REGEX = Regex("""/([0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{32})""", RegexOption.IGNORE_CASE)

        /**
         * Pinterest serves the same video as MP4 under one of several paths depending on when it
         * was encoded; the HLS URL exposes the hash used by all of them.
         */
        internal fun derivePinimgMp4Candidates(hlsUrl: String): List<String> {
            val hashPath = PINIMG_HASH_PATH_REGEX.find(hlsUrl)?.groupValues?.get(1) ?: return emptyList()
            val host = Regex("""^https://(v\d*\.pinimg\.com)/""", RegexOption.IGNORE_CASE).find(hlsUrl)?.groupValues?.get(1)
                ?: "v1.pinimg.com"
            return listOf(
                "https://$host/videos/iht/expMp4/${hashPath}_720w.mp4",
                "https://$host/videos/mc/720p/$hashPath.mp4",
                "https://$host/videos/iht/720p/$hashPath.mp4",
                "https://$host/videos/mc/expMp4/${hashPath}_t4.mp4",
            )
        }

        private fun videoCandidatesFromList(videoList: JSONObject?): PinterestApiMedia.Video? {
            if (videoList == null || videoList.length() == 0) return null
            val mp4s = mutableListOf<Triple<String, Int?, Int?>>()
            var hls: String? = null
            var w: Int? = null
            var h: Int? = null
            val keys = videoList.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = videoList.optJSONObject(key) ?: continue
                val url = entry.optString("url")
                if (url.isBlank()) continue
                val ew = entry.optInt("width").takeIf { it > 0 }
                val eh = entry.optInt("height").takeIf { it > 0 }
                if (isDirectMp4Url(url)) {
                    mp4s += Triple(url, ew, eh)
                } else if (url.contains(".m3u8", ignoreCase = true) && hls == null) {
                    hls = url
                }
                if (w == null || (ew != null && ew > w)) {
                    w = ew
                    h = eh
                }
            }
            val ordered = mp4s.sortedByDescending { (it.second ?: 0) * (it.third ?: 0) }.map { it.first } +
                (hls?.let { derivePinimgMp4Candidates(it) } ?: emptyList())
            if (ordered.isEmpty()) return null
            return PinterestApiMedia.Video(ordered, w, h)
        }

        private fun bestImageFrom(images: JSONObject?): PinterestApiMedia.Image? {
            if (images == null) return null
            for (key in listOf("orig", "originals")) {
                images.optJSONObject(key)?.let { o ->
                    val url = o.optString("url")
                    if (url.startsWith("http")) {
                        return PinterestApiMedia.Image(url, o.optInt("width").takeIf { it > 0 }, o.optInt("height").takeIf { it > 0 })
                    }
                }
            }
            var best: PinterestApiMedia.Image? = null
            var bestArea = -1L
            val keys = images.keys()
            while (keys.hasNext()) {
                val o = images.optJSONObject(keys.next()) ?: continue
                val url = o.optString("url").takeIf { it.startsWith("http") } ?: continue
                val w = o.optInt("width")
                val hgt = o.optInt("height")
                val area = w.toLong() * hgt.toLong()
                if (area > bestArea) {
                    bestArea = area
                    best = PinterestApiMedia.Image(url, w.takeIf { it > 0 }, hgt.takeIf { it > 0 })
                }
            }
            return best
        }

        /**
         * Extracts ordered media from a `PinResource` pin: regular video Pins (`videos.video_list`),
         * Idea/Story Pins (`story_pin_data.pages[].blocks[]` with video or image blocks), image
         * carousels (`carousel_data.carousel_slots[]`) and finally the original image.
         */
        internal fun parsePinterestApiPin(pin: JSONObject): PinterestApiParse? {
            val title = listOf("title", "grid_title", "seo_title")
                .map { pin.optString(it) }
                .firstOrNull { it.isNotBlank() && it != "null" }
            val media = mutableListOf<PinterestApiMedia>()

            videoCandidatesFromList(pin.optJSONObject("videos")?.optJSONObject("video_list"))?.let { media += it }

            if (media.isEmpty()) {
                val pages = pin.optJSONObject("story_pin_data")?.optJSONArray("pages")
                if (pages != null) {
                    for (p in 0 until pages.length()) {
                        val blocks = pages.optJSONObject(p)?.optJSONArray("blocks") ?: continue
                        for (b in 0 until blocks.length()) {
                            val block = blocks.optJSONObject(b) ?: continue
                            val video = videoCandidatesFromList(block.optJSONObject("video")?.optJSONObject("video_list"))
                            if (video != null) {
                                media += video
                                continue
                            }
                            bestImageFrom(block.optJSONObject("image")?.optJSONObject("images"))?.let { media += it }
                        }
                    }
                }
            }

            if (media.isEmpty()) {
                val slots = pin.optJSONObject("carousel_data")?.optJSONArray("carousel_slots")
                if (slots != null) {
                    for (i in 0 until slots.length()) {
                        bestImageFrom(slots.optJSONObject(i)?.optJSONObject("images"))?.let { media += it }
                    }
                }
            }

            if (media.isEmpty()) {
                bestImageFrom(pin.optJSONObject("images"))?.let { media += it }
            }
            if (media.isEmpty()) return null
            return PinterestApiParse(title, media)
        }

        internal fun pinterestVideoItem(pinId: String, index: Int, url: String, width: Int?, height: Int?) =
            ResolvedMediaItem(
                index = index,
                id = "${pinId}_$index",
                type = MediaItemType.VIDEO,
                url = url,
                fileName = "pinterest_${pinId}_%02d.mp4".format(index),
                mimeType = "video/mp4",
                width = width,
                height = height,
                headers = mapOf("Referer" to "https://www.pinterest.com/"),
            )

        internal fun pinterestImageItem(pinId: String, index: Int, url: String, width: Int?, height: Int?): ResolvedMediaItem {
            val mime = InstagramParsers.guessImageMime(url)
            val ext = when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            return ResolvedMediaItem(
                index = index,
                id = "${pinId}_$index",
                type = MediaItemType.IMAGE,
                url = url,
                fileName = "pinterest_${pinId}_%02d.$ext".format(index),
                mimeType = mime,
                width = width,
                height = height,
                headers = mapOf("Referer" to "https://www.pinterest.com/"),
            )
        }

        private fun extractBestPinterestVideo(videosObj: JSONObject?): Triple<String, Int?, Int?>? {
            val vList = videosObj?.optJSONObject("video_list") ?: return null
            var bestUrl: String? = null
            var bestW: Int? = null
            var bestH: Int? = null
            var bestScore = -1L
            val keys = vList.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = vList.optJSONObject(key) ?: continue
                val url = entry.optString("url").takeIf { isDirectMp4Url(it) } ?: continue
                val w = entry.optInt("width").takeIf { it > 0 }
                val h = entry.optInt("height").takeIf { it > 0 }
                val area = if (w != null && h != null) w.toLong() * h.toLong() else 0L
                val keyBoost = when {
                    key.contains("1080", ignoreCase = true) -> 4L
                    key.contains("720", ignoreCase = true) -> 3L
                    key.contains("EXP7", ignoreCase = true) -> 2L
                    else -> 1L
                }
                val score = if (area > 0L) area * 10L + keyBoost else keyBoost
                if (bestUrl == null || score > bestScore) {
                    bestUrl = url
                    bestW = w
                    bestH = h
                    bestScore = score
                }
            }
            return bestUrl?.let { Triple(it, bestW, bestH) }
        }

        private fun extractPinterestOrigImage(imagesObj: JSONObject?): Triple<String, Int?, Int?>? {
            if (imagesObj == null) return null
            val targetObj = imagesObj.optJSONObject("images") ?: imagesObj
            val orig = targetObj.optJSONObject("orig") ?: targetObj.optJSONObject("originals")
            if (orig != null) {
                val url = orig.optString("url").takeIf { it.startsWith("http") }
                if (url != null) {
                    return Triple(url, orig.optInt("width").takeIf { it > 0 }, orig.optInt("height").takeIf { it > 0 })
                }
            }
            // Fallback to largest candidate
            var bestUrl: String? = null
            var bestW: Int? = null
            var bestH: Int? = null
            var bestArea = -1L
            val keys = targetObj.keys()
            while (keys.hasNext()) {
                val cand = targetObj.optJSONObject(keys.next()) ?: continue
                val url = cand.optString("url").takeIf { it.startsWith("http") } ?: continue
                val w = cand.optInt("width").takeIf { it > 0 }
                val h = cand.optInt("height").takeIf { it > 0 }
                val area = if (w != null && h != null) w.toLong() * h.toLong() else 0L
                if (bestUrl == null || area > bestArea) {
                    bestUrl = url
                    bestW = w
                    bestH = h
                    bestArea = area
                }
            }
            return bestUrl?.let { Triple(it, bestW, bestH) }
        }

        /**
         * Parses Reddit's `[listing, comments]` JSON array for galleries, images, and silent GIFs.
         * Throws [ResolverException(EXTRACTION_FAILED)] for `v.redd.it` video+audio posts so
         * [EngineRouter] delegates them to `yt-dlp` for proper DASH audio+video merging.
         */
        fun parseRedditJson(jsonText: String, postId: String): ResolutionResult? {
            if (jsonText.isBlank()) return null
            val postObj: JSONObject = try {
                val trimmed = jsonText.trim()
                if (trimmed.startsWith("[")) {
                    JSONArray(trimmed).optJSONObject(0)
                        ?.optJSONObject("data")
                        ?.optJSONArray("children")
                        ?.optJSONObject(0)
                        ?.optJSONObject("data")
                } else {
                    JSONObject(trimmed).optJSONObject("data")
                        ?.optJSONArray("children")
                        ?.optJSONObject(0)
                        ?.optJSONObject("data")
                }
            } catch (_: Exception) {
                null
            } ?: return null

            val targetPost = postObj.optJSONArray("crosspost_parent_list")?.optJSONObject(0) ?: postObj
            val title = targetPost.optString("title").takeIf { it.isNotBlank() }?.take(120)
            val author = targetPost.optString("author").takeIf { it.isNotBlank() }
            val cleanId = targetPost.optString("id").takeIf { it.isNotBlank() } ?: postId

            // 1. Reddit Gallery (`is_gallery` + `gallery_data.items` + `media_metadata`)
            if (targetPost.optBoolean("is_gallery", false) || targetPost.has("gallery_data")) {
                val galleryItems = targetPost.optJSONObject("gallery_data")?.optJSONArray("items")
                val metadata = targetPost.optJSONObject("media_metadata")
                if (galleryItems != null && metadata != null) {
                    val items = mutableListOf<ResolvedMediaItem>()
                    for (i in 0 until galleryItems.length()) {
                        val mediaId = galleryItems.optJSONObject(i)?.optString("media_id") ?: continue
                        val meta = metadata.optJSONObject(mediaId) ?: continue
                        if (meta.optString("status") == "failed") continue
                        val s = meta.optJSONObject("s") ?: continue
                        val mp4Url = s.optString("mp4").takeIf { it.startsWith("http") }?.replace("&amp;", "&")
                        val imgUrl = s.optString("u").takeIf { it.startsWith("http") }?.replace("&amp;", "&")
                            ?: s.optString("gif").takeIf { it.startsWith("http") }?.replace("&amp;", "&")
                        val w = s.optInt("x").takeIf { it > 0 }
                        val h = s.optInt("y").takeIf { it > 0 }
                        val idx = items.size + 1
                        if (mp4Url != null) {
                            items.add(
                                ResolvedMediaItem(
                                    index = idx,
                                    id = "${cleanId}_$idx",
                                    type = MediaItemType.VIDEO,
                                    url = mp4Url,
                                    fileName = "reddit_${cleanId}_${idx.toString().padStart(2, '0')}.mp4",
                                    mimeType = "video/mp4",
                                    width = w,
                                    height = h,
                                ),
                            )
                        } else if (imgUrl != null) {
                            val mime = InstagramParsers.guessImageMime(imgUrl)
                            val ext = if (mime == "image/png") "png" else if (mime == "image/webp") "webp" else "jpg"
                            items.add(
                                ResolvedMediaItem(
                                    index = idx,
                                    id = "${cleanId}_$idx",
                                    type = MediaItemType.IMAGE,
                                    url = imgUrl,
                                    fileName = "reddit_${cleanId}_${idx.toString().padStart(2, '0')}.$ext",
                                    mimeType = mime,
                                    width = w,
                                    height = h,
                                ),
                            )
                        }
                    }
                    if (items.isNotEmpty()) {
                        return ResolutionResult(
                            platform = "reddit",
                            shortcode = cleanId,
                            title = title,
                            author = author,
                            engineUsed = "gallery-dl",
                            engineVersion = "3.0.0-native",
                            strategyUsed = "RedditGalleryJson",
                            items = items,
                        )
                    }
                }
            }

            // 2. Reddit Video (`v.redd.it`): only handle silent `is_gif` directly; audio+video DASH goes to yt-dlp
            val redditVideo = targetPost.optJSONObject("secure_media")?.optJSONObject("reddit_video")
                ?: targetPost.optJSONObject("media")?.optJSONObject("reddit_video")
            if (redditVideo != null) {
                val isGif = redditVideo.optBoolean("is_gif", false)
                val fallbackUrl = redditVideo.optString("fallback_url").takeIf { it.startsWith("http") }
                if (isGif && fallbackUrl != null) {
                    return ResolutionResult(
                        platform = "reddit",
                        shortcode = cleanId,
                        title = title,
                        author = author,
                        engineUsed = "gallery-dl",
                        engineVersion = "3.0.0-native",
                        strategyUsed = "RedditGifVideo",
                        items = listOf(
                            ResolvedMediaItem(
                                index = 1,
                                id = "${cleanId}_1",
                                type = MediaItemType.VIDEO,
                                url = fallbackUrl,
                                fileName = "reddit_${cleanId}_01.mp4",
                                mimeType = "video/mp4",
                                width = redditVideo.optInt("width").takeIf { it > 0 },
                                height = redditVideo.optInt("height").takeIf { it > 0 },
                                durationSeconds = redditVideo.optDouble("duration").takeIf { !it.isNaN() && it > 0.0 },
                            ),
                        ),
                    )
                }
                // Let EngineRouter fall back to yt-dlp so audio+video DASH streams are merged cleanly
                throw ResolverException(
                    errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                    technicalReason = "Vídeo Reddit (v.redd.it) requer mesclagem DASH áudio+vídeo via yt-dlp",
                )
            }

            // 3. Direct image post (`i.redd.it` or `preview.images[0].source`)
            val destUrl = targetPost.optString("url_overridden_by_dest").ifBlank { targetPost.optString("url") }
            val isDirectImage = destUrl.startsWith("http") &&
                Regex("""\.(jpe?g|png|webp|gif)(\?.*)?$""", RegexOption.IGNORE_CASE).containsMatchIn(destUrl)

            val previewSource = targetPost.optJSONObject("preview")
                ?.optJSONArray("images")
                ?.optJSONObject(0)
                ?.optJSONObject("source")

            val finalImgUrl = when {
                isDirectImage -> destUrl
                previewSource != null -> previewSource.optString("url").replace("&amp;", "&").takeIf { it.startsWith("http") }
                else -> null
            } ?: return null

            val mime = InstagramParsers.guessImageMime(finalImgUrl)
            val ext = if (mime == "image/png") "png" else if (mime == "image/webp") "webp" else "jpg"
            return ResolutionResult(
                platform = "reddit",
                shortcode = cleanId,
                title = title,
                author = author,
                engineUsed = "gallery-dl",
                engineVersion = "3.0.0-native",
                strategyUsed = "RedditImageJson",
                items = listOf(
                    ResolvedMediaItem(
                        index = 1,
                        id = "${cleanId}_1",
                        type = MediaItemType.IMAGE,
                        url = finalImgUrl,
                        fileName = "reddit_${cleanId}_01.$ext",
                        mimeType = mime,
                        width = previewSource?.optInt("width")?.takeIf { it > 0 },
                        height = previewSource?.optInt("height")?.takeIf { it > 0 },
                    ),
                ),
            )
        }

        fun parseTwitterSyndicationJson(jsonText: String, tweetId: String): ResolutionResult? {
            if (jsonText.isBlank()) return null
            return try {
                val root = JSONObject(jsonText)
                val title = root.optString("text").takeIf { it.isNotBlank() }?.take(120)
                val author = root.optJSONObject("user")?.optString("screen_name")?.takeIf { it.isNotBlank() }
                val items = mutableListOf<ResolvedMediaItem>()

                val photos = root.optJSONArray("photos")
                if (photos != null) {
                    for (i in 0 until photos.length()) {
                        val p = photos.optJSONObject(i) ?: continue
                        val url = p.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val mime = InstagramParsers.guessImageMime(url)
                        val ext = if (mime == "image/png") "png" else "jpg"
                        val idx = items.size + 1
                        items.add(
                            ResolvedMediaItem(
                                index = idx,
                                id = "${tweetId}_$idx",
                                type = MediaItemType.IMAGE,
                                url = url,
                                fileName = "x_${tweetId}_${idx.toString().padStart(2, '0')}.$ext",
                                mimeType = mime,
                                width = p.optInt("width").takeIf { it > 0 },
                                height = p.optInt("height").takeIf { it > 0 },
                            ),
                        )
                    }
                }

                if (items.isEmpty()) {
                    val details = root.optJSONArray("mediaDetails")
                    if (details != null) {
                        for (i in 0 until details.length()) {
                            val d = details.optJSONObject(i) ?: continue
                            if (d.optString("type") == "photo") {
                                val url = d.optString("media_url_https").takeIf { it.startsWith("http") } ?: continue
                                val idx = items.size + 1
                                items.add(
                                    ResolvedMediaItem(
                                        index = idx,
                                        id = "${tweetId}_$idx",
                                        type = MediaItemType.IMAGE,
                                        url = url,
                                        fileName = "x_${tweetId}_${idx.toString().padStart(2, '0')}.jpg",
                                        mimeType = "image/jpeg",
                                    ),
                                )
                            }
                        }
                    }
                }

                if (items.isEmpty()) return null
                ResolutionResult(
                    platform = "twitter",
                    shortcode = tweetId,
                    title = title,
                    author = author,
                    engineUsed = "gallery-dl",
                    engineVersion = "3.0.0-native",
                    strategyUsed = "TwitterSyndicationImages",
                    items = items,
                )
            } catch (_: Exception) {
                null
            }
        }

        fun buildRedditApiUserAgent(username: String?): String {
            val cleanUser = username?.trim()?.removePrefix("/u/")?.removePrefix("u/")?.takeIf { it.isNotBlank() }
            return if (cleanUser != null) {
                "android:com.devwolf.downloader:v3.0.0-rc.11 (by /u/$cleanUser)"
            } else {
                "android:com.devwolf.downloader:v3.0.0-rc.11"
            }
        }

        fun classifyRedditHttpError(
            statusCode: Int,
            body: String,
            contentType: String?,
            hasAuth: Boolean = false,
        ): DevwolfErrorCode {
            if (statusCode == 429) return DevwolfErrorCode.RATE_LIMITED
            if (statusCode == 404 || statusCode == 410) return DevwolfErrorCode.MEDIA_UNAVAILABLE
            if (statusCode == 401) return DevwolfErrorCode.AUTH_REQUIRED

            if (statusCode == 403) {
                val trimmed = body.trim()
                val lowerBody = trimmed.lowercase()
                val isJson = contentType?.contains("json", ignoreCase = true) == true || trimmed.startsWith("{")
                if (isJson) {
                    try {
                        val json = JSONObject(trimmed)
                        val reason = json.optString("reason").trim().lowercase()
                        val subredditType = json.optString("subreddit_type").trim().lowercase()
                        val message = json.optString("message").trim().lowercase()
                        val isPrivate = json.optBoolean("is_private", false)
                        if (reason == "private" || subredditType == "private" || isPrivate) {
                            return DevwolfErrorCode.PRIVATE_MEDIA
                        }
                        if (reason == "quarantined" || reason == "login_required" || message == "unauthorized") {
                            return DevwolfErrorCode.AUTH_REQUIRED
                        }
                    } catch (_: Exception) {
                    }
                }

                if (Regex(""""reason"\s*:\s*"private"""").containsMatchIn(lowerBody) ||
                    Regex(""""subreddit_type"\s*:\s*"private"""").containsMatchIn(lowerBody) ||
                    Regex(""""is_private"\s*:\s*true""").containsMatchIn(lowerBody) ||
                    lowerBody.contains("this community is private") ||
                    lowerBody.contains("you must be invited to visit this community")
                ) {
                    return DevwolfErrorCode.PRIVATE_MEDIA
                }

                if (Regex(""""reason"\s*:\s*"quarantined"""").containsMatchIn(lowerBody) ||
                    lowerBody.contains("login_required")
                ) {
                    return DevwolfErrorCode.AUTH_REQUIRED
                }

                // When no Reddit session/OAuth is present, explicit 403 blocks prompt the user
                // to connect their Reddit account instead of cycling alternate hosts.
                return if (!hasAuth) DevwolfErrorCode.AUTH_REQUIRED else DevwolfErrorCode.EXTRACTION_FAILED
            }

            if (statusCode in 500..599) return DevwolfErrorCode.NETWORK_ERROR
            return ErrorClassifier.fromHttpStatus(statusCode, body)
        }

        internal fun isRedditShareOrShortUrl(url: String): Boolean {
            val uri = try { URI(url) } catch (_: Exception) { return false }
            val host = uri.host?.lowercase()?.trimEnd('.') ?: ""
            val path = uri.path.orEmpty()
            if (host == "redd.it" || host.endsWith(".redd.it")) return true
            return isUnresolvedRedditSharePath(url) || (!path.contains("/comments/") && !path.contains("/gallery/"))
        }

        internal fun isUnresolvedRedditSharePath(url: String): Boolean {
            val path = try { URI(url).path.orEmpty() } catch (_: Exception) { return false }
            if (path.contains("/comments/") || path.contains("/gallery/")) return false
            return Regex("""(?:^|/)s/[A-Za-z0-9_-]+""").containsMatchIn(path)
        }

        internal fun extractRedditCanonicalUrl(html: String): String? {
            if (html.isBlank()) return null
            val doc = Jsoup.parse(html)
            val candidates = listOfNotNull(
                doc.selectFirst("link[rel=canonical]")?.attr("href")?.trim(),
                doc.selectFirst("meta[property=og:url]")?.attr("content")?.trim(),
            )
            for (cand in candidates) {
                if (cand.startsWith("http", ignoreCase = true) &&
                    !isUnresolvedRedditSharePath(cand) &&
                    (cand.contains("/comments/") || cand.contains("/gallery/"))
                ) {
                    return UrlExtractor.normalizeUrl(cand)
                }
            }
            return null
        }

        internal fun buildRedditJsonUrl(url: String): String? {
            val uri = try { URI(url) } catch (_: Exception) { return null }
            val path = (uri.path ?: "").trimEnd('/')
            if (path.isBlank() || path == "/") return null
            if (isUnresolvedRedditSharePath(url)) return null
            val jsonPath = if (path.endsWith(".json")) path else "$path.json"
            val port = if (uri.port != -1) ":${uri.port}" else ""
            return "${uri.scheme}://${uri.host}$port$jsonPath?raw_json=1"
        }

        private fun extractPinterestPinId(url: String): String? {
            return Regex("""/pin/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
        }

        private fun extractRedditPostId(url: String): String? {
            return Regex("""/(?:comments|gallery)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
        }

        private fun extractTweetId(url: String): String? {
            return Regex("""/status/(\d+)""").find(url)?.groupValues?.get(1)
        }

        private fun rewriteHost(url: String, overrideBase: String): String {
            val u = URI(url)
            val base = overrideBase.trimEnd('/')
            val path = u.rawPath.ifBlank { "/" }
            val query = u.rawQuery?.let { "?$it" } ?: ""
            return "$base$path$query"
        }
    }
}
