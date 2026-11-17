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
) : MediaResolver {

    override val engineKind: EngineKind = EngineKind.GALLERY_DL_LOCAL
    override val engineId: String = "gallery-dl"
    override val engineVersion: String = "3.0.0-native"

    override fun supports(url: String, platform: String): Boolean {
        return platform in setOf("pinterest", "reddit", "twitter") && UrlExtractor.isSafePublicHttpUrl(url)
    }

    override fun resolve(request: ResolutionRequest): ResolutionResult {
        val startMs = System.currentTimeMillis()
        val platform = request.platform.ifBlank { UrlExtractor.detectPlatform(request.url) }
        return when (platform) {
            "pinterest" -> resolvePinterest(request, startMs)
            "reddit" -> resolveReddit(request, startMs)
            "twitter" -> resolveTwitterImages(request, startMs)
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
            val parsed = parsePinterestHtml(resp.body, pinId)
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

    private fun resolveReddit(request: ResolutionRequest, startMs: Long): ResolutionResult {
        var totalReqs = 0
        try {
            val normalized = UrlExtractor.normalizeUrl(request.url)
            val headers = mapOf(
                "User-Agent" to "android:com.devwolf.downloader:v3.0.0",
                "Accept" to "application/json",
            )
            val initialTarget = if (redditBaseUrl != null) {
                rewriteHost(normalized, redditBaseUrl)
            } else {
                normalized
            }

            val (finalUrl, hopReqs) = httpClient.resolveFinalUrl(initialTarget, headers, request.cancellationCheck)
            totalReqs += hopReqs

            val jsonEndpoint = buildRedditJsonUrl(finalUrl)
                ?: throw ResolverException(
                    errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                    technicalReason = "URL do Reddit não aponta para um post reconhecido",
                    metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                )

            val resp = httpClient.getText(jsonEndpoint, headers, cancellationCheck = request.cancellationCheck)
            totalReqs += resp.httpRequests

            if (resp.statusCode == 404 || resp.statusCode == 410) {
                throw ResolverException(
                    errorCode = DevwolfErrorCode.MEDIA_UNAVAILABLE,
                    httpStatus = resp.statusCode,
                    metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                )
            }
            if (resp.statusCode == 403) {
                throw ResolverException(
                    errorCode = DevwolfErrorCode.PRIVATE_MEDIA,
                    httpStatus = 403,
                    technicalReason = "Comunidade ou publicação privada no Reddit",
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
            if (resp.statusCode !in 200..299) {
                throw ResolverException(
                    errorCode = ErrorClassifier.fromHttpStatus(resp.statusCode),
                    httpStatus = resp.statusCode,
                    metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                )
            }

            val postId = extractRedditPostId(finalUrl) ?: "reddit"
            val parsed = parseRedditJson(resp.body, postId)
                ?: throw ResolverException(
                    errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                    technicalReason = "Reddit JSON não contém mídia de imagem ou galeria direta",
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
                technicalReason = "Reddit IO: ${e.message}",
                metrics = ResolutionMetrics(System.currentTimeMillis() - startMs, totalReqs),
                cause = e,
            )
        }
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

        fun parsePinterestHtml(html: String, pinId: String): ResolutionResult? {
            if (html.isBlank()) return null
            val doc = Jsoup.parse(html)

            // 1. __PWS_DATA__ or embedded JSON state
            val pwsScript = doc.selectFirst("script#__PWS_DATA__")?.data()?.trim()
            if (!pwsScript.isNullOrBlank()) {
                parsePinterestPwsJson(pwsScript, pinId)?.let { return it }
            }

            // 2. application/ld+json
            for (ld in doc.select("script[type=application/ld+json]")) {
                parsePinterestLdJson(ld.data().trim(), pinId)?.let { return it }
            }

            // 3. OpenGraph meta fallback
            val ogVideo = doc.selectFirst("meta[property=og:video:secure_url], meta[property=og:video]")
                ?.attr("content")?.takeIf { it.startsWith("http") }
            val ogImage = doc.selectFirst("meta[property=og:image]")
                ?.attr("content")?.takeIf { it.startsWith("http") }
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }

            val items = mutableListOf<ResolvedMediaItem>()
            if (ogVideo != null) {
                items.add(
                    ResolvedMediaItem(
                        index = 1,
                        id = "${pinId}_1",
                        type = MediaItemType.VIDEO,
                        url = ogVideo,
                        fileName = "pinterest_${pinId}_01.mp4",
                        mimeType = "video/mp4",
                        headers = mapOf("Referer" to "https://www.pinterest.com/"),
                    ),
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
                strategyUsed = "PinterestOpenGraph",
                items = items,
            )
        }

        private fun parsePinterestPwsJson(jsonText: String, pinId: String): ResolutionResult? {
            return try {
                val root = JSONObject(jsonText)
                val pinNode = findPinterestPinNode(root, 0) ?: return null
                val title = pinNode.optString("title").takeIf { it.isNotBlank() }
                    ?: pinNode.optString("grid_title").takeIf { it.isNotBlank() }
                    ?: pinNode.optString("description").takeIf { it.isNotBlank() }
                val author = pinNode.optJSONObject("pinner")?.optString("username")?.takeIf { it.isNotBlank() }

                val items = mutableListOf<ResolvedMediaItem>()

                // Story / Idea Pin multi-page carousel
                val storyPages = pinNode.optJSONObject("story_pin_data")?.optJSONArray("pages")
                if (storyPages != null && storyPages.length() > 0) {
                    for (i in 0 until storyPages.length()) {
                        val page = storyPages.optJSONObject(i) ?: continue
                        val blocks = page.optJSONArray("blocks") ?: continue
                        for (b in 0 until blocks.length()) {
                            val block = blocks.optJSONObject(b) ?: continue
                            val vidUrl = extractBestPinterestVideo(block.optJSONObject("video"))
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
                }

                // Single video Pin
                if (items.isEmpty()) {
                    val video = extractBestPinterestVideo(pinNode.optJSONObject("videos"))
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
                    }
                }

                // Single image Pin (`images.orig`)
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
                ResolutionResult(
                    platform = "pinterest",
                    shortcode = pinId,
                    title = title?.take(120),
                    author = author,
                    engineUsed = "gallery-dl",
                    engineVersion = "3.0.0-native",
                    strategyUsed = "PinterestPwsJson",
                    items = items,
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun parsePinterestLdJson(raw: String, pinId: String): ResolutionResult? {
            return try {
                val obj = JSONObject(raw)
                val contentUrl = obj.optString("contentUrl").takeIf { it.startsWith("http") }
                val imageUrl = obj.optString("image").takeIf { it.startsWith("http") }
                val mediaUrl = contentUrl ?: imageUrl ?: return null
                val isVideo = obj.optString("@type").equals("VideoObject", ignoreCase = true) ||
                    mediaUrl.lowercase().contains(".mp4")
                val mime = if (isVideo) "video/mp4" else InstagramParsers.guessImageMime(mediaUrl)
                val ext = if (isVideo) "mp4" else if (mime == "image/png") "png" else "jpg"
                ResolutionResult(
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
                            type = if (isVideo) MediaItemType.VIDEO else MediaItemType.IMAGE,
                            url = mediaUrl,
                            fileName = "pinterest_${pinId}_01.$ext",
                            mimeType = mime,
                        ),
                    ),
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun findPinterestPinNode(node: Any?, depth: Int): JSONObject? {
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
                            findPinterestPinNode(child, depth + 1)?.let { return it }
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        findPinterestPinNode(node.opt(i), depth + 1)?.let { return it }
                    }
                }
            }
            return null
        }

        private fun extractBestPinterestVideo(videosObj: JSONObject?): Triple<String, Int?, Int?>? {
            val vList = videosObj?.optJSONObject("video_list") ?: return null
            var bestUrl: String? = null
            var bestW: Int? = null
            var bestH: Int? = null
            var bestArea = -1L
            val keys = vList.keys()
            while (keys.hasNext()) {
                val entry = vList.optJSONObject(keys.next()) ?: continue
                val url = entry.optString("url").takeIf { it.startsWith("http") && !it.endsWith(".m3u8") } ?: continue
                val w = entry.optInt("width").takeIf { it > 0 }
                val h = entry.optInt("height").takeIf { it > 0 }
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

        private fun extractPinterestOrigImage(imagesObj: JSONObject?): Triple<String, Int?, Int?>? {
            if (imagesObj == null) return null
            val orig = imagesObj.optJSONObject("orig") ?: imagesObj.optJSONObject("originals")
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
            val keys = imagesObj.keys()
            while (keys.hasNext()) {
                val cand = imagesObj.optJSONObject(keys.next()) ?: continue
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

        internal fun buildRedditJsonUrl(url: String): String? {
            val uri = try { URI(url) } catch (_: Exception) { return null }
            val path = (uri.path ?: "").trimEnd('/')
            if (path.isBlank() || path == "/") return null
            val jsonPath = if (path.endsWith(".json")) path else "$path.json"
            val port = if (uri.port != -1) ":${uri.port}" else ""
            return "${uri.scheme}://${uri.host}$port$jsonPath?raw_json=1"
        }

        private fun extractPinterestPinId(url: String): String? {
            return Regex("""/pin/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
        }

        private fun extractRedditPostId(url: String): String? {
            return Regex("""/comments/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)
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
