package com.devwolf.downloader.resolvers

import com.devwolf.downloader.ParsedThreadsUrl
import com.devwolf.downloader.ThreadsUrlKind
import com.devwolf.downloader.UrlExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI

data class ThreadsParsedMedia(
    val shortcode: String?,
    val title: String? = null,
    val author: String? = null,
    val items: List<ResolvedMediaItem>,
    val isPrivate: Boolean = false,
)

/**
 * Devwolf Native Resolver (DNR) for Meta Threads (`threads.com` and `threads.net`).
 * Independent from [InstagramNativeResolver] while sharing [SafeHttpClient] and structured errors.
 * Supports single videos, single photos, multi-media carousels, and optional Threads session cookies.
 */
class ThreadsNativeResolver(
    private val httpClient: SafeHttpClient = SafeHttpClient.shared,
    private val baseUrl: String? = null,
) : MediaResolver {

    override val engineKind: EngineKind = EngineKind.DNR
    override val engineId: String = "dnr"
    override val engineVersion: String = "3.0.0"

    override fun supports(url: String, platform: String): Boolean {
        return platform == "threads" && UrlExtractor.parseThreadsUrl(url) != null
    }

    override fun resolve(request: ResolutionRequest): ResolutionResult {
        val overallStartMs = System.currentTimeMillis()
        val attempts = mutableListOf<StrategyAttemptMetric>()
        var totalHttpRequests = 0

        fun buildMetrics(): ResolutionMetrics = ResolutionMetrics(
            resolutionMs = System.currentTimeMillis() - overallStartMs,
            httpRequestCount = totalHttpRequests,
            strategyAttempts = attempts.toList(),
        )

        val parsed = UrlExtractor.parseThreadsUrl(request.url)
            ?: throw ResolverException(
                errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                technicalReason = "URL do Threads fora do padrão suportado (/@user/post/{code}, /t/{code}, /post/{code})",
                metrics = buildMetrics(),
            )

        val shortcode = parsed.shortcode ?: "threads"
        val hasSession = !request.cookieHeader.isNullOrBlank()
        val targetUrl = rewriteBaseUrl(parsed.canonicalUrl, parsed.canonicalPath)
        var sawAuthWall = false
        var sawNetworkError = false
        var lastTechnicalReason: String? = null
        var lastHttpStatus: Int? = null

        fun runStrategy(
            strategyName: String,
            isMainPage: Boolean,
            block: () -> Pair<HttpTextResponse, ThreadsParsedMedia?>,
        ): ResolutionResult? {
            val start = System.currentTimeMillis()
            var reqs = 0
            try {
                val (resp, media) = block()
                reqs = resp.httpRequests
                totalHttpRequests += reqs
                lastHttpStatus = resp.statusCode

                if (media?.isPrivate == true) {
                    val code = DevwolfErrorCode.PRIVATE_MEDIA
                    attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, false, code, "is_private=true"))
                    throw ResolverException(code, strategyUsed = strategyName, httpStatus = resp.statusCode, metrics = buildMetrics())
                }

                val hasAcceptable = media != null && media.items.isNotEmpty() &&
                    (!request.format.equals("video", ignoreCase = true) || media.items.any { it.type == MediaItemType.VIDEO })

                if (hasAcceptable) {
                    attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, true))
                    return ResolutionResult(
                        platform = "threads",
                        shortcode = media!!.shortcode ?: shortcode,
                        title = media.title,
                        author = media.author ?: parsed.username,
                        engineUsed = engineId,
                        engineVersion = engineVersion,
                        strategyUsed = strategyName,
                        items = media.items,
                        metrics = buildMetrics(),
                    )
                }

                val errCode = classifyFailure(resp.statusCode, resp.finalUrl, resp.body)
                val immediateTerminal = errCode == DevwolfErrorCode.RATE_LIMITED ||
                    errCode == DevwolfErrorCode.CAPTCHA_REQUIRED ||
                    errCode == DevwolfErrorCode.PRIVATE_MEDIA ||
                    (isMainPage && errCode == DevwolfErrorCode.MEDIA_UNAVAILABLE)

                if (immediateTerminal) {
                    attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, false, errCode, "HTTP ${resp.statusCode}"))
                    throw ResolverException(
                        errorCode = errCode,
                        strategyUsed = strategyName,
                        httpStatus = resp.statusCode,
                        metrics = buildMetrics(),
                    )
                }

                if (errCode == DevwolfErrorCode.AUTH_REQUIRED && isMainPage) {
                    sawAuthWall = true
                }
                if (errCode == DevwolfErrorCode.NETWORK_ERROR) {
                    sawNetworkError = true
                }
                lastTechnicalReason = "$strategyName: HTTP ${resp.statusCode} ($errCode)"
                attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, false, errCode, lastTechnicalReason))
            } catch (e: ResolverException) {
                if (e.errorCode == DevwolfErrorCode.CANCELLED ||
                    e.errorCode == DevwolfErrorCode.PRIVATE_MEDIA ||
                    e.errorCode == DevwolfErrorCode.RATE_LIMITED ||
                    e.errorCode == DevwolfErrorCode.CAPTCHA_REQUIRED ||
                    (isMainPage && e.errorCode == DevwolfErrorCode.MEDIA_UNAVAILABLE)
                ) {
                    throw e
                }
                if (e.errorCode == DevwolfErrorCode.NETWORK_ERROR) {
                    sawNetworkError = true
                }
                if (e.errorCode == DevwolfErrorCode.AUTH_REQUIRED && isMainPage) {
                    sawAuthWall = true
                }
                attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, false, e.errorCode, e.technicalReason))
                lastTechnicalReason = e.technicalReason
            } catch (e: IOException) {
                sawNetworkError = true
                lastTechnicalReason = "$strategyName IO: ${e.message}"
                attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, false, DevwolfErrorCode.NETWORK_ERROR, lastTechnicalReason))
            }
            return null
        }

        // Strategy 1: Direct web page HTML / data-sjs JSON (with optional Threads session cookie)
        runStrategy(strategyName = "ThreadsWebPageJsonStrategy", isMainPage = true) {
            val headers = LinkedHashMap<String, String>().apply {
                put("User-Agent", request.userAgent?.takeIf { it.isNotBlank() } ?: SafeHttpClient.DEFAULT_MOBILE_CHROME_UA)
                put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                put("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
                put("Sec-Fetch-Mode", "navigate")
                if (hasSession) {
                    put("Cookie", request.cookieHeader!!.trim())
                }
            }
            val resp = httpClient.getText(targetUrl, headers, cancellationCheck = request.cancellationCheck)
            val allowOgImg = !request.format.equals("video", ignoreCase = true)
            resp to parseThreadsHtml(resp.body, shortcode, allowOgImg)
        }?.let { return it }

        // Strategy 2: Anonymous embed / desktop SSR fallback
        if (parsed.kind == ThreadsUrlKind.POST || !hasSession) {
            runStrategy(strategyName = "ThreadsEmbedMetaStrategy", isMainPage = false) {
                val embedUrl = if (parsed.kind == ThreadsUrlKind.POST) {
                    "${targetUrl.trimEnd('/')}/embed"
                } else {
                    targetUrl
                }
                val headers = mapOf(
                    "User-Agent" to SafeHttpClient.DEFAULT_DESKTOP_CHROME_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "en-US,en;q=0.9,pt-BR;q=0.8",
                    "Referer" to "https://www.threads.com/",
                )
                val resp = httpClient.getText(embedUrl, headers, cancellationCheck = request.cancellationCheck)
                val allowOgImg = !request.format.equals("video", ignoreCase = true)
                resp to parseThreadsHtml(resp.body, shortcode, allowOgImg)
            }?.let { return it }
        }

        val finalErrorCode = when {
            sawAuthWall -> DevwolfErrorCode.AUTH_REQUIRED
            sawNetworkError -> DevwolfErrorCode.NETWORK_ERROR
            else -> DevwolfErrorCode.EXTRACTION_FAILED
        }
        val finalMessage = if (finalErrorCode == DevwolfErrorCode.AUTH_REQUIRED) {
            ErrorClassifier.authRequiredMessageForPlatform("threads")
        } else {
            finalErrorCode.defaultMessagePtBr
        }
        throw ResolverException(
            errorCode = finalErrorCode,
            userMessage = finalMessage,
            technicalReason = SafeHttpClient.sanitizeForDiagnostics(lastTechnicalReason),
            strategyUsed = attempts.lastOrNull()?.strategyName,
            httpStatus = lastHttpStatus,
            metrics = buildMetrics(),
        )
    }

    private fun rewriteBaseUrl(canonicalUrl: String, canonicalPath: String): String {
        val base = baseUrl?.trimEnd('/') ?: return canonicalUrl
        return "$base$canonicalPath"
    }

    companion object {
        fun parseThreadsHtml(
            html: String,
            shortcode: String,
            allowOgImageFallback: Boolean = true,
        ): ThreadsParsedMedia? {
            if (html.isBlank()) return null
            val doc = Jsoup.parse(html)

            // 1. Parse <script type="application/json" data-sjs> and other JSON script blocks
            val scripts = doc.select("script[type=application/json][data-sjs], script[type=application/json], script")
            for (script in scripts) {
                val raw = script.data().trim()
                if (!raw.startsWith("{")) continue
                if (raw.contains("thread_items") ||
                    raw.contains("xdt_api__v1__text_post_app_media") ||
                    raw.contains("containing_thread") ||
                    raw.contains("carousel_media") ||
                    raw.contains("video_versions") ||
                    raw.contains("image_versions2")
                ) {
                    try {
                        val root = JSONObject(raw)
                        findThreadsPostInJson(root, shortcode)?.let { return it }
                    } catch (_: Exception) {
                    }
                }
            }

            // 2. application/ld+json
            for (ld in doc.select("script[type=application/ld+json]")) {
                parseThreadsLdJson(ld.data().trim(), shortcode)?.let { return it }
            }

            // 3. OpenGraph fallback
            return parseThreadsOpenGraph(doc, shortcode, allowOgImageFallback)
        }

        internal fun classifyFailure(statusCode: Int, finalUrl: String, body: String): DevwolfErrorCode {
            val urlPath = try {
                URI(finalUrl).path.orEmpty().lowercase()
            } catch (_: Exception) {
                finalUrl.lowercase()
            }

            val trimmed = body.trim()
            val visibleText = if (!trimmed.startsWith("{") && trimmed.isNotEmpty()) {
                val doc = Jsoup.parse(body)
                doc.select("script, style, noscript, meta, link").remove()
                doc.text().lowercase()
            } else {
                trimmed.lowercase()
            }

            if (statusCode == 429 || visibleText.contains("too many requests") || visibleText.contains("please wait a few minutes")) {
                return DevwolfErrorCode.RATE_LIMITED
            }
            if (statusCode == 404 ||
                statusCode == 410 ||
                visibleText.contains("sorry, this page isn't available") ||
                visibleText.contains("this post is unavailable") ||
                visibleText.contains("esta página não está disponível")
            ) {
                return DevwolfErrorCode.MEDIA_UNAVAILABLE
            }
            if (visibleText.contains("this profile is private") ||
                visibleText.contains("este perfil é privado") ||
                visibleText.contains("this account is private")
            ) {
                return DevwolfErrorCode.PRIVATE_MEDIA
            }
            if (statusCode == 401 ||
                urlPath.contains("/login") ||
                urlPath.contains("/accounts/login") ||
                visibleText.contains("log in to see") ||
                visibleText.contains("log in with instagram")
            ) {
                return DevwolfErrorCode.AUTH_REQUIRED
            }
            if (statusCode in 500..599) {
                return DevwolfErrorCode.NETWORK_ERROR
            }
            return DevwolfErrorCode.EXTRACTION_FAILED
        }

        internal fun findThreadsPostInJson(root: Any?, targetShortcode: String): ThreadsParsedMedia? {
            val candidates = mutableListOf<JSONObject>()
            collectPostObjects(root, candidates, 0)
            if (candidates.isEmpty()) return null

            // Prefer exact shortcode match first, then first candidate with actual media
            val ordered = candidates.sortedByDescending { obj ->
                val code = obj.optString("code").ifBlank { obj.optString("shortcode") }
                when {
                    code.equals(targetShortcode, ignoreCase = false) -> 2
                    hasThreadsMedia(obj) -> 1
                    else -> 0
                }
            }
            for (post in ordered) {
                parseThreadsPostObject(post, targetShortcode)?.let { return it }
            }
            return null
        }

        private fun collectPostObjects(node: Any?, out: MutableList<JSONObject>, depth: Int) {
            if (node == null || depth > 24 || out.size >= 40) return
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("post")?.let { postObj ->
                        if (hasThreadsMedia(postObj) || postObj.has("code") || postObj.has("pk")) {
                            out.add(postObj)
                        }
                    }
                    node.optJSONArray("thread_items")?.let { items ->
                        for (i in 0 until items.length()) {
                            val entry = items.optJSONObject(i) ?: continue
                            val post = entry.optJSONObject("post") ?: entry
                            if (hasThreadsMedia(post)) {
                                out.add(post)
                            }
                        }
                    }
                    if (hasThreadsMedia(node) && (node.has("code") || node.has("pk") || node.has("id"))) {
                        out.add(node)
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key == "post" || key == "thread_items") continue
                        val child = node.opt(key)
                        if (child is JSONObject || child is JSONArray) {
                            collectPostObjects(child, out, depth + 1)
                        }
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        val child = node.opt(i)
                        if (child is JSONObject || child is JSONArray) {
                            collectPostObjects(child, out, depth + 1)
                        }
                    }
                }
            }
        }

        private fun hasThreadsMedia(post: JSONObject): Boolean {
            if ((post.optJSONArray("carousel_media")?.length() ?: 0) > 0) return true
            if ((post.optJSONArray("video_versions")?.length() ?: 0) > 0) return true
            if ((post.optJSONObject("image_versions2")?.optJSONArray("candidates")?.length() ?: 0) > 0) return true
            val giphy = post.optJSONObject("text_post_app_info")
                ?.optJSONObject("link_preview_attachment")
                ?.optString("url")
            return !giphy.isNullOrBlank() && giphy.endsWith(".mp4", ignoreCase = true)
        }

        private fun JSONObject.optNonNullString(key: String): String? {
            if (isNull(key)) return null
            return optString(key, "").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }

        private fun parseThreadsPostObject(post: JSONObject, fallbackShortcode: String): ThreadsParsedMedia? {
            val code = post.optNonNullString("code")
                ?: post.optNonNullString("shortcode")
                ?: fallbackShortcode
            val user = post.optJSONObject("user") ?: post.optJSONObject("owner")
            val author = user?.optNonNullString("username")
            val isPrivate = user?.optBoolean("is_private", false) == true
            val caption = post.optJSONObject("caption")?.optNonNullString("text")

            val items = mutableListOf<ResolvedMediaItem>()
            val carousel = post.optJSONArray("carousel_media")
            if (carousel != null && carousel.length() > 0) {
                for (i in 0 until carousel.length()) {
                    val child = carousel.optJSONObject(i) ?: continue
                    extractSingleThreadsItem(child, code, items.size + 1)?.let { items.add(it) }
                }
            } else {
                extractSingleThreadsItem(post, code, 1)?.let { items.add(it) }
            }

            if (items.isEmpty()) {
                return if (isPrivate) {
                    ThreadsParsedMedia(shortcode = code, author = author, items = emptyList(), isPrivate = true)
                } else {
                    null
                }
            }

            return ThreadsParsedMedia(
                shortcode = code,
                title = caption?.take(120),
                author = author,
                items = items,
                isPrivate = isPrivate,
            )
        }

        private fun extractSingleThreadsItem(node: JSONObject, shortcode: String, index: Int): ResolvedMediaItem? {
            val origW = node.optInt("original_width").takeIf { it > 0 }
            val origH = node.optInt("original_height").takeIf { it > 0 }
            val duration = node.optDouble("video_duration").takeIf { !it.isNaN() && it > 0.0 }

            val bestVideo = pickBestCandidate(node.optJSONArray("video_versions"))
            if (bestVideo != null) {
                return buildItem(
                    index = index,
                    id = "${shortcode}_$index",
                    type = MediaItemType.VIDEO,
                    url = bestVideo.first,
                    shortcode = shortcode,
                    mimeType = "video/mp4",
                    width = bestVideo.second ?: origW,
                    height = bestVideo.third ?: origH,
                    durationSeconds = duration,
                )
            }

            val bestImg = pickBestCandidate(node.optJSONObject("image_versions2")?.optJSONArray("candidates"))
            if (bestImg != null) {
                val mime = InstagramParsers.guessImageMime(bestImg.first)
                return buildItem(
                    index = index,
                    id = "${shortcode}_$index",
                    type = MediaItemType.IMAGE,
                    url = bestImg.first,
                    shortcode = shortcode,
                    mimeType = mime,
                    width = bestImg.second ?: origW,
                    height = bestImg.third ?: origH,
                )
            }
            return null
        }

        private fun pickBestCandidate(arr: JSONArray?): Triple<String, Int?, Int?>? {
            if (arr == null || arr.length() == 0) return null
            var bestUrl: String? = null
            var bestW: Int? = null
            var bestH: Int? = null
            var bestArea = -1L
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url").takeIf { it.startsWith("http") } ?: continue
                val w = obj.optInt("width").takeIf { it > 0 }
                val h = obj.optInt("height").takeIf { it > 0 }
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

        private fun parseThreadsLdJson(raw: String, shortcode: String): ThreadsParsedMedia? {
            if (raw.isBlank()) return null
            return try {
                val obj = JSONObject(raw)
                val items = mutableListOf<ResolvedMediaItem>()
                val videos = obj.optJSONArray("video")
                if (videos != null) {
                    for (i in 0 until videos.length()) {
                        val v = videos.optJSONObject(i) ?: continue
                        val url = v.optString("contentUrl").takeIf { it.startsWith("http") } ?: continue
                        items.add(
                            buildItem(
                                index = items.size + 1,
                                id = "${shortcode}_${items.size + 1}",
                                type = MediaItemType.VIDEO,
                                url = url,
                                shortcode = shortcode,
                                mimeType = "video/mp4",
                                width = v.optInt("width").takeIf { it > 0 },
                                height = v.optInt("height").takeIf { it > 0 },
                            ),
                        )
                    }
                }
                if (items.isEmpty()) {
                    val contentUrl = obj.optString("contentUrl").takeIf { it.startsWith("http") }
                    if (contentUrl != null && (obj.optString("@type").equals("VideoObject", true) || contentUrl.contains(".mp4"))) {
                        items.add(
                            buildItem(
                                index = 1,
                                id = "${shortcode}_1",
                                type = MediaItemType.VIDEO,
                                url = contentUrl,
                                shortcode = shortcode,
                                mimeType = "video/mp4",
                            ),
                        )
                    }
                }
                if (items.isEmpty()) return null
                val authorObj = obj.optJSONObject("author")
                val author = authorObj?.optString("alternateName")?.removePrefix("@")?.takeIf { it.isNotBlank() }
                    ?: authorObj?.optString("name")?.takeIf { it.isNotBlank() }
                val title = obj.optString("headline").takeIf { it.isNotBlank() }
                    ?: obj.optString("articleBody").takeIf { it.isNotBlank() }
                ThreadsParsedMedia(shortcode = shortcode, title = title?.take(120), author = author, items = items)
            } catch (_: Exception) {
                null
            }
        }

        private fun parseThreadsOpenGraph(
            doc: org.jsoup.nodes.Document,
            shortcode: String,
            allowOgImageFallback: Boolean,
        ): ThreadsParsedMedia? {
            val ogVideo = doc.selectFirst("meta[property=og:video:secure_url], meta[property=og:video]")
                ?.attr("content")?.takeIf { it.startsWith("http") }
            val ogImage = doc.selectFirst("meta[property=og:image]")
                ?.attr("content")?.takeIf { it.startsWith("http") }
            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }

            val items = mutableListOf<ResolvedMediaItem>()
            if (ogVideo != null) {
                items.add(
                    buildItem(
                        index = 1,
                        id = "${shortcode}_1",
                        type = MediaItemType.VIDEO,
                        url = ogVideo,
                        shortcode = shortcode,
                        mimeType = "video/mp4",
                    ),
                )
            } else if (allowOgImageFallback && ogImage != null && !isThreadsPlaceholderImage(ogImage)) {
                items.add(
                    buildItem(
                        index = 1,
                        id = "${shortcode}_1",
                        type = MediaItemType.IMAGE,
                        url = ogImage,
                        shortcode = shortcode,
                        mimeType = InstagramParsers.guessImageMime(ogImage),
                    ),
                )
            }
            if (items.isEmpty()) return null
            return ThreadsParsedMedia(
                shortcode = shortcode,
                title = ogTitle?.take(120),
                items = items,
            )
        }

        private fun isThreadsPlaceholderImage(url: String): Boolean {
            val lower = url.lowercase()
            return lower.contains("rsrc.php") ||
                lower.contains("threads-logo") ||
                lower.contains("threads_logo") ||
                lower.contains("static.cdninstagram.com") ||
                lower.contains("profile_pic") ||
                lower.contains("s150x150")
        }

        private fun buildItem(
            index: Int,
            id: String,
            type: MediaItemType,
            url: String,
            shortcode: String,
            mimeType: String,
            width: Int? = null,
            height: Int? = null,
            durationSeconds: Double? = null,
        ): ResolvedMediaItem {
            val ext = when {
                type == MediaItemType.VIDEO -> "mp4"
                mimeType == "image/png" -> "png"
                mimeType == "image/webp" -> "webp"
                else -> "jpg"
            }
            val cleanCode = shortcode.replace(Regex("""[^A-Za-z0-9_-]"""), "_").take(32).ifBlank { "threads" }
            val fileName = "threads_${cleanCode}_${index.toString().padStart(2, '0')}.$ext"
            return ResolvedMediaItem(
                index = index,
                id = id,
                type = type,
                url = url,
                fileName = fileName,
                mimeType = mimeType,
                width = width,
                height = height,
                durationSeconds = durationSeconds,
                headers = mapOf("Referer" to "https://www.threads.com/"),
            )
        }
    }
}
