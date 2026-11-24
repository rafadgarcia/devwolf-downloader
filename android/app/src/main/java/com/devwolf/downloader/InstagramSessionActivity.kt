package com.devwolf.downloader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Optional, user-initiated WebView / OAuth2 PKCE login screen for Instagram, Reddit, and Threads.
 * Never captures passwords or injects keyloggers; reads standard WebView cookies or official
 * OAuth2 Installed App PKCE codes only for the selected platform and persists them encrypted in [SecureVault].
 */
class InstagramSessionActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLabel: TextView
    private lateinit var doneBtn: Button

    private var platform: String = PLATFORM_INSTAGRAM
    private var redditClientId: String? = null
    private var isRedditOAuthMode = false
    private var pkceVerifier: String? = null
    private var oauthState: String? = null
    private var oauthRedirectUriInUse: String = REDDIT_OAUTH_REDIRECT_URI
    private var sessionSaved = false
    private var oauthExchangeInFlight = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionPollRunnable = object : Runnable {
        override fun run() {
            if (sessionSaved || isFinishing || isDestroyed || isRedditOAuthMode) return
            tryCaptureSession(force = false)
            if (!sessionSaved && !isFinishing && !isDestroyed) {
                mainHandler.postDelayed(this, SESSION_POLL_INTERVAL_MS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        platform = intent.getStringExtra(EXTRA_PLATFORM)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it in SUPPORTED_PLATFORMS }
            ?: PLATFORM_INSTAGRAM
        redditClientId = intent.getStringExtra(EXTRA_REDDIT_CLIENT_ID)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val isRedditOAuth = platform == PLATFORM_REDDIT && !redditClientId.isNullOrBlank()
        isRedditOAuthMode = isRedditOAuth

        applyPurpleSystemChrome()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG_DEEP)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_SURFACE_HEADER)
            val pad = dp(12)
            setPadding(pad, dp(14), pad, dp(10))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = when {
                isRedditOAuth -> "Conectar Reddit (OAuth2)"
                platform == PLATFORM_REDDIT -> "Conectar Reddit"
                platform == PLATFORM_THREADS -> "Conectar Threads"
                else -> "Conectar Instagram"
            }
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val cancelBtn = Button(this).apply {
            text = "Cancelar"
            setTextColor(COLOR_LAVENDER_TEXT)
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isAllCaps = false
            setOnClickListener {
                setResult(
                    Activity.RESULT_CANCELED,
                    Intent().apply {
                        putExtra(EXTRA_SAVED, false)
                        putExtra(EXTRA_PLATFORM, platform)
                        putExtra(EXTRA_MESSAGE, "Login cancelado pelo usuário.")
                    },
                )
                finish()
            }
        }

        doneBtn = Button(this).apply {
            text = "Concluir"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(COLOR_ACCENT_PURPLE)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(6), dp(14), dp(6))
            minimumHeight = dp(36)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            if (isRedditOAuth) {
                visibility = android.view.View.GONE
            }
            setOnClickListener {
                if (!tryCaptureSession(force = true)) {
                    statusLabel.text = when (platform) {
                        PLATFORM_REDDIT -> "Faça login no Reddit primeiro até carregar seu feed antes de concluir."
                        PLATFORM_THREADS -> "Faça login no Threads primeiro até carregar seu feed antes de concluir."
                        else -> "Faça login primeiro até aparecer seu feed antes de concluir."
                    }
                }
            }
        }

        topRow.addView(titleView)
        topRow.addView(cancelBtn)
        topRow.addView(doneBtn)

        statusLabel = TextView(this).apply {
            text = if (isRedditOAuth) {
                "Autorize o acesso OAuth2 do Reddit abaixo para conectar sua conta automaticamente."
            } else {
                "Mídias públicas não exigem login. Entre na sua conta abaixo — assim que o login concluir, sua sessão criptografada será salva automaticamente."
            }
            setTextColor(COLOR_LAVENDER_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        }

        header.addView(topRow)
        header.addView(statusLabel)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressTintList = ColorStateList.valueOf(COLOR_ACCENT_PURPLE)
            progressBackgroundTintList = ColorStateList.valueOf(COLOR_SURFACE_CARD)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            setBackgroundColor(COLOR_BG_DEEP)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            val currentUa = settings.userAgentString.orEmpty()
            if (currentUa.contains("; wv)")) {
                settings.userAgentString = currentUa.replace("; wv)", ")")
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (isRedditOAuth && url.startsWith(REDDIT_OAUTH_REDIRECT_URI, ignoreCase = true)) {
                    handleRedditOAuthCallback(url)
                    return true
                }
                val scheme = request.url?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return true
                }
                if (!UrlExtractor.isSafePublicHttpUrl(url)) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                evaluateAutoCapture(url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                evaluateAutoCapture(url)
            }
        }

        root.addView(header)
        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (::webView.isInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        setResult(
                            Activity.RESULT_CANCELED,
                            Intent().apply {
                                putExtra(EXTRA_SAVED, false)
                                putExtra(EXTRA_PLATFORM, platform)
                                putExtra(EXTRA_MESSAGE, "Login cancelado pelo usuário.")
                            },
                        )
                        finish()
                    }
                }
            },
        )

        val initialUrl = if (isRedditOAuth) {
            buildRedditOAuthAuthorizeUrl(redditClientId!!)
        } else {
            when (platform) {
                PLATFORM_REDDIT -> REDDIT_LOGIN_URL
                PLATFORM_THREADS -> THREADS_LOGIN_URL
                else -> INSTAGRAM_LOGIN_URL
            }
        }
        webView.loadUrl(initialUrl)
    }

    override fun onResume() {
        super.onResume()
        if (!isRedditOAuthMode && !sessionSaved) {
            mainHandler.removeCallbacks(sessionPollRunnable)
            mainHandler.postDelayed(sessionPollRunnable, SESSION_POLL_INTERVAL_MS)
        }
    }

    override fun onPause() {
        mainHandler.removeCallbacks(sessionPollRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(sessionPollRunnable)
        ioExecutor.shutdownNow()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun evaluateAutoCapture(url: String?) {
        if (url == null || isRedditOAuthMode || sessionSaved) return
        when (platform) {
            PLATFORM_INSTAGRAM -> {
                if (isHostMatch(url, "instagram.com")) {
                    tryCaptureSession(force = false)
                }
            }
            PLATFORM_THREADS -> {
                if (isHostMatch(url, "threads.net") || isHostMatch(url, "threads.com")) {
                    tryCaptureSession(force = false)
                }
            }
            PLATFORM_REDDIT -> {
                if (isHostMatch(url, "reddit.com")) {
                    tryCaptureSession(force = false)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyPurpleSystemChrome() {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = COLOR_BG_DEEP
            navigationBarColor = COLOR_BG_DEEP
            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    private fun buildRedditOAuthAuthorizeUrl(
        clientId: String,
        redirectUri: String = REDDIT_OAUTH_REDIRECT_URI,
    ): String {
        val verifier = generateRandomUrlSafe(64)
        val state = generateRandomUrlSafe(24)
        pkceVerifier = verifier
        oauthState = state
        oauthRedirectUriInUse = redirectUri

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        return "https://www.reddit.com/api/v1/authorize.compact" +
            "?client_id=${urlEncode(clientId)}" +
            "&response_type=code" +
            "&state=${urlEncode(state)}" +
            "&redirect_uri=${urlEncode(redirectUri)}" +
            "&duration=permanent" +
            "&scope=read%20identity" +
            "&code_challenge=${urlEncode(challenge)}" +
            "&code_challenge_method=S256"
    }

    private fun handleRedditOAuthCallback(callbackUrl: String) {
        if (oauthExchangeInFlight || sessionSaved) return
        val uri = try {
            Uri.parse(callbackUrl)
        } catch (_: Exception) {
            finishWithError("Resposta OAuth2 do Reddit inválida.")
            return
        }
        val err = uri.getQueryParameter("error")
        if (!err.isNullOrBlank()) {
            finishWithError("Autorização no Reddit recusada: $err")
            return
        }
        val returnedState = uri.getQueryParameter("state")
        if (oauthState.isNullOrBlank() || returnedState != oauthState) {
            finishWithError("Estado OAuth2 inválido. Tente conectar novamente.")
            return
        }
        val code = uri.getQueryParameter("code")
        val clientId = redditClientId
        val verifier = pkceVerifier
        if (code.isNullOrBlank() || clientId.isNullOrBlank() || verifier.isNullOrBlank()) {
            finishWithError("Código de autorização OAuth2 ausente.")
            return
        }

        oauthExchangeInFlight = true
        statusLabel.text = "Validando token OAuth2 oficial do Reddit…"

        ioExecutor.execute {
            try {
                val formBody = "grant_type=authorization_code" +
                    "&code=${urlEncode(code)}" +
                    "&redirect_uri=${urlEncode(oauthRedirectUriInUse)}" +
                    "&code_verifier=${urlEncode(verifier)}"
                val basicAuth = Base64.encodeToString(
                    "$clientId:".toByteArray(StandardCharsets.UTF_8),
                    Base64.NO_WRAP,
                )

                val tokenConn = (URL("https://www.reddit.com/api/v1/access_token").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Authorization", "Basic $basicAuth")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("User-Agent", REDDIT_OAUTH_USER_AGENT)
                }

                val tokenBody = try {
                    tokenConn.outputStream.use { it.write(formBody.toByteArray(StandardCharsets.UTF_8)) }
                    val status = tokenConn.responseCode
                    val stream = if (status in 200..299) tokenConn.inputStream else tokenConn.errorStream
                    val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) {
                        throw IllegalStateException("Reddit OAuth HTTP $status")
                    }
                    body
                } finally {
                    tokenConn.disconnect()
                }

                val tokenJson = JSONObject(tokenBody)
                val accessToken = tokenJson.optString("access_token").trim()
                val refreshToken = tokenJson.optString("refresh_token").trim().takeIf { it.isNotEmpty() }
                val expiresInSec = tokenJson.optLong("expires_in", 3600L).coerceAtLeast(60L)
                if (accessToken.isEmpty()) {
                    throw IllegalStateException("access_token vazio na resposta do Reddit")
                }

                val username = fetchRedditUsername(accessToken)
                val expiresAtEpochMs = System.currentTimeMillis() + (expiresInSec * 1000L)
                val updatedAt = SecureVault(applicationContext).savePlatformOAuthSession(
                    platform = PLATFORM_REDDIT,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    clientId = clientId,
                    username = username,
                    expiresAtEpochMs = expiresAtEpochMs,
                    source = "oauth_pkce",
                )

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    sessionSaved = true
                    setResult(
                        Activity.RESULT_OK,
                        Intent().apply {
                            putExtra(EXTRA_SAVED, true)
                            putExtra(EXTRA_PLATFORM, PLATFORM_REDDIT)
                            putExtra(EXTRA_UPDATED_AT, updatedAt)
                            putExtra(
                                EXTRA_MESSAGE,
                                if (!username.isNullOrBlank()) {
                                    "Conta Reddit (/u/$username) conectada via OAuth2 PKCE."
                                } else {
                                    "Conta Reddit conectada via OAuth2 PKCE."
                                },
                            )
                        },
                    )
                    finish()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    oauthExchangeInFlight = false
                    if (!isFinishing && !isDestroyed) {
                        statusLabel.text = "Falha no OAuth2 do Reddit: ${e.message ?: "erro ao trocar código"}"
                    }
                }
            }
        }
    }

    private fun fetchRedditUsername(accessToken: String): String? {
        return try {
            val meConn = (URL("https://oauth.reddit.com/api/v1/me").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("User-Agent", REDDIT_OAUTH_USER_AGENT)
            }
            try {
                if (meConn.responseCode in 200..299) {
                    val body = meConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    JSONObject(body).optString("name").trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            } finally {
                meConn.disconnect()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun finishWithError(message: String) {
        setResult(
            Activity.RESULT_CANCELED,
            Intent().apply {
                putExtra(EXTRA_SAVED, false)
                putExtra(EXTRA_PLATFORM, platform)
                putExtra(EXTRA_MESSAGE, message)
            },
        )
        finish()
    }

    private fun tryCaptureSession(force: Boolean): Boolean {
        if (sessionSaved) return true
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val ua = if (::webView.isInitialized) webView.settings.userAgentString else null

        val updatedAt: String = when (platform) {
            PLATFORM_INSTAGRAM -> {
                val rawCookies = mergeCookieStrings(
                    cookieManager.getCookie(INSTAGRAM_BASE_URL),
                    cookieManager.getCookie("https://i.instagram.com"),
                )
                if (!SecureVault.hasCookieKey(rawCookies, "sessionid")) {
                    return false
                }
                SecureVault(applicationContext).saveInstagramSession(
                    cookieHeader = rawCookies,
                    userAgent = ua,
                    source = "webview",
                )
            }
            PLATFORM_THREADS -> {
                val merged = mergeCookieStrings(
                    cookieManager.getCookie(THREADS_NET_BASE_URL),
                    cookieManager.getCookie(THREADS_COM_BASE_URL),
                )
                if (!SecureVault.hasCookieKey(merged, "sessionid")) {
                    return false
                }
                SecureVault(applicationContext).savePlatformCookieSession(
                    platform = PLATFORM_THREADS,
                    cookieHeader = merged,
                    userAgent = ua,
                    source = "webview",
                )
            }
            PLATFORM_REDDIT -> {
                val merged = mergeCookieStrings(
                    cookieManager.getCookie(REDDIT_BASE_URL),
                    cookieManager.getCookie(REDDIT_OAUTH_BASE_URL),
                    cookieManager.getCookie("https://reddit.com"),
                    cookieManager.getCookie("https://old.reddit.com"),
                )
                val currentUrl = if (::webView.isInitialized) webView.url.orEmpty() else ""
                val onRedditAuthPage = currentUrl.contains("/login", ignoreCase = true) ||
                    currentUrl.contains("/register", ignoreCase = true)
                val manualFallbackAllowed = force &&
                    merged.isNotBlank() &&
                    isHostMatch(currentUrl, "reddit.com") &&
                    !onRedditAuthPage
                if (!isAuthenticatedRedditCookie(merged) && !manualFallbackAllowed) {
                    return false
                }
                SecureVault(applicationContext).savePlatformCookieSession(
                    platform = PLATFORM_REDDIT,
                    cookieHeader = merged,
                    userAgent = ua,
                    source = "webview",
                )
            }
            else -> return false
        }

        sessionSaved = true
        mainHandler.removeCallbacks(sessionPollRunnable)
        val label = when (platform) {
            PLATFORM_REDDIT -> "Reddit"
            PLATFORM_THREADS -> "Threads"
            else -> "Instagram"
        }
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_SAVED, true)
                putExtra(EXTRA_PLATFORM, platform)
                putExtra(EXTRA_UPDATED_AT, updatedAt)
                putExtra(EXTRA_MESSAGE, "Conta do $label conectada com sucesso!")
            },
        )
        finish()
        return true
    }

    private fun isHostMatch(url: String, rootDomain: String): Boolean {
        val host = try {
            URI(url).host?.lowercase()?.trimEnd('.')
        } catch (_: Exception) {
            null
        } ?: return false
        return host == rootDomain || host.endsWith(".$rootDomain")
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        const val PLATFORM_INSTAGRAM = "instagram"
        const val PLATFORM_REDDIT = "reddit"
        const val PLATFORM_THREADS = "threads"
        val SUPPORTED_PLATFORMS = setOf(PLATFORM_INSTAGRAM, PLATFORM_REDDIT, PLATFORM_THREADS)

        const val INSTAGRAM_BASE_URL = "https://www.instagram.com"
        const val INSTAGRAM_LOGIN_URL = "https://www.instagram.com/accounts/login/"

        const val THREADS_NET_BASE_URL = "https://www.threads.net"
        const val THREADS_COM_BASE_URL = "https://www.threads.com"
        const val THREADS_LOGIN_URL = "https://www.threads.net/login"

        const val REDDIT_BASE_URL = "https://www.reddit.com"
        const val REDDIT_OAUTH_BASE_URL = "https://oauth.reddit.com"
        const val REDDIT_LOGIN_URL = "https://www.reddit.com/login/"
        const val REDDIT_OAUTH_REDIRECT_URI = "https://localhost/devwolf-oauth-callback"
        private const val REDDIT_OAUTH_USER_AGENT = "android:com.devwolf.downloader:v3.0.0-rc.11 (by /u/devwolf-app)"
        private const val SESSION_POLL_INTERVAL_MS = 1200L

        private const val COLOR_BG_DEEP = 0xFF0D0B1E.toInt()
        private const val COLOR_SURFACE_HEADER = 0xFF14112E.toInt()
        private const val COLOR_SURFACE_CARD = 0xFF1E1940.toInt()
        private const val COLOR_ACCENT_PURPLE = 0xFF7C5CFF.toInt()
        private const val COLOR_LAVENDER_TEXT = 0xFFC4B5FD.toInt()
        private const val COLOR_LAVENDER_MUTED = 0xFFB8B3D8.toInt()

        const val EXTRA_PLATFORM = "extra_platform"
        const val EXTRA_REDDIT_CLIENT_ID = "extra_reddit_client_id"
        const val EXTRA_SAVED = "extra_saved"
        const val EXTRA_UPDATED_AT = "extra_updated_at"
        const val EXTRA_MESSAGE = "extra_message"

        private val URL_SAFE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        private fun generateRandomUrlSafe(length: Int): String {
            val rng = SecureRandom()
            return buildString(length) {
                repeat(length) {
                    append(URL_SAFE_CHARS[rng.nextInt(URL_SAFE_CHARS.length)])
                }
            }
        }

        private fun urlEncode(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }

        internal fun isAuthenticatedRedditCookie(cookieHeader: String?): Boolean {
            if (cookieHeader.isNullOrBlank()) return false
            if (SecureVault.hasCookieKey(cookieHeader, "reddit_session")) {
                return true
            }
            val tokenV2 = SecureVault.extractCookieValue(cookieHeader, "token_v2") ?: return false
            val parts = tokenV2.split('.')
            if (parts.size < 2) return false
            val payloadBytes = decodeBase64Url(parts[1]) ?: return false
            val payloadStr = String(payloadBytes, StandardCharsets.UTF_8)
            val sub = try {
                JSONObject(payloadStr).optString("sub").trim()
            } catch (_: Exception) {
                ""
            }
            return sub.startsWith("t2_", ignoreCase = true) ||
                payloadStr.contains("\"sub\":\"t2_", ignoreCase = true)
        }

        private fun base64Val(ch: Char): Int = when (ch) {
            in 'A'..'Z' -> ch - 'A'
            in 'a'..'z' -> ch - 'a' + 26
            in '0'..'9' -> ch - '0' + 52
            '+' -> 62
            '/' -> 63
            else -> -1
        }

        private fun decodeBase64Url(input: String): ByteArray? {
            val clean = input.trim().replace('-', '+').replace('_', '/')
            if (clean.isEmpty()) return null
            val padded = when (clean.length % 4) {
                0 -> clean
                2 -> "$clean=="
                3 -> "$clean="
                else -> return null
            }
            val out = ByteArray((padded.length * 3) / 4)
            var outIdx = 0
            var i = 0
            while (i < padded.length) {
                val c0 = base64Val(padded[i++])
                val c1 = base64Val(padded[i++])
                val p2 = padded[i++]
                val p3 = padded[i++]
                if (c0 < 0 || c1 < 0) return null
                val c2 = if (p2 == '=') 0 else base64Val(p2).also { if (it < 0) return null }
                val c3 = if (p3 == '=') 0 else base64Val(p3).also { if (it < 0) return null }
                val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
                out[outIdx++] = ((triple shr 16) and 0xFF).toByte()
                if (p2 != '=') out[outIdx++] = ((triple shr 8) and 0xFF).toByte()
                if (p3 != '=') out[outIdx++] = (triple and 0xFF).toByte()
            }
            return out.copyOf(outIdx)
        }

        internal fun mergeCookieStrings(vararg rawHeaders: String?): String {
            val map = linkedMapOf<String, String>()
            for (raw in rawHeaders) {
                if (raw.isNullOrBlank()) continue
                for (part in raw.split(';')) {
                    val trimmed = part.trim()
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) continue
                    val key = trimmed.substring(0, eq).trim()
                    val value = trimmed.substring(eq + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        map[key] = value
                    }
                }
            }
            return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        /**
         * Performs isolated one-tap logout for a single platform: expires ONLY that platform's
         * WebView cookies without touching other platforms' sessions, and revokes its encrypted
         * credentials in [SecureVault].
         */
        fun clearWebViewSession(context: Context, platform: String = PLATFORM_INSTAGRAM): Boolean {
            val normalized = platform.trim().lowercase()
            val domainTargets = when (normalized) {
                PLATFORM_INSTAGRAM -> listOf(
                    "https://www.instagram.com" to ".instagram.com",
                    "https://i.instagram.com" to ".instagram.com",
                    "https://instagram.com" to "instagram.com",
                )
                PLATFORM_REDDIT -> listOf(
                    "https://www.reddit.com" to ".reddit.com",
                    "https://old.reddit.com" to ".reddit.com",
                    "https://oauth.reddit.com" to ".reddit.com",
                    "https://reddit.com" to "reddit.com",
                )
                PLATFORM_THREADS -> listOf(
                    "https://www.threads.net" to ".threads.net",
                    "https://threads.net" to "threads.net",
                    "https://www.threads.com" to ".threads.com",
                    "https://threads.com" to "threads.com",
                )
                else -> emptyList()
            }
            try {
                val cm = CookieManager.getInstance()
                for ((url, domain) in domainTargets) {
                    val raw = cm.getCookie(url) ?: continue
                    for (part in raw.split(';')) {
                        val eq = part.indexOf('=')
                        if (eq <= 0) continue
                        val name = part.substring(0, eq).trim()
                        if (name.isEmpty()) continue
                        cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Path=/")
                        cm.setCookie(url, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0; Domain=$domain; Path=/")
                    }
                }
                cm.flush()
            } catch (_: Throwable) {
            }
            return SecureVault(context.applicationContext).revokePlatformSession(normalized)
        }
    }
}
