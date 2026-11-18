package com.devwolf.downloader.resolvers

import java.io.File

/**
 * Structured error taxonomy shared across all local engines (DNR, yt-dlp, gallery-dl/local)
 * and exposed verbatim to the Capacitor bridge and React UI.
 */
enum class DevwolfErrorCode(
    val isTerminal: Boolean,
    val defaultMessagePtBr: String,
) {
    AUTH_REQUIRED(
        isTerminal = true,
        defaultMessagePtBr = "Esta mídia exige login na plataforma para ser acessada.",
    ),
    PRIVATE_MEDIA(
        isTerminal = true,
        defaultMessagePtBr = "Este conteúdo pertence a uma conta ou publicação privada.",
    ),
    RATE_LIMITED(
        isTerminal = true,
        defaultMessagePtBr = "A plataforma limitou temporariamente as requisições (429). Aguarde alguns minutos.",
    ),
    CAPTCHA_REQUIRED(
        isTerminal = true,
        defaultMessagePtBr = "A plataforma solicitou verificação anti-robô (CAPTCHA).",
    ),
    MEDIA_UNAVAILABLE(
        isTerminal = true,
        defaultMessagePtBr = "Mídia indisponível, removida ou expirada na origem.",
    ),
    UNSUPPORTED_MEDIA(
        isTerminal = true,
        defaultMessagePtBr = "Tipo de mídia ou link não suportado pelo motor selecionado.",
    ),
    CANCELLED(
        isTerminal = true,
        defaultMessagePtBr = "Download cancelado pelo usuário.",
    ),
    STORAGE_ERROR(
        isTerminal = true,
        defaultMessagePtBr = "Erro ao salvar arquivo no armazenamento ou galeria do dispositivo.",
    ),
    EXTRACTOR_OUTDATED(
        isTerminal = false,
        defaultMessagePtBr = "Extrator local desatualizado para esta plataforma. Atualize o motor nos Ajustes.",
    ),
    EXTRACTION_FAILED(
        isTerminal = false,
        defaultMessagePtBr = "Não foi possível extrair a mídia desta página.",
    ),
    NETWORK_ERROR(
        isTerminal = false,
        defaultMessagePtBr = "Falha de conexão de rede ao baixar a mídia.",
    ),
}

enum class MediaItemType(val wireName: String) {
    VIDEO("video"),
    IMAGE("image"),
    AUDIO("audio"),
}

enum class EngineKind(val wireName: String) {
    DNR("dnr"),
    GALLERY_DL_LOCAL("gallery-dl"),
    YT_DLP("yt-dlp"),
}

