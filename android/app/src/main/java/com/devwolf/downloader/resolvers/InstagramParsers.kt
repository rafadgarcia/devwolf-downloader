package com.devwolf.downloader.resolvers

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

data class InstagramParsedMedia(
    val shortcode: String?,
    val mediaId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val items: List<ResolvedMediaItem>,
    val isPrivate: Boolean = false,
)

data class InstagramPageContext(
    val mediaId: String? = null,
    val csrfToken: String? = null,
    val lsdToken: String? = null,
    val igAppId: String? = null,
)

/**
 * Pure Kotlin/Jsoup/org.json parsers for Instagram public embed pages, web HTML JSON blocks,
 * LD+JSON metadata, and authorized web API JSON payloads.
 */
object InstagramParsers {

    fun parseEmbedCaptionedHtml(
        html: String,
        shortcode: String,
    ): InstagramParsedMedia? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)

        // 1. Look for contextJSON or gql_data inside <script> tags
        for (script in doc.select("script")) {
            val data = script.data()
            if (data.isBlank()) continue

            extractBalancedJsonAfterKey(data, "\"gql_data\"")?.let { gqlObj ->
                val mediaObj = gqlObj.optJSONObject("shortcode_media") ?: gqlObj
                parseGraphqlMediaNode(mediaObj, shortcode)?.let { return it }
            }

            extractBalancedJsonAfterKey(data, "\"contextJSON\"")?.let { ctxObj ->
                parseContextJsonObject(ctxObj, shortcode)?.let { return it }
            }

            if (data.contains("contextJSON") && data.contains("\\\"")) {
                extractEscapedContextJson(data)?.let { ctxObj ->
                    parseContextJsonObject(ctxObj, shortcode)?.let { return it }
                }
            }
        }

        // 2. Fallback to Embed DOM elements (EmbeddedMediaVideo / EmbeddedMediaImage)
        return parseEmbedDomFallback(doc, shortcode)
    }

    fun parseWebPageHtml(
        html: String,
        shortcode: String,
        allowOgImageFallback: Boolean = true,
    ): InstagramParsedMedia? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)

        // 1. Modern JSON script blocks (<script type="application/json">)
        for (script in doc.select("script[type=application/json], script")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue

            if (raw.startsWith("{") && (
                    raw.contains("xdt_api__v1__media__shortcode__web_info") ||
                        raw.contains("xig_polaris_media") ||
                        raw.contains("xdt_api__v1__clips__clips_on_logged_out_connection_v2") ||
                        raw.contains("fetch__XDTMediaDict") ||
                        raw.contains("xdt_shortcode_media") ||
                        raw.contains("shortcode_media") ||
                        raw.contains("carousel_media") ||
                        raw.contains("video_versions")
                    )
            ) {
                try {
                    val root = JSONObject(raw)
                    findBestMediaInJson(root, shortcode)?.let { return it }
                } catch (_: Exception) {
                }
            }

            // 2. Legacy window._sharedData = {...};
            if (raw.contains("_sharedData")) {
                extractAssignedJson(raw, "_sharedData")?.let { shared ->
                    findBestMediaInJson(shared, shortcode)?.let { return it }
                }
            }

            // 3. Legacy window.__additionalDataLoaded('...', {...});
            if (raw.contains("__additionalDataLoaded")) {
                extractAdditionalDataJson(raw)?.let { addObj ->
                    findBestMediaInJson(addObj, shortcode)?.let { return it }
                }
            }
        }

        // 4. Structured application/ld+json
        for (ldScript in doc.select("script[type=application/ld+json]")) {
            val raw = ldScript.data().trim()
            if (raw.isEmpty()) continue
            parseLdJson(raw, shortcode)?.let { return it }
        }

        // 5. OpenGraph meta fallback (only when an actual video/image media URL is present)
        return parseOpenGraphFallback(doc, shortcode, allowOgImageFallback)
    }

    fun parseApiMediaJson(
        jsonText: String,
        shortcode: String,
    ): InstagramParsedMedia? {
        if (jsonText.isBlank()) return null
        return try {
            val root = JSONObject(jsonText)
            findBestMediaInJson(root, shortcode)
        } catch (_: Exception) {
            null
        }
    }

    fun extractPageContext(html: String): InstagramPageContext {
        if (html.isBlank()) return InstagramPageContext()
        val mediaId = Regex(""""(?:media_id|pk)"\s*:\s*"(\d{10,25})"""")
            .find(html)?.groupValues?.get(1)
        val csrfToken = Regex(""""csrf_token"\s*:\s*"([A-Za-z0-9_-]{8,128})"""")
            .find(html)?.groupValues?.get(1)
        val lsdToken = Regex(""""LSD"\s*,\s*\[\]\s*,\s*\{\s*"token"\s*:\s*"([^"]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex(""""lsd"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        val igAppId = Regex(""""(?:X-IG-App-ID|appId)"\s*:\s*"(\d{10,25})"""")
            .find(html)?.groupValues?.get(1)

        return InstagramPageContext(
            mediaId = mediaId,
            csrfToken = csrfToken,
            lsdToken = lsdToken,
            igAppId = igAppId,
        )
    }

    /**
     * Classifies Instagram HTTP / HTML errors accurately without falsely attributing
     * parser mismatches or generic HTML changes to PRIVATE_MEDIA.
     */
    fun classifyFailure(
        statusCode: Int,
        finalUrl: String,
        body: String,
        hasSessionCookie: Boolean,
    ): DevwolfErrorCode {
        val lowerBody = body.lowercase()
        val lowerUrl = finalUrl.lowercase()

        if (statusCode == 429 ||
            lowerBody.contains("please wait a few minutes before you try again") ||
            lowerBody.contains("\"message\":\"rate limit") ||
            lowerBody.contains("too many requests")
        ) {
            return DevwolfErrorCode.RATE_LIMITED
        }

        if (lowerUrl.contains("/challenge/") ||
            lowerBody.contains("checkpoint_required") ||
            lowerBody.contains("challenge_required") ||
            lowerBody.contains("verify you are human") ||
            lowerBody.contains("captcha")
        ) {
            return DevwolfErrorCode.CAPTCHA_REQUIRED
        }

        if (statusCode == 404 ||
            statusCode == 410 ||
            lowerBody.contains("sorry, this page isn't available") ||
            lowerBody.contains("the link you followed may be broken") ||
            lowerBody.contains("post isn't available") ||
            lowerBody.contains("this content isn't available right now") ||
            lowerBody.contains("media not found")
        ) {
            return DevwolfErrorCode.MEDIA_UNAVAILABLE
        }

        // Explicit private account / private post indicators in JSON or clear UI notice
        val explicitPrivate = Regex(""""is_private"\s*:\s*true""").containsMatchIn(lowerBody) ||
            lowerBody.contains("this account is private") ||
            lowerBody.contains("esta conta é privada") ||
            lowerBody.contains("conta privada")
        if (explicitPrivate) {
            return DevwolfErrorCode.PRIVATE_MEDIA
        }

        val loginWall = lowerUrl.contains("/accounts/login") ||
            statusCode == 401 ||
            lowerBody.contains("\"require_login\":true") ||
            lowerBody.contains("login_required") ||
            lowerBody.contains("log in to see photos and videos")
        if (loginWall) {
            return if (hasSessionCookie) DevwolfErrorCode.AUTH_REQUIRED else DevwolfErrorCode.AUTH_REQUIRED
        }

        if (statusCode in 500..599) {
            return DevwolfErrorCode.NETWORK_ERROR
        }

        return DevwolfErrorCode.EXTRACTION_FAILED
    }

    private fun parseContextJsonObject(ctxObj: JSONObject, shortcode: String): InstagramParsedMedia? {
        val contextNested = ctxObj.optJSONObject("context") ?: ctxObj
        val gql = contextNested.optJSONObject("gql_data")
            ?: ctxObj.optJSONObject("gql_data")
        if (gql != null) {
            val media = gql.optJSONObject("shortcode_media") ?: gql
            parseGraphqlMediaNode(media, shortcode)?.let { return it }
        }
        val mediaDirect = contextNested.optJSONObject("media") ?: ctxObj.optJSONObject("media")
        if (mediaDirect != null) {
            parseGraphqlMediaNode(mediaDirect, shortcode)?.let { return it }
            parseXdtMediaDict(mediaDirect, shortcode)?.let { return it }
        }
        return findBestMediaInJson(ctxObj, shortcode)
    }

    private fun parseEmbedDomFallback(doc: Document, shortcode: String): InstagramParsedMedia? {
        val items = mutableListOf<ResolvedMediaItem>()
        val author = doc.selectFirst(".UsernameText, a.Username")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val caption = doc.selectFirst(".Caption")?.ownText()?.trim()?.takeIf { it.isNotBlank() }

        val videoEl = doc.selectFirst("video.EmbeddedMediaVideo, video[src], video source[src]")
        val videoSrc = videoEl?.attr("src")?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[property=og:video], meta[property=og:video:secure_url]")?.attr("content")?.takeIf { it.startsWith("http") }

        if (videoSrc != null) {
            items.add(
                buildItem(
                    index = 1,
                    id = "${shortcode}_1",
                    type = MediaItemType.VIDEO,
                    url = videoSrc,
                    shortcode = shortcode,
                    mimeType = "video/mp4",
                ),
            )
        } else {
            val imgEl = doc.selectFirst("img.EmbeddedMediaImage[src], .Content img[src]")
            val imgSrc = imgEl?.attr("src")?.takeIf { it.startsWith("http") }
            if (imgSrc != null && !imgSrc.contains("s150x150") && !imgSrc.contains("profile_pic")) {
                items.add(
                    buildItem(
                        index = 1,
                        id = "${shortcode}_1",
                        type = MediaItemType.IMAGE,
                        url = imgSrc,
                        shortcode = shortcode,
                        mimeType = guessImageMime(imgSrc),
                    ),
                )
            }
        }

        if (items.isEmpty()) return null
        return InstagramParsedMedia(
            shortcode = shortcode,
            title = caption,
            author = author,
            items = items,
        )
    }

    private fun parseLdJson(raw: String, shortcode: String): InstagramParsedMedia? {
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
                val images = obj.optJSONArray("image")
                if (images != null) {
                    for (i in 0 until images.length()) {
                        val imgObj = images.optJSONObject(i)
                        val url = imgObj?.optString("contentUrl")?.takeIf { it.startsWith("http") }
                            ?: imgObj?.optString("url")?.takeIf { it.startsWith("http") }
                            ?: images.optString(i).takeIf { it.startsWith("http") }
                            ?: continue
                        items.add(
                            buildItem(
                                index = items.size + 1,
                                id = "${shortcode}_${items.size + 1}",
                                type = MediaItemType.IMAGE,
                                url = url,
                                shortcode = shortcode,
                                mimeType = guessImageMime(url),
                            ),
                        )
                    }
                } else {
                    val singleImg = obj.optString("image").takeIf { it.startsWith("http") }
                    if (singleImg != null) {
                        items.add(
                            buildItem(
                                index = 1,
                                id = "${shortcode}_1",
                                type = MediaItemType.IMAGE,
                                url = singleImg,
                                shortcode = shortcode,
                                mimeType = guessImageMime(singleImg),
                            ),
                        )
                    }
                }
            }
            if (items.isEmpty()) return null
            val authorObj = obj.optJSONObject("author")
            val author = authorObj?.optString("alternateName")?.removePrefix("@")?.takeIf { it.isNotBlank() }
                ?: authorObj?.optString("name")?.takeIf { it.isNotBlank() }
            val title = obj.optString("headline").takeIf { it.isNotBlank() }
                ?: obj.optString("articleBody").takeIf { it.isNotBlank() }
            InstagramParsedMedia(
                shortcode = shortcode,
                title = title?.take(120),
                author = author,
                items = items,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseOpenGraphFallback(
        doc: Document,
        shortcode: String,
        allowOgImageFallback: Boolean = true,
    ): InstagramParsedMedia? {
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
        } else if (allowOgImageFallback && ogImage != null && !ogImage.contains("instagram_logo") && !ogImage.contains("rsrc.php")) {
            items.add(
                buildItem(
                    index = 1,
                    id = "${shortcode}_1",
                    type = MediaItemType.IMAGE,
                    url = ogImage,
                    shortcode = shortcode,
                    mimeType = guessImageMime(ogImage),
                ),
            )
        }
        if (items.isEmpty()) return null
        return InstagramParsedMedia(
            shortcode = shortcode,
            title = ogTitle?.take(120),
            items = items,
        )
    }

    /**
     * Recursively searches an arbitrary Instagram JSON tree for known media containers:
     * `xdt_api__v1__media__shortcode__web_info`, `xig_polaris_media`, `shortcode_media`,
     * `xdt_shortcode_media`, `items[]`, or direct media dictionaries (`video_versions` / `carousel_media`).
     */
    internal fun findBestMediaInJson(node: Any?, shortcode: String, depth: Int = 0): InstagramParsedMedia? {
        if (node == null || depth > 22) return null
        when (node) {
            is JSONObject -> {
                // Direct GraphQL keys
                node.optJSONObject("shortcode_media")?.let {
                    parseGraphqlMediaNode(it, shortcode)?.let { parsed -> return parsed }
                }
                node.optJSONObject("xdt_shortcode_media")?.let {
                    parseGraphqlMediaNode(it, shortcode) ?: parseXdtMediaDict(it, shortcode)
                }?.let { return it }

                // Modern XDT web_info / polaris / clips wrappers
                val priorityKeys = listOf(
                    "xdt_api__v1__media__shortcode__web_info",
                    "xig_polaris_media",
                    "xdt_api__v1__clips__clips_on_logged_out_connection_v2",
                    "fetch__XDTMediaDict",
                )
                for (key in priorityKeys) {
                    if (node.has(key)) {
                        findBestMediaInJson(node.opt(key), shortcode, depth + 1)?.let { return it }
                    }
                }

                // Direct items array (V1 API or XDT web_info)
                node.optJSONArray("items")?.let { itemsArr ->
                    for (i in 0 until itemsArr.length()) {
                        val itemObj = itemsArr.optJSONObject(i) ?: continue
                        val itemCode = itemObj.optString("code")
                        if (itemCode.isNotBlank() && itemCode != shortcode && itemsArr.length() > 1) {
                            continue
                        }
                        parseXdtMediaDict(itemObj, shortcode)?.let { return it }
                        parseGraphqlMediaNode(itemObj, shortcode)?.let { return it }
                    }
                    // If none matched shortcode exactly, take first valid item
                    for (i in 0 until itemsArr.length()) {
                        val itemObj = itemsArr.optJSONObject(i) ?: continue
                        parseXdtMediaDict(itemObj, shortcode)?.let { return it }
                    }
                }

                // Direct media dictionary
                if (node.has("carousel_media") || node.has("video_versions") || node.has("image_versions2")) {
                    parseXdtMediaDict(node, shortcode)?.let { return it }
                }
                if (node.has("edge_sidecar_to_children") || node.has("video_url") || node.has("display_url")) {
                    parseGraphqlMediaNode(node, shortcode)?.let { return it }
                }

                // Recurse child keys
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key in priorityKeys || key == "items" || key == "shortcode_media" || key == "xdt_shortcode_media") continue
                    val child = node.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        findBestMediaInJson(child, shortcode, depth + 1)?.let { return it }
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val child = node.opt(i)
                    if (child is JSONObject || child is JSONArray) {
                        findBestMediaInJson(child, shortcode, depth + 1)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    internal fun parseGraphqlMediaNode(media: JSONObject, fallbackShortcode: String): InstagramParsedMedia? {
        val code = media.optString("shortcode").takeIf { it.isNotBlank() } ?: fallbackShortcode
        val mediaId = media.optString("id").takeIf { it.isNotBlank() }
        val owner = media.optJSONObject("owner") ?: media.optJSONObject("user")
        val author = owner?.optString("username")?.takeIf { it.isNotBlank() }
        val isPrivate = owner?.optBoolean("is_private", false) == true

        val caption = media.optJSONObject("edge_media_to_caption")
            ?.optJSONArray("edges")
            ?.optJSONObject(0)
            ?.optJSONObject("node")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?: media.optJSONObject("caption")?.optString("text")?.takeIf { it.isNotBlank() }

        val items = mutableListOf<ResolvedMediaItem>()
        val sidecarEdges = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (sidecarEdges != null && sidecarEdges.length() > 0) {
            for (i in 0 until sidecarEdges.length()) {
                val child = sidecarEdges.optJSONObject(i)?.optJSONObject("node") ?: continue
                extractSingleGraphqlItem(child, code, items.size + 1)?.let { items.add(it) }
            }
        } else {
            extractSingleGraphqlItem(media, code, 1)?.let { items.add(it) }
        }

        if (items.isEmpty()) return null
        return InstagramParsedMedia(
            shortcode = code,
            mediaId = mediaId,
            title = caption?.take(120),
            author = author,
            items = items,
            isPrivate = isPrivate,
        )
    }

    private fun extractSingleGraphqlItem(node: JSONObject, shortcode: String, index: Int): ResolvedMediaItem? {
        val isVideo = node.optBoolean("is_video", false) || node.has("video_url")
        val dims = node.optJSONObject("dimensions")
        val width = dims?.optInt("width")?.takeIf { it > 0 }
        val height = dims?.optInt("height")?.takeIf { it > 0 }
        val duration = node.optDouble("video_duration").takeIf { !it.isNaN() && it > 0.0 }

        if (isVideo) {
            val videoUrl = node.optString("video_url").takeIf { it.startsWith("http") }
                ?: pickBestCandidateUrl(node.optJSONArray("video_versions"))?.first
            if (videoUrl != null) {
                return buildItem(
                    index = index,
                    id = "${shortcode}_$index",
                    type = MediaItemType.VIDEO,
                    url = videoUrl,
                    shortcode = shortcode,
                    mimeType = "video/mp4",
                    width = width,
                    height = height,
                    durationSeconds = duration,
                )
            }
        }

        val bestRes = pickBestDisplayResource(node.optJSONArray("display_resources"))
        val imgUrl = bestRes?.first
            ?: node.optString("display_url").takeIf { it.startsWith("http") }
            ?: pickBestCandidateUrl(node.optJSONObject("image_versions2")?.optJSONArray("candidates"))?.first
            ?: return null

        return buildItem(
            index = index,
            id = "${shortcode}_$index",
            type = MediaItemType.IMAGE,
            url = imgUrl,
            shortcode = shortcode,
            mimeType = guessImageMime(imgUrl),
            width = bestRes?.second ?: width,
            height = bestRes?.third ?: height,
        )
    }

    internal fun parseXdtMediaDict(media: JSONObject, fallbackShortcode: String): InstagramParsedMedia? {
        val code = media.optString("code").takeIf { it.isNotBlank() }
            ?: media.optString("shortcode").takeIf { it.isNotBlank() }
            ?: fallbackShortcode
        val mediaId = media.optString("pk").takeIf { it.isNotBlank() }
            ?: media.optString("id").takeIf { it.isNotBlank() }
        val user = media.optJSONObject("user") ?: media.optJSONObject("owner")
        val author = user?.optString("username")?.takeIf { it.isNotBlank() }
        val isPrivate = user?.optBoolean("is_private", false) == true
        val caption = media.optJSONObject("caption")?.optString("text")?.takeIf { it.isNotBlank() }

        val items = mutableListOf<ResolvedMediaItem>()
        val carousel = media.optJSONArray("carousel_media")
        if (carousel != null && carousel.length() > 0) {
            for (i in 0 until carousel.length()) {
                val child = carousel.optJSONObject(i) ?: continue
                extractSingleXdtItem(child, code, items.size + 1)?.let { items.add(it) }
            }
        } else {
            extractSingleXdtItem(media, code, 1)?.let { items.add(it) }
        }

        if (items.isEmpty()) return null
        return InstagramParsedMedia(
            shortcode = code,
            mediaId = mediaId,
            title = caption?.take(120),
            author = author,
            items = items,
            isPrivate = isPrivate,
        )
    }

    private fun extractSingleXdtItem(node: JSONObject, shortcode: String, index: Int): ResolvedMediaItem? {
        val mediaType = node.optInt("media_type", -1)
        val duration = node.optDouble("video_duration").takeIf { !it.isNaN() && it > 0.0 }
        val origW = node.optInt("original_width").takeIf { it > 0 }
        val origH = node.optInt("original_height").takeIf { it > 0 }

        val bestVideo = pickBestCandidateUrl(node.optJSONArray("video_versions"))
        if (bestVideo != null && (mediaType == 2 || mediaType == -1 || node.has("video_versions"))) {
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

        val bestImg = pickBestCandidateUrl(node.optJSONObject("image_versions2")?.optJSONArray("candidates"))
            ?: node.optString("display_url").takeIf { it.startsWith("http") }?.let { Triple(it, origW, origH) }
            ?: return null

        return buildItem(
            index = index,
            id = "${shortcode}_$index",
            type = MediaItemType.IMAGE,
            url = bestImg.first,
            shortcode = shortcode,
            mimeType = guessImageMime(bestImg.first),
            width = bestImg.second ?: origW,
            height = bestImg.third ?: origH,
        )
    }

    /**
     * Sorts `video_versions` or `image_versions2.candidates` by pixel area (`width * height`) descending
     * and returns `(url, width, height)` for the highest-resolution candidate.
     */
    private fun pickBestCandidateUrl(arr: JSONArray?): Triple<String, Int?, Int?>? {
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

    private fun pickBestDisplayResource(arr: JSONArray?): Triple<String, Int?, Int?>? {
        if (arr == null || arr.length() == 0) return null
        var bestUrl: String? = null
        var bestW: Int? = null
        var bestH: Int? = null
        var bestArea = -1L
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("src").takeIf { it.startsWith("http") } ?: continue
            val w = obj.optInt("config_width").takeIf { it > 0 }
            val h = obj.optInt("config_height").takeIf { it > 0 }
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
        val cleanCode = shortcode.replace(Regex("""[^A-Za-z0-9_-]"""), "_").take(32).ifBlank { "ig" }
        val fileName = "instagram_${cleanCode}_${index.toString().padStart(2, '0')}.$ext"
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
            headers = mapOf("Referer" to "https://www.instagram.com/"),
        )
    }

    internal fun guessImageMime(url: String): String {
        val path = try { URI(url).path.orEmpty().lowercase() } catch (_: Exception) { url.lowercase() }
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    private fun extractBalancedJsonAfterKey(source: String, key: String): JSONObject? {
        val idx = source.indexOf(key)
        if (idx < 0) return null
        val braceIdx = source.indexOf('{', idx + key.length)
        if (braceIdx < 0) return null
        val jsonSlice = sliceBalancedBraces(source, braceIdx) ?: return null
        return try {
            JSONObject(jsonSlice)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractEscapedContextJson(source: String): JSONObject? {
        val m = Regex("""["']contextJSON["']\s*:\s*["'](\{.*?\})["']""").find(source)
            ?: return null
        val unescaped = m.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\/", "/")
        return try {
            JSONObject(unescaped)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAssignedJson(source: String, varName: String): JSONObject? {
        val idx = source.indexOf(varName)
        if (idx < 0) return null
        val eqIdx = source.indexOf('=', idx + varName.length)
        if (eqIdx < 0) return null
        val braceIdx = source.indexOf('{', eqIdx)
        if (braceIdx < 0) return null
        val slice = sliceBalancedBraces(source, braceIdx) ?: return null
        return try {
            JSONObject(slice)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAdditionalDataJson(source: String): JSONObject? {
        val idx = source.indexOf("__additionalDataLoaded")
        if (idx < 0) return null
        val commaIdx = source.indexOf(',', idx)
        if (commaIdx < 0) return null
        val braceIdx = source.indexOf('{', commaIdx)
        if (braceIdx < 0) return null
        val slice = sliceBalancedBraces(source, braceIdx) ?: return null
        return try {
            JSONObject(slice)
        } catch (_: Exception) {
            null
        }
    }

    private fun sliceBalancedBraces(text: String, openIdx: Int): String? {
        if (openIdx < 0 || openIdx >= text.length || text[openIdx] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIdx until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return text.substring(openIdx, i + 1)
                    }
                }
            }
        }
        return null
    }
}
