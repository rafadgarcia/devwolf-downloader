package com.devwolf.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.BridgeActivity
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class MainActivity : BridgeActivity() {

    private var clipboardBubble: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(DevwolfNativePlugin::class.java)
        super.onCreate(savedInstanceState)
        applyPurpleSystemChrome()
        registerHardwareBackHandler()
        requestNotificationPermissionIfNeeded()
        showClipboardBubbleIfEnabled()
        handleIncomingIntent(intent)
    }

    private fun registerHardwareBackHandler() {
        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = bridge?.webView
                if (wv == null) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                    return
                }
                val js = """
                    (function() {
                      try {
                        if (typeof window.__devwolfHandleBack === 'function') {
                          return window.__devwolfHandleBack() ? '1' : '0';
                        }
                        var ev = new CustomEvent('devwolf:hardwareBack', { cancelable: true });
                        var notCancelled = window.dispatchEvent(ev);
                        return notCancelled ? '0' : '1';
                      } catch (e) {
                        return '0';
                      }
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js) { result ->
                    val handled = result == "\"1\"" || result == "1" || result == "true"
                    if (handled) return@evaluateJavascript
                    runOnUiThread {
                        if (wv.canGoBack()) {
                            wv.goBack()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            isEnabled = true
                        }
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
    }

    @Suppress("DEPRECATION")
    private fun applyPurpleSystemChrome() {
        val deepViolet = Color.parseColor("#0D0B1E")
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setBackgroundDrawable(ColorDrawable(deepViolet))
            statusBarColor = deepViolet
            navigationBarColor = deepViolet
            WindowInsetsControllerCompat(this, decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        bridge?.webView?.setBackgroundColor(deepViolet)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        showClipboardBubbleIfEnabled()
    }

    override fun onDestroy() {
        removeClipboardBubble()
        super.onDestroy()
    }

    private fun showClipboardBubbleIfEnabled() {
        val prefs = getSharedPreferences("devwolf_features", MODE_PRIVATE)
        if (prefs.getBoolean("clipboard_bubble_enabled", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            if (clipboardBubble != null) return
            val bubble = TextView(this).apply {
                text = "DW"
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                contentDescription = "Verificar link copiado no Devwolf"
                setBackgroundColor(Color.parseColor("#7C5CFF"))
                elevation = 12f
                setOnClickListener {
                    val plugin = DevwolfNativePlugin.instance
                    if (plugin != null) plugin.emitClipboardBubbleTapped()
                    else {
                        val launch = packageManager.getLaunchIntentForPackage(packageName)
                        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        if (launch != null) startActivity(launch)
                    }
                }
            }
            val params = WindowManager.LayoutParams(
                dp(54), dp(54),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; x = dp(8) }
            try {
                getSystemService(WindowManager::class.java).addView(bubble, params)
                clipboardBubble = bubble
            } catch (_: SecurityException) {
                clipboardBubble = null
            }
        } else {
            removeClipboardBubble()
        }
    }

    private fun removeClipboardBubble() {
        clipboardBubble?.let { bubble ->
            runCatching { getSystemService(WindowManager::class.java).removeView(bubble) }
        }
        clipboardBubble = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null || incoming.action != Intent.ACTION_SEND) return
        val rawText = incoming.getStringExtra(Intent.EXTRA_TEXT)
            ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        val subject = incoming.getStringExtra(Intent.EXTRA_SUBJECT)
        val extractedUrl = UrlExtractor.extractFirstValidUrl(rawText) ?: return

        val payload = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("url", extractedUrl)
            put("rawText", rawText ?: "")
            put("title", subject ?: JSONObject.NULL)
            put("receivedAt", Instant.now().toString())
            put("autoStart", true)
        }

        LocalQueueStore(applicationContext).savePendingShareIntent(payload)
        DevwolfNativePlugin.instance?.emitShareIntent(payload)
    }
}
