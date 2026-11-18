package com.devwolf.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.BridgeActivity
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(DevwolfNativePlugin::class.java)
        super.onCreate(savedInstanceState)
        applyPurpleSystemChrome()
        registerHardwareBackHandler()
        requestNotificationPermissionIfNeeded()
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
