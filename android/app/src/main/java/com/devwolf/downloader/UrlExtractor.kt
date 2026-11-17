package com.devwolf.downloader

import java.net.InetAddress
import java.net.URI

/**
 * Extracts, validates, and classifies social media URLs from share texts or user input
 * without executing shell commands or allowing SSRF-prone hosts.
 */
object UrlExtractor {
    private val URL_REGEX = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private val TRAILING_PUNCT = charArrayOf('.', ',', ';', ')', ']', '}', '!', '?')

    private val SUPPORTED_HOSTS = mapOf(
        "instagram.com" to "instagram",
        "instagr.am" to "instagram",
        "facebook.com" to "facebook",
        "fb.watch" to "facebook",
        "fb.com" to "facebook",
        "tiktok.com" to "tiktok",
        "youtube.com" to "youtube",
        "youtu.be" to "youtube",
        "twitter.com" to "twitter",
        "x.com" to "twitter",
        "reddit.com" to "reddit",
        "redd.it" to "reddit",
        "pinterest.com" to "pinterest",
        "pin.it" to "pinterest",
    )

    val ALLOWED_VAULT_PLATFORMS = setOf(
        "instagram",
        "facebook",
        "tiktok",
        "youtube",
        "twitter",
        "reddit",
        "pinterest",
    )

    fun extractFirstValidUrl(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        for (match in URL_REGEX.findAll(rawText)) {
            val candidate = match.value.trimEnd(*TRAILING_PUNCT)
            if (isSafePublicHttpUrl(candidate)) {
                return candidate
            }
        }
        return null
    }

    fun isSafePublicHttpUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val uri = try {
            URI(rawUrl.trim())
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (host.isEmpty() || host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) {
            return false
        }
        if (isIpLiteral(host)) return false
        if (uri.port != -1 && uri.port != 80 && uri.port != 443) return false
        return true
    }

    fun isSupportedPlatformUrl(rawUrl: String?): Boolean {
        if (!isSafePublicHttpUrl(rawUrl)) return false
        return detectPlatform(rawUrl) != "unknown"
    }

    fun detectPlatform(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return "unknown"
        val host = try {
            URI(rawUrl.trim()).host?.lowercase()?.trimEnd('.') ?: return "unknown"
        } catch (_: Exception) {
            return "unknown"
        }
        for ((domain, platform) in SUPPORTED_HOSTS) {
            if (host == domain || host.endsWith(".$domain")) {
                return platform
            }
        }
        return "unknown"
    }

    internal fun isIpLiteral(host: String): Boolean {
        val clean = host.removePrefix("[").removeSuffix("]")
        // Reject dotted quad, integer, hex, or IPv6 literals
        if (clean.contains(':')) return true
        if (clean.all { it.isDigit() }) return true
        if (clean.startsWith("0x", ignoreCase = true)) return true
        val parts = clean.split('.')
        if (parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }) {
            return true
        }
        return try {
            val addr = InetAddress.getByName(clean)
            addr.hostAddress == clean && (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress)
        } catch (_: Exception) {
            false
        }
    }
}
