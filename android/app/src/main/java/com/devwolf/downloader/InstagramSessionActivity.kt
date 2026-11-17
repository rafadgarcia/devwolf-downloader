package com.devwolf.downloader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.URI

/**
 * Optional, user-initiated WebView login screen for Instagram.
 * Never captures passwords or injects keyloggers; reads standard WebView cookies only for
 * `*.instagram.com` once `sessionid` is present and persists them encrypted in [SecureVault].
 */
class InstagramSessionActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLabel: TextView
    private var sessionSaved = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101012"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1C20"))
            val pad = dp(12)
            setPadding(pad, dp(14), pad, dp(10))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "Sessão opcional do Instagram"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val cancelBtn = Button(this).apply {
            text = "Cancelar"
            setTextColor(Color.parseColor("#D4D4D8"))
            setBackgroundColor(Color.TRANSPARENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                setResult(
                    Activity.RESULT_CANCELED,
                    Intent().apply {
                        putExtra(EXTRA_SAVED, false)
                        putExtra(EXTRA_MESSAGE, "Login cancelado pelo usuário.")
                    },
                )
                finish()
            }
        }

        val doneBtn = Button(this).apply {
            text = "Concluir"
            setTextColor(Color.parseColor("#101012"))
            setBackgroundColor(Color.parseColor("#A3E635"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener {
                if (!tryCaptureSession(force = true)) {
                    statusLabel.text = "Faça login primeiro até aparecer seu feed antes de concluir."
                }
            }
        }

        topRow.addView(titleView)
        topRow.addView(cancelBtn)
        topRow.addView(doneBtn)

        statusLabel = TextView(this).apply {
            text = "Mídias públicas não exigem login. Cookies ficam criptografados só no aparelho."
            setTextColor(Color.parseColor("#A1A1AA"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(4), 0, 0)
        }

        header.addView(topRow)
        header.addView(statusLabel)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
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
                if (url != null && isInstagramHost(url)) {
                    val isLoginPage = url.contains("/accounts/login", ignoreCase = true)
                    if (!isLoginPage) {
                        tryCaptureSession(force = false)
                    }
                }
            }
        }

        root.addView(header)
        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)

        webView.loadUrl(INSTAGRAM_LOGIN_URL)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun tryCaptureSession(force: Boolean): Boolean {
        if (sessionSaved) return true
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val rawCookies = cookieManager.getCookie(INSTAGRAM_BASE_URL)
        if (!SecureVault.hasCookieKey(rawCookies, "sessionid")) {
            return false
        }
        if (!force && rawCookies.isNullOrBlank()) return false

        val ua = if (::webView.isInitialized) webView.settings.userAgentString else null
        val updatedAt = SecureVault(applicationContext).saveInstagramSession(
            cookieHeader = rawCookies!!,
            userAgent = ua,
            source = "webview",
        )
        sessionSaved = true
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_SAVED, true)
                putExtra(EXTRA_UPDATED_AT, updatedAt)
                putExtra(EXTRA_MESSAGE, "Sessão do Instagram salva localmente com criptografia.")
            },
        )
        finish()
        return true
    }

    private fun isInstagramHost(url: String): Boolean {
        val host = try {
            URI(url).host?.lowercase()?.trimEnd('.')
        } catch (_: Exception) {
            null
        } ?: return false
        return host == "instagram.com" || host.endsWith(".instagram.com")
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        const val INSTAGRAM_BASE_URL = "https://www.instagram.com"
        const val INSTAGRAM_LOGIN_URL = "https://www.instagram.com/accounts/login/"

        const val EXTRA_SAVED = "extra_saved"
        const val EXTRA_UPDATED_AT = "extra_updated_at"
        const val EXTRA_MESSAGE = "extra_message"

        /**
         * Performs full one-tap logout: clears WebView cookies, WebStorage, and revokes the
         * encrypted session stored in [SecureVault].
         */
        fun clearWebViewSession(context: Context): Boolean {
            try {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
            } catch (_: Throwable) {
            }
            try {
                WebStorage.getInstance().deleteAllData()
            } catch (_: Throwable) {
            }
            return SecureVault(context.applicationContext).revokeSession("instagram")
        }
    }
}
