package com.devwolf.downloader

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class InstagramUrlKind {
    REEL,
    POST,
    TV,
    STORIES,
    SHARE,
    UNKNOWN,
}

data class ParsedInstagramUrl(
    val normalizedUrl: String,
    val canonicalUrl: String,
    val kind: InstagramUrlKind,
    val shortcode: String?,
    val username: String? = null,
    val storyId: String? = null,
)

/**
 * Extracts, normalizes, validates, and classifies social media URLs from share texts or user input
 * without executing shell commands or allowing SSRF-prone hosts.
 */
object UrlExtractor {
    private val URL_REGEX = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private val TRAILING_PUNCT = charArrayOf('.', ',', ';', ')', ']', '}', '!', '?')

    private val TRACKING_PARAMS = setOf(
        "igsh",
        "igshid",
        "utm_source",
        "utm_medium",
        "utm_campaign",
        "utm_term",
        "utm_content",
        "utm_id",
        "utm_name",
        "fbclid",
        "si",
        "feature",
        "ref",
        "ref_src",
        "ref_url",
        "sender_device",
        "is_from_webapp",
        "_r",
        "_t",
        "share_id",
    )

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
        "t.co" to "twitter",
        "reddit.com" to "reddit",
        "redd.it" to "reddit",
        "pinterest.com" to "pinterest",
        "pin.it" to "pinterest",
    )

    private val INSTAGRAM_HOSTS = setOf(
        "instagram.com",
        "www.instagram.com",
        "m.instagram.com",
        "instagr.am",
        "www.instagr.am",
    )

    private val SHORTCODE_REGEX = Regex("""^[A-Za-z0-9_-]{5,64}$""")

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
        if (!uri.rawUserInfo.isNullOrEmpty()) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (host.isEmpty() || host == "localhost" || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".localhost")) {
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

    /**
     * Strips tracking query parameters (such as `igsh`, `utm_*`, `fbclid`, `si`) while
     * preserving functional parameters (`v`, `t`, `list`, `index`, `id`, `fbid`, `set`).
     */
    fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (!isSafePublicHttpUrl(trimmed)) return trimmed
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return trimmed
        }
        val query = uri.rawQuery
        val filteredQuery = if (query.isNullOrBlank()) {
            null
        } else {
            val kept = query.split('&')
                .mapNotNull { part ->
                    if (part.isBlank()) return@mapNotNull null
                    val eqIdx = part.indexOf('=')
                    val rawKey = if (eqIdx >= 0) part.substring(0, eqIdx) else part
                    val decodedKey = try {
                        URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name()).lowercase()
                    } catch (_: Exception) {
                        rawKey.lowercase()
                    }
                    if (decodedKey in TRACKING_PARAMS || decodedKey.startsWith("utm_")) {
                        null
                    } else {
                        part
                    }
                }
            if (kept.isEmpty()) null else kept.joinToString("&")
        }
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase().trimEnd('.')
        val port = if (uri.port == 80 && scheme == "http" || uri.port == 443 && scheme == "https") -1 else uri.port
        val path = uri.rawPath.ifBlank { "/" }
        return buildString {
            append(scheme).append("://").append(host)
            if (port != -1) append(':').append(port)
            append(path)
            if (!filteredQuery.isNullOrBlank()) append('?').append(filteredQuery)
        }
    }

    /**
     * Strictly parses an Instagram URL (Reels, Posts, IGTV, Stories, or `/share/` links)
     * without accepting impostor domains or unsafe paths.
     */
    fun parseInstagramUrl(rawUrl: String?): ParsedInstagramUrl? {
        if (rawUrl.isNullOrBlank() || !isSafePublicHttpUrl(rawUrl)) return null
        val normalized = normalizeUrl(rawUrl)
        val uri = try {
            URI(normalized)
        } catch (_: Exception) {
            return null
        }
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        if (host !in INSTAGRAM_HOSTS) return null

        val segments = (uri.path ?: "")
            .split('/')
            .filter { it.isNotBlank() }

        if (segments.isEmpty()) return null

        return when (segments[0].lowercase()) {
            "reel", "reels" -> {
                val code = segments.getOrNull(1)?.takeIf { SHORTCODE_REGEX.matches(it) } ?: return null
                ParsedInstagramUrl(
                    normalizedUrl = normalized,
                    canonicalUrl = "https://www.instagram.com/reel/$code/",
                    kind = InstagramUrlKind.REEL,
                    shortcode = code,
                )
            }
            "p" -> {
                val code = segments.getOrNull(1)?.takeIf { SHORTCODE_REGEX.matches(it) } ?: return null
                ParsedInstagramUrl(
                    normalizedUrl = normalized,
                    canonicalUrl = "https://www.instagram.com/p/$code/",
                    kind = InstagramUrlKind.POST,
                    shortcode = code,
                )
            }
            "tv" -> {
                val code = segments.getOrNull(1)?.takeIf { SHORTCODE_REGEX.matches(it) } ?: return null
                ParsedInstagramUrl(
                    normalizedUrl = normalized,
                    canonicalUrl = "https://www.instagram.com/tv/$code/",
                    kind = InstagramUrlKind.TV,
                    shortcode = code,
                )
            }
            "stories" -> {
                val user = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
                val storyId = segments.getOrNull(2)?.takeIf { it.all(Char::isDigit) }
                ParsedInstagramUrl(
                    normalizedUrl = normalized,
                    canonicalUrl = if (storyId != null) {
                        "https://www.instagram.com/stories/$user/$storyId/"
                    } else {
                        "https://www.instagram.com/stories/$user/"
                    },
                    kind = InstagramUrlKind.STORIES,
                    shortcode = storyId,
                    username = user,
                    storyId = storyId,
                )
            }
            "share" -> {
                val sub = segments.getOrNull(1)?.lowercase()
                val token = segments.getOrNull(2) ?: segments.getOrNull(1)
                val canonical = when {
                    sub in setOf("reel", "p", "tv") && !segments.getOrNull(2).isNullOrBlank() ->
                        "https://www.instagram.com/share/$sub/${segments[2]}/"
                    !token.isNullOrBlank() ->
                        "https://www.instagram.com/share/$token/"
                    else -> return null
                }
                ParsedInstagramUrl(
                    normalizedUrl = normalized,
                    canonicalUrl = canonical,
                    kind = InstagramUrlKind.SHARE,
                    shortcode = null,
                )
            }
            else -> null
        }
    }

    /**
     * Resolves DNS for [host] and verifies every returned IP address is publicly routable.
     * Throws [SecurityException] if any resolved address is loopback, RFC1918, link-local,
     * CGNAT, multicast, unspecified, or IPv6 ULA/mapped-private.
     */
    fun validateDnsPublicIp(host: String): List<InetAddress> {
        val cleanHost = host.trim().trimEnd('.').removePrefix("[").removeSuffix("]")
        if (cleanHost.isEmpty() || isIpLiteral(cleanHost)) {
            throw SecurityException("Host literal ou inválido bloqueado pela política SSRF: $cleanHost")
        }
        val addresses = InetAddress.getAllByName(cleanHost).toList()
        if (addresses.isEmpty()) {
            throw SecurityException("Nenhum endereço DNS resolvido para $cleanHost")
        }
        for (addr in addresses) {
            if (!isPublicRoutableAddress(addr)) {
                throw SecurityException("Endereço IP não público bloqueado pela política SSRF")
            }
        }
        return addresses
    }

    fun isPublicRoutableAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress ||
            addr.isLoopbackAddress ||
            addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress ||
            addr.isMulticastAddress
        ) {
            return false
        }

        return when (addr) {
            is Inet4Address -> isPublicIpv4Bytes(addr.address)
            is Inet6Address -> {
                val bytes = addr.address
                val first = bytes[0].toInt() and 0xFF
                // Unique Local Addresses fc00::/7
                if ((first and 0xFE) == 0xFC) return false
                // IPv4-mapped IPv6 ::ffff:0:0/96
                val isV4Mapped = (0..9).all { bytes[it].toInt() == 0 } &&
                    (bytes[10].toInt() and 0xFF) == 0xFF &&
                    (bytes[11].toInt() and 0xFF) == 0xFF
                if (isV4Mapped) {
                    val v4 = bytes.copyOfRange(12, 16)
                    return isPublicIpv4Bytes(v4)
                }
                true
            }
            else -> false
        }
    }

    private fun isPublicIpv4Bytes(b: ByteArray): Boolean {
        if (b.size != 4) return false
        val o0 = b[0].toInt() and 0xFF
        val o1 = b[1].toInt() and 0xFF
        // 0.0.0.0/8, 10.0.0.0/8, 127.0.0.0/8
        if (o0 == 0 || o0 == 10 || o0 == 127) return false
        // 100.64.0.0/10 (CGNAT)
        if (o0 == 100 && o1 in 64..127) return false
        // 169.254.0.0/16 (link-local / cloud metadata)
        if (o0 == 169 && o1 == 254) return false
        // 172.16.0.0/12
        if (o0 == 172 && o1 in 16..31) return false
        // 192.0.0.0/24, 192.0.2.0/24, 192.168.0.0/16
        if (o0 == 192 && (o1 == 168 || o1 == 0)) return false
        // 198.18.0.0/15 (benchmark) & 198.51.100.0/24
        if (o0 == 198 && (o1 == 18 || o1 == 19 || o1 == 51)) return false
        // 203.0.113.0/24, >=224.0.0.0 (multicast/reserved)
        if (o0 >= 224) return false
        return true
    }

    internal fun isIpLiteral(host: String): Boolean {
        val clean = host.removePrefix("[").removeSuffix("]")
        if (clean.isEmpty()) return true
        // Reject IPv6, integer, hex, octal, or dotted-quad literals
        if (clean.contains(':')) return true
        if (clean.all { it.isDigit() }) return true
        if (clean.startsWith("0x", ignoreCase = true)) return true
        val parts = clean.split('.')
        if (parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && (
                    part.all { it.isDigit() } ||
                        part.startsWith("0x", ignoreCase = true)
                    )
            }
        ) {
            return true
        }
        return false
    }

    fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