data class ResolvedMediaItem(
    val index: Int,
    val id: String,
    val type: MediaItemType,
    val url: String,
    val fileName: String,
    val mimeType: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationSeconds: Double? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class StrategyAttemptMetric(
    val strategyName: String,
    val durationMs: Long,
    val httpRequests: Int,
    val success: Boolean,
    val errorCode: DevwolfErrorCode? = null,
    val note: String? = null,
)

data class ResolutionMetrics(
    val resolutionMs: Long = 0L,
    val httpRequestCount: Int = 0,
    val strategyAttempts: List<StrategyAttemptMetric> = emptyList(),
)

data class ResolutionResult(
    val platform: String,
    val shortcode: String? = null,
    val title: String? = null,
    val author: String? = null,
    val engineUsed: String,
    val engineVersion: String? = null,
    val strategyUsed: String,
    val items: List<ResolvedMediaItem>,
    val metrics: ResolutionMetrics = ResolutionMetrics(),
)

data class ResolutionRequest(
    val url: String,
    val platform: String,
    val format: String = "auto",
    val quality: String = "best",
    val cookieHeader: String? = null,
    val userAgent: String? = null,
    val cancellationCheck: () -> Boolean = { false },
    val accessToken: String? = null,
    val username: String? = null,
)

data class HttpDownloadProgress(
    val itemIndex: Int,
    val totalItems: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val percent: Double?,
    val isIndeterminate: Boolean,
    val speedBytesPerSec: Long?,
    val etaSeconds: Long?,
    val ttfbMs: Long? = null,
)

data class HttpDownloadResult(
    val item: ResolvedMediaItem,
    val file: File,
    val bytesDownloaded: Long,
    val ttfbMs: Long,
    val transferMs: Long,
    val httpRequests: Int,
)

class ResolverException(
    val errorCode: DevwolfErrorCode,
    val userMessage: String = errorCode.defaultMessagePtBr,
    val technicalReason: String? = null,
    val strategyUsed: String? = null,
    val httpStatus: Int? = null,
    val metrics: ResolutionMetrics = ResolutionMetrics(),
    cause: Throwable? = null,
) : RuntimeException(userMessage, cause)

interface MediaResolver {
    val engineKind: EngineKind
    val engineId: String
    val engineVersion: String
    fun supports(url: String, platform: String): Boolean
    fun resolve(request: ResolutionRequest): ResolutionResult
}

/**
 * Classifies yt-dlp stderr, HTTP status codes, and runtime exceptions into DevwolfErrorCode.
 * Never classifies generic parser/HTML changes or yt-dlp age warnings as PRIVATE_MEDIA.
 */
object ErrorClassifier {
    fun fromHttpStatus(statusCode: Int, contextHint: String? = null): DevwolfErrorCode {
        return when (statusCode) {
            401 -> DevwolfErrorCode.AUTH_REQUIRED
            403 -> {
                val hint = contextHint?.lowercase().orEmpty()
                when {
                    hint.contains("whoa there, pardner") ||
                        hint.contains("blocked by network security") ||
                        hint.contains("403 blocked") ||
                        hint.contains("cloudflare") ||
                        hint.contains("fastly") -> DevwolfErrorCode.EXTRACTION_FAILED
                    hint.contains("\"reason\": \"private\"") ||
                        hint.contains("\"reason\":\"private\"") ||
                        hint.contains("\"subreddit_type\": \"private\"") ||
                        hint.contains("\"subreddit_type\":\"private\"") ||
                        hint.contains("this community is private") -> DevwolfErrorCode.PRIVATE_MEDIA
                    hint.contains("captcha") || hint.contains("challenge") -> DevwolfErrorCode.CAPTCHA_REQUIRED
                    hint.contains("login") -> DevwolfErrorCode.AUTH_REQUIRED
                    else -> DevwolfErrorCode.EXTRACTION_FAILED
                }
            }
            404, 410 -> DevwolfErrorCode.MEDIA_UNAVAILABLE
            429 -> DevwolfErrorCode.RATE_LIMITED
            in 500..599 -> DevwolfErrorCode.NETWORK_ERROR
            else -> DevwolfErrorCode.EXTRACTION_FAILED
        }
    }

    fun authRequiredMessageForPlatform(platform: String?): String {
        return when (platform?.trim()?.lowercase()) {
            "reddit" -> "O Reddit exigiu autenticação para acessar esta mídia. Conecte sua conta do Reddit em Contas e Sessões."
            "threads" -> "Esta publicação exige sessão no Threads. Conecte sua conta do Threads em Contas e Sessões."
            "instagram" -> "Esta publicação exige login no Instagram. Conecte sua conta do Instagram em Contas e Sessões."
            else -> DevwolfErrorCode.AUTH_REQUIRED.defaultMessagePtBr
        }
    }

    fun fromYtdlpOutput(rawMessage: String?): Pair<DevwolfErrorCode, String> {
        val raw = rawMessage.orEmpty().trim()
        val errorOnly = extractFatalErrorLines(raw)
        val cleanReason = stripYtdlpReportBoilerplate(SafeHttpClient.sanitizeForDiagnostics(errorOnly))
        val lower = errorOnly.lowercase()
        val lowerClean = cleanReason.lowercase()
        val hasExplicitAgeWarning = YTDLP_AGE_WARNING_REGEX.containsMatchIn(raw)

        return when {
            lower.contains("interrupted") || lower.contains("cancelled") || lower.contains("canceled") ->
                DevwolfErrorCode.CANCELLED to DevwolfErrorCode.CANCELLED.defaultMessagePtBr

            lower.contains("captcha") || lower.contains("checkpoint_required") || lower.contains("challenge_required") ->
                DevwolfErrorCode.CAPTCHA_REQUIRED to DevwolfErrorCode.CAPTCHA_REQUIRED.defaultMessagePtBr

            lower.contains("429") || lower.contains("too many requests") || lower.contains("rate limit") || lower.contains("wait a few minutes") ->
                DevwolfErrorCode.RATE_LIMITED to DevwolfErrorCode.RATE_LIMITED.defaultMessagePtBr

            lower.contains("account is private") || lower.contains("private account") || lower.contains("this video is private") || lower.contains("private post") ->
                DevwolfErrorCode.PRIVATE_MEDIA to DevwolfErrorCode.PRIVATE_MEDIA.defaultMessagePtBr

            lower.contains("login required") || lower.contains("sign in to confirm") || lower.contains("authentication required") || lower.contains("use --cookies") || lower.contains("cookies-from-browser") ->
                DevwolfErrorCode.AUTH_REQUIRED to DevwolfErrorCode.AUTH_REQUIRED.defaultMessagePtBr

            lower.contains("404") || lower.contains("not found") || lower.contains("unavailable") || lower.contains("has been removed") || lower.contains("deleted") || lower.contains("does not exist") ->
                DevwolfErrorCode.MEDIA_UNAVAILABLE to DevwolfErrorCode.MEDIA_UNAVAILABLE.defaultMessagePtBr

            lower.contains("drm") || lower.contains("no video could be found") || lower.contains("unsupported url") ->
                DevwolfErrorCode.UNSUPPORTED_MEDIA to DevwolfErrorCode.UNSUPPORTED_MEDIA.defaultMessagePtBr

            lower.contains("timed out") || lower.contains("timeout") || lower.contains("unknownhost") || lower.contains("connect") || lower.contains("network") || lower.contains("ssl") || lower.contains("socket") ->
                DevwolfErrorCode.NETWORK_ERROR to DevwolfErrorCode.NETWORK_ERROR.defaultMessagePtBr

            hasExplicitAgeWarning ||
                lowerClean.contains("please update yt-dlp") ||
                lowerClean.contains("yt-dlp version is outdated") ->
                DevwolfErrorCode.EXTRACTOR_OUTDATED to DevwolfErrorCode.EXTRACTOR_OUTDATED.defaultMessagePtBr

            else ->
                DevwolfErrorCode.EXTRACTION_FAILED to (cleanReason.take(260).ifBlank { DevwolfErrorCode.EXTRACTION_FAILED.defaultMessagePtBr })
        }
    }

    private val YTDLP_AGE_WARNING_REGEX = Regex(
        """your yt-dlp version.*older than 90 days|yt-dlp is more than 90 days old""",
        RegexOption.IGNORE_CASE,
    )

    private val YTDLP_REPORT_BOILERPLATE_REGEX = Regex(
        """(?i)\s*[.;]?\s*(?:please report this issue on https?://\S+.*|make sure you are using the latest version.*|confirm you are on the latest version.*)$""",
    )

    fun stripYtdlpReportBoilerplate(message: String): String {
        if (message.isBlank()) return ""
        return message.replace(YTDLP_REPORT_BOILERPLATE_REGEX, "").trim().trimEnd(';', '.')
    }

    fun fromThrowable(error: Throwable): Pair<DevwolfErrorCode, String> {
        if (error is ResolverException) {
            return error.errorCode to error.userMessage
        }
        if (error is InterruptedException) {
            return DevwolfErrorCode.CANCELLED to DevwolfErrorCode.CANCELLED.defaultMessagePtBr
        }
        val msg = error.message.orEmpty()
        val lower = msg.lowercase()
        return when {
            lower.contains("mediastore") || lower.contains("enospc") || lower.contains("no space") || lower.contains("permission denied") ->
                DevwolfErrorCode.STORAGE_ERROR to DevwolfErrorCode.STORAGE_ERROR.defaultMessagePtBr
            else -> fromYtdlpOutput(msg)
        }
    }

    /**
     * Strips yt-dlp non-fatal WARNING lines (such as "yt-dlp is more than 90 days old")
     * so age warnings never masquerade as fatal extraction failures.
     */
    fun extractFatalErrorLines(rawStderr: String): String {
        if (rawStderr.isBlank()) return ""
        val lines = rawStderr.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val fatal = lines.filter { line ->
            !line.startsWith("WARNING:", ignoreCase = true) &&
                !line.contains("older than 90 days", ignoreCase = true) &&
                !line.contains("more than 90 days old", ignoreCase = true)
        }
        return (if (fatal.isNotEmpty()) fatal else lines).joinToString(" | ")
    }
}
