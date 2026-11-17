package com.devwolf.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getcapacitor.BridgeActivity
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class MainActivity : BridgeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(DevwolfNativePlugin::class.java)
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        handleIncomingIntent(intent)
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
