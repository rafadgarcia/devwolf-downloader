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
            val canAttemptApi = hasSession || !csrf.isNullOrBlank() || !lastPageContext.mediaId.isNullOrBlank()
            if (!canAttemptApi) return null

            return runStrategy(
                strategyName = "AuthorizedWebApiStrategy",
                isMainPage = false,
                allowAuthWallPoison = false,
            ) {
                val apiUrl = if (!lastPageContext.mediaId.isNullOrBlank()) {
                    "$baseUrl/api/v1/media/${lastPageContext.mediaId}/info/"
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
                    lastPageContext.igAppId?.let { put("X-IG-App-ID", it) }
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
