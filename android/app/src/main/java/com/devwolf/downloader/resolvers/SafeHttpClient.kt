package com.devwolf.downloader.resolvers

import com.devwolf.downloader.UrlExtractor
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Call
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class HttpTextResponse(
    val statusCode: Int,
    val finalUrl: String,
    val contentType: String?,
    val body: String,
    val headers: Map<String, String>,
    val ttfbMs: Long,
    val httpRequests: Int,
)

/**
 * Shared OkHttp connection-pooled HTTP client enforcing SSRF protection on initial URL,
 * DNS resolution, and every redirect hop, while measuring TTFB and request counts.
 */
class SafeHttpClient internal constructor(
    private val allowLoopbackForTesting: Boolean = false,
    private val maxRedirects: Int = 5,
    connectTimeoutSec: Long = 15,
    readTimeoutSec: Long = 30,
) {
    private val ssrfDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (allowLoopbackForTesting) {
                val clean = hostname.removePrefix("[").removeSuffix("]")
                val addrs = InetAddress.getAllByName(clean).toList()
                if (addrs.isNotEmpty() && addrs.all { it.isLoopbackAddress }) {
                    return addrs
                }
            }
            return UrlExtractor.validateDnsPublicIp(hostname)
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
        .dns(ssrfDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .build()

    fun isAllowedUrl(url: String): Boolean {
        if (UrlExtractor.isSafePublicHttpUrl(url)) return true
        if (!allowLoopbackForTesting) return false
        val uri = try { URI(url) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val rawHost = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (rawHost == "localhost" || rawHost == "127.0.0.1" || rawHost == "::1") return true
        return try {
            val addrs = InetAddress.getAllByName(rawHost)
            addrs.isNotEmpty() && addrs.all { it.isLoopbackAddress }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Executes an HTTP GET following up to [maxRedirects] redirects, validating SSRF on every hop
     * and scoping sensitive headers (Cookie, X-CSRFToken) strictly to matching origin hosts.
     */
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBodyBytes: Long = 6L * 1024L * 1024L,
        cancellationCheck: () -> Boolean = { false },
    ): HttpTextResponse {
        var currentUrl = url
        var hopCount = 0
        val initialHost = extractHost(url)
        val totalRequests = AtomicInteger(0)
        var lastTtfbMs = 0L

        while (true) {
            if (cancellationCheck()) {
                throw ResolverException(DevwolfErrorCode.CANCELLED)
            }
            if (!isAllowedUrl(currentUrl)) {
                throw ResolverException(
                    errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                    userMessage = "URL bloqueada pela política de segurança SSRF.",
                    technicalReason = "SSRF validation rejected URL host",
                )
            }

            val reqHost = extractHost(currentUrl)
            val scopedHeaders = scopeHeadersForHost(headers, initialHost, reqHost)
            val ttfbHolder = AtomicLong(-1L)
            val startNs = System.nanoTime()

            val callClient = client.newBuilder()
                .eventListener(object : EventListener() {
                    override fun responseHeadersStart(call: Call) {
                        if (ttfbHolder.get() < 0L) {
                            ttfbHolder.set((System.nanoTime() - startNs) / 1_000_000L)
                        }
                    }
                })
                .build()

            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .get()
            for ((k, v) in scopedHeaders) {
                if (v.isNotBlank()) {
                    reqBuilder.header(k, v)
                }
            }

            totalRequests.incrementAndGet()
            var redirectTarget: String? = null
            var result: HttpTextResponse? = null
            callClient.newCall(reqBuilder.build()).execute().use { resp ->
                lastTtfbMs = ttfbHolder.get().takeIf { it >= 0L }
                    ?: ((System.nanoTime() - startNs) / 1_000_000L)

                if (resp.isRedirect) {
                    val location = resp.header("Location")
                    if (location.isNullOrBlank()) {
                        result = HttpTextResponse(
                            statusCode = resp.code,
                            finalUrl = currentUrl,
                            contentType = resp.header("Content-Type"),
                            body = "",
                            headers = flattenHeaders(resp),
                            ttfbMs = lastTtfbMs,
                            httpRequests = totalRequests.get(),
                        )
                    } else {
                        hopCount++
                        if (hopCount > maxRedirects) {
                            throw ResolverException(
                                errorCode = DevwolfErrorCode.NETWORK_ERROR,
                                technicalReason = "Exceeded max redirect hops ($maxRedirects)",
                            )
                        }
                        redirectTarget = URI(currentUrl).resolve(location).toASCIIString()
                    }
                } else {
                    val bodyBytes = readLimitedBytes(resp, maxBodyBytes)
                    val charset = resp.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    result = HttpTextResponse(
                        statusCode = resp.code,
                        finalUrl = currentUrl,
                        contentType = resp.header("Content-Type"),
                        body = String(bodyBytes, charset),
                        headers = flattenHeaders(resp),
                        ttfbMs = lastTtfbMs,
                        httpRequests = totalRequests.get(),
                    )
                }
            }
            if (result != null) return result!!
            currentUrl = redirectTarget!!
        }
    }

    /**
     * Resolves redirects (HEAD/GET with Range 0-0 fallback) safely to discover canonical URL
     * for shortlinks such as `/share/reel/`, `pin.it`, or `redd.it`.
     */
    fun resolveFinalUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cancellationCheck: () -> Boolean = { false },
    ): Pair<String, Int> {
        var currentUrl = url
        var hopCount = 0
        var requests = 0
        val initialHost = extractHost(url)

        while (true) {
            if (cancellationCheck()) throw ResolverException(DevwolfErrorCode.CANCELLED)
            if (!isAllowedUrl(currentUrl)) {
                throw ResolverException(
                    errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                    userMessage = "Destino de redirecionamento bloqueado pela política de segurança.",
                    technicalReason = "SSRF rejected redirect target",
                )
            }
            val reqHost = extractHost(currentUrl)
            val scoped = scopeHeadersForHost(headers, initialHost, reqHost)
            val reqBuilder = Request.Builder().url(currentUrl).get()
            for ((k, v) in scoped) {
                if (v.isNotBlank()) reqBuilder.header(k, v)
            }
            requests++
            var redirectTarget: String? = null
            var resolvedPair: Pair<String, Int>? = null
            client.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isRedirect) {
                    val loc = resp.header("Location")
                    if (loc == null) {
                        resolvedPair = currentUrl to requests
                    } else {
                        hopCount++
                        if (hopCount > maxRedirects) {
                            throw ResolverException(
                                errorCode = DevwolfErrorCode.NETWORK_ERROR,
                                technicalReason = "Too many redirects ($maxRedirects)",
                            )
                        }
                        redirectTarget = URI(currentUrl).resolve(loc).toASCIIString()
                    }
                } else {
                    resolvedPair = currentUrl to requests
                }
            }
            if (resolvedPair != null) return resolvedPair!!
            currentUrl = redirectTarget!!
        }
    }

    /**
     * Executes an SSRF-validated streaming GET call and passes the open [Response] to [consumer].
     */
    fun <T> executeStreamingGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        cancellationCheck: () -> Boolean = { false },
        consumer: (response: Response, finalUrl: String, ttfbMs: Long, httpRequests: Int) -> T,
    ): T {
        var currentUrl = url
        var hopCount = 0
        val initialHost = extractHost(url)
        val requests = AtomicInteger(0)

        while (true) {
            if (cancellationCheck()) throw ResolverException(DevwolfErrorCode.CANCELLED)
            if (!isAllowedUrl(currentUrl)) {
                throw ResolverException(
                    errorCode = DevwolfErrorCode.UNSUPPORTED_MEDIA,
                    userMessage = "URL de mídia bloqueada pela política de segurança SSRF.",
                    technicalReason = "SSRF rejected media URL",
                )
            }

            val reqHost = extractHost(currentUrl)
            val scoped = scopeHeadersForHost(headers, initialHost, reqHost)
            val ttfbHolder = AtomicLong(-1L)
            val startNs = System.nanoTime()

            val callClient = client.newBuilder()
                .eventListener(object : EventListener() {
                    override fun responseHeadersStart(call: Call) {
                        if (ttfbHolder.get() < 0L) {
                            ttfbHolder.set((System.nanoTime() - startNs) / 1_000_000L)
                        }
                    }
                })
                .build()

            val reqBuilder = Request.Builder().url(currentUrl).get()
            for ((k, v) in scoped) {
                if (v.isNotBlank()) reqBuilder.header(k, v)
            }

            requests.incrementAndGet()
            var redirectTarget: String? = null
            var consumed = false
            var outputValue: T? = null
            callClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.isRedirect) {
                    val loc = resp.header("Location")
                        ?: throw ResolverException(
                            errorCode = DevwolfErrorCode.NETWORK_ERROR,
                            technicalReason = "Redirect without Location header (${resp.code})",
                        )
                    hopCount++
                    if (hopCount > maxRedirects) {
                        throw ResolverException(
                            errorCode = DevwolfErrorCode.NETWORK_ERROR,
                            technicalReason = "Too many redirects ($maxRedirects)",
                        )
                    }
                    redirectTarget = URI(currentUrl).resolve(loc).toASCIIString()
                } else {
                    val ttfbMs = ttfbHolder.get().takeIf { it >= 0L }
                        ?: ((System.nanoTime() - startNs) / 1_000_000L)
                    outputValue = consumer(resp, currentUrl, ttfbMs, requests.get())
                    consumed = true
                }
            }
            if (consumed) {
                @Suppress("UNCHECKED_CAST")
                return outputValue as T
            }
            currentUrl = redirectTarget!!
        }
    }

    private fun readLimitedBytes(resp: Response, maxBytes: Long): ByteArray {
        val stream = resp.body?.byteStream() ?: return ByteArray(0)
        stream.use { input ->
            val buffer = ByteArray(16 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > maxBytes) {
                    throw IOException("Resposta HTTP excedeu limite de $maxBytes bytes")
                }
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun flattenHeaders(resp: Response): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (name in resp.headers.names()) {
            if (!name.equals("set-cookie", ignoreCase = true)) {
                map[name.lowercase()] = resp.header(name).orEmpty()
            }
        }
        return map
    }

    companion object {
        val shared: SafeHttpClient by lazy { SafeHttpClient() }

        const val DEFAULT_MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        const val DEFAULT_DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val SENSITIVE_HEADERS = setOf(
            "cookie",
            "authorization",
            "x-csrftoken",
            "x-ig-www-claim",
        )

        fun extractHost(url: String): String {
            return try {
                URI(url).host?.lowercase()?.trimEnd('.') ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        /**
         * Ensures sensitive headers (such as Instagram session cookies or CSRF tokens)
         * are ONLY sent to the matching origin domain (e.g. `*.instagram.com`) and never
         * leaked across redirects or to third-party CDN hosts.
         */
        fun scopeHeadersForHost(
            headers: Map<String, String>,
            expectedHost: String,
            actualHost: String,
        ): Map<String, String> {
            if (headers.isEmpty()) return emptyMap()
            val sameDomain = expectedHost.isNotEmpty() && actualHost.isNotEmpty() && (
                actualHost == expectedHost ||
                    isSameRootDomain(expectedHost, actualHost, "instagram.com") ||
                    isSameRootDomain(expectedHost, actualHost, "pinterest.com") ||
                    isSameRootDomain(expectedHost, actualHost, "reddit.com") ||
                    (isThreadsDomain(expectedHost) && isThreadsDomain(actualHost))
                )
            if (sameDomain) return headers
            return headers.filterKeys { it.lowercase() !in SENSITIVE_HEADERS }
        }

        private fun isSameRootDomain(h1: String, h2: String, root: String): Boolean {
            val m1 = h1 == root || h1.endsWith(".$root")
            val m2 = h2 == root || h2.endsWith(".$root")
            return m1 && m2
        }

        private fun isThreadsDomain(host: String): Boolean {
            return host == "threads.net" ||
                host.endsWith(".threads.net") ||
                host == "threads.com" ||
                host.endsWith(".threads.com")
        }

        /**
         * Redacts cookies, tokens, and query signatures before storing or copying diagnostics.
         */
        fun sanitizeForDiagnostics(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw
                .replace(Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-+/=]+"""), "Bearer [REDACTED]")
                .replace(Regex("""(?i)(cookie|authorization|x-csrftoken|sessionid|csrftoken|access_token|refresh_token)\s*[:=]\s*[^\s;,\r\n]+"""), "$1=[REDACTED]")
                .replace(Regex("""(https?://[^\s?]+)\?[^\s"']+"""), "$1?[REDACTED_QUERY]")
        }
    }
}
