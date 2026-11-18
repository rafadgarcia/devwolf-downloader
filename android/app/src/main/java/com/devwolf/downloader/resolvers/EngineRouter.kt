package com.devwolf.downloader.resolvers

import com.devwolf.downloader.UrlExtractor
import java.util.concurrent.atomic.AtomicLong

data class EngineRoutePlan(
    val platform: String,
    val normalizedUrl: String,
    val primaryEngine: EngineKind,
    val fallbackEngines: List<EngineKind>,
    val shouldInitializeYtdlpUpfront: Boolean,
)

data class PlatformRouteCapability(
    val platform: String,
    val primaryEngine: EngineKind,
    val fallbackEngines: List<EngineKind>,
    val summaryPtBr: String,
)

data class DnrTelemetrySnapshot(
    val strategies: List<String> = listOf(
        "EmbedCaptionedStrategy",
        "WebPageJsonStrategy",
        "AuthorizedWebApiStrategy",
    ),
    val avgResolutionMs: Long? = null,
    val totalResolutions: Long = 0L,
)

/**
 * Central platform routing and fallback registry for local Android execution.
 * Enforces strict host classification via [UrlExtractor] and guarantees terminal
 * platform errors stop immediately without abusive fallback retries.
 */
class EngineRouter(
    private val instagramResolver: InstagramNativeResolver = InstagramNativeResolver(),
    private val galleryDlResolver: GalleryDlLocalResolver = GalleryDlLocalResolver(),
    private val threadsResolver: ThreadsNativeResolver = ThreadsNativeResolver(),
) {

    fun route(rawUrl: String): EngineRoutePlan {
        if (!UrlExtractor.isSafePublicHttpUrl(rawUrl)) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                userMessage = "URL inválida ou bloqueada pela política de segurança.",
                technicalReason = "UrlExtractor.isSafePublicHttpUrl returned false",
            )
        }
        val normalized = UrlExtractor.normalizeUrl(rawUrl)
        val platform = UrlExtractor.detectPlatform(normalized)
        if (platform == "unknown") {
            throw ResolverException(
                errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                userMessage = "Plataforma não suportada pelo Devwolf Downloader.",
                technicalReason = "Unknown platform host",
            )
        }

        return when (platform) {
            "instagram" -> EngineRoutePlan(
                platform = platform,
                normalizedUrl = normalized,
                primaryEngine = EngineKind.DNR,
                fallbackEngines = listOf(EngineKind.YT_DLP),
                shouldInitializeYtdlpUpfront = false,
            )
            "threads" -> EngineRoutePlan(
                platform = platform,
                normalizedUrl = normalized,
                primaryEngine = EngineKind.DNR,
                fallbackEngines = listOf(EngineKind.YT_DLP),
                shouldInitializeYtdlpUpfront = false,
            )
            "pinterest" -> EngineRoutePlan(
                platform = platform,
                normalizedUrl = normalized,
                primaryEngine = EngineKind.GALLERY_DL_LOCAL,
                fallbackEngines = listOf(EngineKind.YT_DLP),
                shouldInitializeYtdlpUpfront = false,
            )
            "reddit" -> EngineRoutePlan(
                platform = platform,
                normalizedUrl = normalized,
                primaryEngine = EngineKind.GALLERY_DL_LOCAL,
                fallbackEngines = listOf(EngineKind.YT_DLP),
                shouldInitializeYtdlpUpfront = false,
            )
            "twitter" -> EngineRoutePlan(
                platform = platform,
                normalizedUrl = normalized,
                primaryEngine = EngineKind.YT_DLP,
                fallbackEngines = listOf(EngineKind.GALLERY_DL_LOCAL),
                shouldInitializeYtdlpUpfront = true,
            )
            else -> EngineRoutePlan(
                platform = platform,
                normalizedUrl = normalized,
                primaryEngine = EngineKind.YT_DLP,
                fallbackEngines = emptyList(),
                shouldInitializeYtdlpUpfront = true,
            )
        }
    }

    /**
     * Resolves [request] using a Kotlin-native resolver ([EngineKind.DNR] or [EngineKind.GALLERY_DL_LOCAL]).
     * Throws [ResolverException] on any resolution failure.
     */
    fun resolveNative(request: ResolutionRequest, engineKind: EngineKind): ResolutionResult {
        val resolver: MediaResolver = when (engineKind) {
            EngineKind.DNR -> {
                val effectivePlatform = request.platform.ifBlank { UrlExtractor.detectPlatform(request.url) }
                if (effectivePlatform == "threads" || threadsResolver.supports(request.url, effectivePlatform)) {
                    threadsResolver
                } else {
                    instagramResolver
                }
            }
            EngineKind.GALLERY_DL_LOCAL -> galleryDlResolver
            EngineKind.YT_DLP -> throw ResolverException(
                errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                technicalReason = "YT_DLP must be executed via DevwolfDownloadService/YoutubeDL runtime",
            )
        }
        val result = resolver.resolve(request)
        if (engineKind == EngineKind.DNR) {
            recordDnrLatency(result.metrics.resolutionMs)
        }
        return result
    }

    /**
     * Determines whether transitioning from [fromEngine] to [toEngine] is permitted
     * for [errorCode]. Terminal account/platform/network-limit errors always halt immediately.
     */
    fun shouldFallback(
        fromEngine: EngineKind,
        toEngine: EngineKind,
        errorCode: DevwolfErrorCode,
    ): Boolean {
        if (errorCode == DevwolfErrorCode.AUTH_REQUIRED ||
            errorCode == DevwolfErrorCode.PRIVATE_MEDIA ||
            errorCode == DevwolfErrorCode.RATE_LIMITED ||
            errorCode == DevwolfErrorCode.CAPTCHA_REQUIRED ||
            errorCode == DevwolfErrorCode.MEDIA_UNAVAILABLE ||
            errorCode == DevwolfErrorCode.CANCELLED ||
            errorCode == DevwolfErrorCode.STORAGE_ERROR
        ) {
            return false
        }
        if (errorCode == DevwolfErrorCode.UNSUPPORTED_MEDIA) {
            // Twitter image-only tweets cause yt-dlp to report UNSUPPORTED_MEDIA ("No video found")
            return (fromEngine == EngineKind.YT_DLP && toEngine == EngineKind.GALLERY_DL_LOCAL) ||
                (fromEngine == EngineKind.GALLERY_DL_LOCAL && toEngine == EngineKind.YT_DLP)
        }
        return !errorCode.isTerminal
    }

    fun getCapabilityTable(): List<PlatformRouteCapability> = CAPABILITY_TABLE

    companion object {
        private val dnrTotalMs = AtomicLong(0L)
        private val dnrCount = AtomicLong(0L)

        val shared: EngineRouter by lazy { EngineRouter() }

        fun recordDnrLatency(ms: Long) {
            if (ms >= 0L) {
                dnrTotalMs.addAndGet(ms)
                dnrCount.incrementAndGet()
            }
        }

        fun getDnrTelemetry(): DnrTelemetrySnapshot {
            val count = dnrCount.get()
            val avg = if (count > 0L) dnrTotalMs.get() / count else null
            return DnrTelemetrySnapshot(
                avgResolutionMs = avg,
                totalResolutions = count,
            )
        }

        val CAPABILITY_TABLE: List<PlatformRouteCapability> = listOf(
            PlatformRouteCapability(
                platform = "instagram",
                primaryEngine = EngineKind.DNR,
                fallbackEngines = listOf(EngineKind.YT_DLP),
                summaryPtBr = "Instagram -> DNR (fallback yt-dlp)",
            ),
            PlatformRouteCapability(
                platform = "threads",
                primaryEngine = EngineKind.DNR,
                fallbackEngines = listOf(EngineKind.YT_DLP),
                summaryPtBr = "Threads -> DNR (fallback yt-dlp)",
            ),
            PlatformRouteCapability(
                platform = "youtube",
                primaryEngine = EngineKind.YT_DLP,
                fallbackEngines = emptyList(),
                summaryPtBr = "YouTube / Shorts -> yt-dlp",
            ),
            PlatformRouteCapability(
                platform = "tiktok",
                primaryEngine = EngineKind.YT_DLP,
                fallbackEngines = emptyList(),
                summaryPtBr = "TikTok -> yt-dlp",
            ),
            PlatformRouteCapability(
                platform = "facebook",
                primaryEngine = EngineKind.YT_DLP,
                fallbackEngines = emptyList(),
                summaryPtBr = "Facebook -> yt-dlp",
            ),
            PlatformRouteCapability(
                platform = "twitter",
                primaryEngine = EngineKind.YT_DLP,
                fallbackEngines = listOf(EngineKind.GALLERY_DL_LOCAL),
                summaryPtBr = "Twitter/X -> yt-dlp (fallback imagens local)",
            ),
            PlatformRouteCapability(
                platform = "pinterest",
                primaryEngine = EngineKind.GALLERY_DL_LOCAL,
                fallbackEngines = listOf(EngineKind.YT_DLP),
                summaryPtBr = "Pinterest -> Resolvedor local (fallback yt-dlp)",
            ),
            PlatformRouteCapability(
                platform = "reddit",
                primaryEngine = EngineKind.GALLERY_DL_LOCAL,
                fallbackEngines = listOf(EngineKind.YT_DLP),
                summaryPtBr = "Reddit -> Resolvedor local (fallback yt-dlp DASH)",
            ),
        )
    }
}
