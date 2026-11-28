package com.devwolf.downloader.resolvers

import com.devwolf.downloader.InstagramUrlKind
import com.devwolf.downloader.ParsedInstagramUrl
import com.devwolf.downloader.SecureVault
import com.devwolf.downloader.UrlExtractor
import java.io.IOException

/**
 * Devwolf Native Resolver (DNR) for Instagram.
 * Executes isolated, individually timed resolution strategies:
 * 1. EmbedCaptionedStrategy
 * 2. WebPageJsonStrategy
 * 3. AuthorizedWebApiStrategy
 *
 * Returns ordered individual [ResolvedMediaItem] items (never ZIPs) and structured errors.
 */
class InstagramNativeResolver(
    private val httpClient: SafeHttpClient = SafeHttpClient.shared,
    private val baseUrl: String = "https://www.instagram.com",
) : MediaResolver {

    override val engineKind: EngineKind = EngineKind.DNR
    override val engineId: String = "dnr"
    override val engineVersion: String = "3.0.0"

    override fun supports(url: String, platform: String): Boolean {
        return platform == "instagram" && UrlExtractor.parseInstagramUrl(url) != null
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

        var parsed = UrlExtractor.parseInstagramUrl(request.url)
            ?: throw ResolverException(
                errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                technicalReason = "URL do Instagram fora do padrão suportado (/reel/, /p/, /tv/, /stories/, /share/)",
                metrics = buildMetrics(),
            )

        val baseHeaders = buildInstagramHeaders(
            userAgent = request.userAgent ?: SafeHttpClient.DEFAULT_MOBILE_CHROME_UA,
            cookieHeader = request.cookieHeader,
        )

        // Resolve `/share/` links first via SSRF-checked redirect follower
        if (parsed.kind == InstagramUrlKind.SHARE) {
            val shareTarget = rewriteBaseUrl(parsed.canonicalUrl)
            val (resolvedUrl, reqs) = httpClient.resolveFinalUrl(
                url = shareTarget,
                headers = baseHeaders,
                cancellationCheck = request.cancellationCheck,
            )
            totalHttpRequests += reqs
            parsed = UrlExtractor.parseInstagramUrl(resolvedUrl)
                ?: throw ResolverException(
                    errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                    technicalReason = "Link compartilhado do Instagram não redirecionou para /reel/ ou /p/ válido",
                    metrics = buildMetrics(),
                )
        }

        if (parsed.kind == InstagramUrlKind.STORIES) {
            return resolveStories(parsed, request, baseHeaders, overallStartMs, totalHttpRequests)
        }

        val shortcode = parsed.shortcode
            ?: throw ResolverException(
                errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                technicalReason = "Shortcode não encontrado na URL do Instagram",
                metrics = buildMetrics(),
            )

        val hasSession = !request.cookieHeader.isNullOrBlank()
        var lastPageContext = InstagramPageContext()
        var sawAuthWall = false
        var sawNetworkError = false
        var lastTechnicalReason: String? = null

        fun runStrategy(
            strategyName: String,
            isMainPage: Boolean,
            allowAuthWallPoison: Boolean,
            block: () -> Pair<HttpTextResponse, InstagramParsedMedia?>,
        ): ResolutionResult? {
            val start = System.currentTimeMillis()
            var reqs = 0
            try {
                val (resp, parsedMedia) = block()
                reqs = resp.httpRequests
                totalHttpRequests += reqs

                if (parsedMedia?.isPrivate == true) {
                    val code = DevwolfErrorCode.PRIVATE_MEDIA
                    attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, false, code, "is_private=true"))
                    throw ResolverException(code, strategyUsed = strategyName, metrics = buildMetrics())
                }

                val hasAcceptableMedia = parsedMedia != null && parsedMedia.items.isNotEmpty() &&
                    (parsed.kind != InstagramUrlKind.REEL || parsedMedia.items.any { it.type == MediaItemType.VIDEO })

                if (hasAcceptableMedia) {
                    attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, true))
                    return buildResult(parsed, parsedMedia!!, strategyName, buildMetrics())
                }

                val errCode = InstagramParsers.classifyFailure(resp.statusCode, resp.finalUrl, resp.body, hasSession)
                val isImmediateTerminal = errCode == DevwolfErrorCode.RATE_LIMITED ||
                    errCode == DevwolfErrorCode.CAPTCHA_REQUIRED ||
                    errCode == DevwolfErrorCode.PRIVATE_MEDIA ||
                    (isMainPage && errCode == DevwolfErrorCode.MEDIA_UNAVAILABLE)

                if (isImmediateTerminal) {
                    attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, false, errCode, "HTTP ${resp.statusCode}"))
                    throw ResolverException(errCode, strategyUsed = strategyName, httpStatus = resp.statusCode, metrics = buildMetrics())
                }

                if (allowAuthWallPoison && errCode == DevwolfErrorCode.AUTH_REQUIRED) {
                    sawAuthWall = true
                }
                if (errCode == DevwolfErrorCode.NETWORK_ERROR) {
                    sawNetworkError = true
                }
                val effectiveCode = if (!allowAuthWallPoison && errCode == DevwolfErrorCode.AUTH_REQUIRED) {
                    DevwolfErrorCode.EXTRACTION_FAILED
                } else if (!isMainPage && errCode == DevwolfErrorCode.MEDIA_UNAVAILABLE) {
                    DevwolfErrorCode.EXTRACTION_FAILED
                } else {
                    errCode
                }
                lastTechnicalReason = "$strategyName: HTTP ${resp.statusCode}, no acceptable media parsed ($effectiveCode)"
                attempts.add(StrategyAttemptMetric(strategyName, System.currentTimeMillis() - start, reqs, false, effectiveCode, lastTechnicalReason))
            } catch (e: ResolverException) {
                if (e.errorCode == DevwolfErrorCode.CANCELLED ||
                    e.errorCode == DevwolfErrorCode.PRIVATE_MEDIA ||
                    e.errorCode == DevwolfErrorCode.RATE_LIMITED ||
                    e.errorCode == DevwolfErrorCode.CAPTCHA_REQUIRED ||
                    (isMainPage && e.errorCode == DevwolfErrorCode.MEDIA_UNAVAILABLE)
                ) {
                    throw e
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

        fun tryEmbedCaptioned(): ResolutionResult? {
            if (parsed.kind == InstagramUrlKind.STORIES) return null
            return runStrategy(
                strategyName = "EmbedCaptionedStrategy",
                isMainPage = false,
                allowAuthWallPoison = false,
            ) {
                val embedUrl = "$baseUrl/p/$shortcode/embed/captioned/?cr=1&v=14&wp=1080"
                val embedHeaders = mapOf(
                    "User-Agent" to SafeHttpClient.DEFAULT_DESKTOP_CHROME_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Referer" to "$baseUrl/",
                )
                val resp = httpClient.getText(embedUrl, embedHeaders, cancellationCheck = request.cancellationCheck)
                resp to InstagramParsers.parseEmbedCaptionedHtml(resp.body, shortcode)
            }
        }

        fun tryWebPageJson(): ResolutionResult? {
            return runStrategy(
                strategyName = "WebPageJsonStrategy",
                isMainPage = true,
                allowAuthWallPoison = true,
            ) {
                val pageUrl = rewriteBaseUrl(parsed.canonicalUrl)
                val resp = httpClient.getText(pageUrl, baseHeaders, cancellationCheck = request.cancellationCheck)
                lastPageContext = InstagramParsers.extractPageContext(resp.body)
                val parsedMedia = InstagramParsers.parseWebPageHtml(
                    html = resp.body,
                    shortcode = shortcode,
                    allowOgImageFallback = parsed.kind != InstagramUrlKind.REEL,
                )
                resp to parsedMedia
            }
        }

        fun tryAuthorizedWebApi(): ResolutionResult? {
            val csrf = SecureVault.extractCookieValue(request.cookieHeader, "csrftoken") ?: lastPageContext.csrfToken
            // With a session, the media id can be derived from the shortcode, so photos and
            // carousels resolve even when the page HTML carries no media data.
            val mediaId = lastPageContext.mediaId?.takeIf { it.isNotBlank() }
                ?: if (hasSession) InstagramParsers.shortcodeToMediaId(shortcode) else null
            val canAttemptApi = hasSession || !csrf.isNullOrBlank() || !mediaId.isNullOrBlank()
            if (!canAttemptApi) return null

            return runStrategy(
                strategyName = "AuthorizedWebApiStrategy",
                isMainPage = false,
                allowAuthWallPoison = false,
            ) {
                val apiUrl = if (!mediaId.isNullOrBlank()) {
                    "$baseUrl/api/v1/media/$mediaId/info/"
                } else {
                    val seg = when (parsed.kind) {
                        InstagramUrlKind.REEL -> "reel"
                        InstagramUrlKind.TV -> "tv"
                        else -> "p"
                    }
                    "$baseUrl/$seg/$shortcode/?__a=1&__d=dis"
                }

                val apiHeaders = LinkedHashMap(baseHeaders).apply {
                    put("Accept", "application/json, text/plain, */*")
                    put("X-Requested-With", "XMLHttpRequest")
                    put("Referer", parsed.canonicalUrl)
                    if (!csrf.isNullOrBlank()) put("X-CSRFToken", csrf)
                    put("X-IG-App-ID", lastPageContext.igAppId ?: INSTAGRAM_WEB_APP_ID)
                }

                val resp = httpClient.getText(apiUrl, apiHeaders, cancellationCheck = request.cancellationCheck)
                resp to InstagramParsers.parseApiMediaJson(resp.body, shortcode)
            }
        }

        if (hasSession) {
            // Session-first execution: leverage authenticated WebView session before public embed fallback
            tryWebPageJson()?.let { return it }
            tryAuthorizedWebApi()?.let { return it }
            tryEmbedCaptioned()?.let { return it }
        } else {
            // Anonymous execution: public embed first, then web page JSON, then API if page yielded tokens
            tryEmbedCaptioned()?.let { return it }
            tryWebPageJson()?.let { return it }
            tryAuthorizedWebApi()?.let { return it }
        }

        val finalErrorCode = when {
            sawAuthWall -> DevwolfErrorCode.AUTH_REQUIRED
            sawNetworkError -> DevwolfErrorCode.NETWORK_ERROR
            else -> DevwolfErrorCode.EXTRACTION_FAILED
        }
        throw ResolverException(
            errorCode = finalErrorCode,
            userMessage = finalErrorCode.defaultMessagePtBr,
            technicalReason = SafeHttpClient.sanitizeForDiagnostics(lastTechnicalReason),
            strategyUsed = attempts.lastOrNull()?.strategyName,
            metrics = buildMetrics(),
        )
    }

    /**
     * Stories always require the user's own Instagram session (connected in Ajustes → Contas):
     * `web_profile_info` → user id → `feed/reels_media`. Highlights (`/stories/highlights/<id>/`)
     * use `reel_ids=highlight:<id>`. A story id in the URL selects that single story.
     */
    private fun resolveStories(
        parsed: ParsedInstagramUrl,
        request: ResolutionRequest,
        baseHeaders: Map<String, String>,
        startMs: Long,
        priorRequests: Int,
    ): ResolutionResult {
        var requests = priorRequests
        fun metrics(success: Boolean, code: DevwolfErrorCode? = null, note: String? = null) = ResolutionMetrics(
            resolutionMs = System.currentTimeMillis() - startMs,
            httpRequestCount = requests,
            strategyAttempts = listOf(
                StrategyAttemptMetric("InstagramStoriesApiStrategy", System.currentTimeMillis() - startMs, requests, success, code, note),
            ),
        )
        if (request.cookieHeader.isNullOrBlank()) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.AUTH_REQUIRED,
                userMessage = "Stories do Instagram só podem ser baixados com a sua conta conectada em Ajustes → Contas e Sessões.",
                technicalReason = "Instagram stories without session",
                strategyUsed = "InstagramStoriesApiStrategy",
                metrics = metrics(false, DevwolfErrorCode.AUTH_REQUIRED),
            )
        }
        val user = parsed.username.orEmpty()
        val isHighlight = user.equals("highlights", ignoreCase = true)
        val csrf = SecureVault.extractCookieValue(request.cookieHeader, "csrftoken")
        val apiHeaders = LinkedHashMap(baseHeaders).apply {
            put("Accept", "application/json, text/plain, */*")
            put("X-Requested-With", "XMLHttpRequest")
            put("X-IG-App-ID", INSTAGRAM_WEB_APP_ID)
            put("Referer", parsed.canonicalUrl)
            if (!csrf.isNullOrBlank()) put("X-CSRFToken", csrf)
        }

        fun fail(resp: HttpTextResponse, what: String): Nothing {
            val code = when (resp.statusCode) {
                401, 403 -> DevwolfErrorCode.AUTH_REQUIRED
                404 -> DevwolfErrorCode.MEDIA_UNAVAILABLE
                429 -> DevwolfErrorCode.RATE_LIMITED
                in 500..599 -> DevwolfErrorCode.NETWORK_ERROR
                else -> InstagramParsers.classifyFailure(resp.statusCode, resp.finalUrl, resp.body, true)
            }
            throw ResolverException(
                errorCode = code,
                userMessage = if (code == DevwolfErrorCode.AUTH_REQUIRED) {
                    "A sessão do Instagram expirou ou não tem acesso a este story. Reconecte a conta em Ajustes → Contas e Sessões."
                } else {
                    code.defaultMessagePtBr
                },
                technicalReason = "Instagram stories: $what HTTP ${resp.statusCode}",
                httpStatus = resp.statusCode,
                strategyUsed = "InstagramStoriesApiStrategy",
                metrics = metrics(false, code, "HTTP ${resp.statusCode}"),
            )
        }

        val reelId = if (isHighlight) {
            val highlightId = parsed.storyId
                ?: throw ResolverException(
                    errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                    technicalReason = "Highlight sem id na URL",
                    metrics = metrics(false, DevwolfErrorCode.UNSUPPORTED_MEDIA),
                )
            "highlight:$highlightId"
        } else {
            if (user.isBlank()) {
                throw ResolverException(
                    errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                    technicalReason = "Story sem usuário na URL",
                    metrics = metrics(false, DevwolfErrorCode.UNSUPPORTED_MEDIA),
                )
            }
            val profileResp = httpClient.getText(
                "$baseUrl/api/v1/users/web_profile_info/?username=${UrlExtractor.urlEncode(user)}",
                apiHeaders,
                cancellationCheck = request.cancellationCheck,
            )
            requests += profileResp.httpRequests
            if (profileResp.statusCode !in 200..299) fail(profileResp, "web_profile_info")
            InstagramParsers.parseWebProfileUserId(profileResp.body)
                ?: throw ResolverException(
                    errorCode = DevwolfErrorCode.MEDIA_UNAVAILABLE,
                    userMessage = "Perfil do Instagram não encontrado ou indisponível.",
                    technicalReason = "web_profile_info sem user.id",
                    metrics = metrics(false, DevwolfErrorCode.MEDIA_UNAVAILABLE),
                )
        }

        val reelsResp = httpClient.getText(
            "$baseUrl/api/v1/feed/reels_media/?reel_ids=${UrlExtractor.urlEncode(reelId)}",
            apiHeaders,
            cancellationCheck = request.cancellationCheck,
        )
        requests += reelsResp.httpRequests
        if (reelsResp.statusCode !in 200..299) fail(reelsResp, "reels_media")
        val media = InstagramParsers.parseStoryReelsJson(
            reelsResp.body,
            storyId = if (isHighlight) null else parsed.storyId,
            fallbackName = if (isHighlight) "highlight_${parsed.storyId}" else user,
        ) ?: throw ResolverException(
            errorCode = DevwolfErrorCode.MEDIA_UNAVAILABLE,
            userMessage = if (parsed.storyId != null && !isHighlight) {
                "Este story expirou ou não está mais disponível (stories duram 24 h)."
            } else {
                "Nenhum story ativo encontrado para este perfil."
            },
            technicalReason = "reels_media sem itens correspondentes",
            metrics = metrics(false, DevwolfErrorCode.MEDIA_UNAVAILABLE),
        )
        return buildResult(parsed, media, "InstagramStoriesApiStrategy", metrics(true))
    }

    private fun buildResult(
        parsedUrl: ParsedInstagramUrl,
        media: InstagramParsedMedia,
        strategyUsed: String,
        metrics: ResolutionMetrics,
    ): ResolutionResult {
        return ResolutionResult(
            platform = "instagram",
            shortcode = media.shortcode ?: parsedUrl.shortcode,
            title = media.title,
            author = media.author,
            engineUsed = engineId,
            engineVersion = engineVersion,
            strategyUsed = strategyUsed,
            items = media.items,
            metrics = metrics,
        )
    }

    private fun buildInstagramHeaders(
        userAgent: String,
        cookieHeader: String?,
    ): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["User-Agent"] = userAgent
        map["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        map["Accept-Language"] = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        if (!cookieHeader.isNullOrBlank()) {
            map["Cookie"] = cookieHeader.trim()
        }
        return map
    }

    private fun rewriteBaseUrl(canonicalUrl: String): String {
        val trimmedBase = baseUrl.trimEnd('/')
        if (trimmedBase == "https://www.instagram.com") return canonicalUrl
        return canonicalUrl.replaceFirst("https://www.instagram.com", trimmedBase)
    }
}

/** Public app id sent by instagram.com's own web client (required by the `/api/v1/` web endpoints). */
internal const val INSTAGRAM_WEB_APP_ID = "936619743392459"
