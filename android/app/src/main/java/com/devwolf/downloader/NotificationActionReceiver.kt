package com.devwolf.downloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Handles Cancel, Share to Status/App, and Open actions triggered from Android notifications.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CANCEL_JOB = "com.devwolf.downloader.notif.CANCEL_JOB"
        const val ACTION_SHARE_MEDIA = "com.devwolf.downloader.notif.SHARE_MEDIA"
        const val ACTION_OPEN_MEDIA = "com.devwolf.downloader.notif.OPEN_MEDIA"

        const val EXTRA_URIS = "extra_uris"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CANCEL_JOB -> {
                val jobId = intent.getStringExtra(DevwolfDownloadService.EXTRA_JOB_ID).orEmpty()
                if (jobId.isNotBlank()) {
                    DevwolfDownloadService.cancelDownload(context, jobId)
                }
            }
            ACTION_SHARE_MEDIA -> {
                val uris = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty().mapNotNull {
                    try { Uri.parse(it) } catch (_: Exception) { null }
                }
                if (uris.isNotEmpty()) {
                    val mime = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"
                    val share = MediaStoreHelper.buildShareIntent(context, uris, mime, "Devwolf Downloader", null, null)
                    share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(share)
                }
            }
            ACTION_OPEN_MEDIA -> {
                val uriStr = intent.getStringExtra(EXTRA_URI) ?: return
                val mime = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"
                try {
                    MediaStoreHelper.openMedia(context, Uri.parse(uriStr), mime)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
