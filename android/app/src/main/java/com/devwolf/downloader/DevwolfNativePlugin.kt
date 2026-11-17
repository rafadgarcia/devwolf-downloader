package com.devwolf.downloader

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Capacitor Native Bridge for Devwolf Downloader (`DevwolfNative`).
 * Exposes local yt-dlp/FFmpeg engine, Status Mode, MediaStore gallery integration,
 * native Share receiver, engine updater/rollback, and Keystore-encrypted cookie vault.
 */
@CapacitorPlugin(name = "DevwolfNative")
class DevwolfNativePlugin : Plugin() {

    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var queueStore: LocalQueueStore
    private lateinit var secureVault: SecureVault

    private val progressListener: (JSONObject) -> Unit = { snapshot ->
        try {
            notifyListeners("localDownloadProgress", JSObject(snapshot.toString()), true)
        } catch (_: Throwable) {
        }
    }

    override fun load() {
        super.load()
        val appCtx = context.applicationContext
        queueStore = LocalQueueStore(appCtx)
        secureVault = SecureVault(appCtx)
        queueStore.markInterruptedJobsOnStartup()
        DevwolfDownloadService.addProgressListener(progressListener)
        instance = this
    }

    override fun handleOnDestroy() {
        DevwolfDownloadService.removeProgressListener(progressListener)
        pluginScope.cancel()
        if (instance === this) {
            instance = null
        }
        super.handleOnDestroy()
    }

    fun emitShareIntent(payload: JSONObject) {
        try {
            notifyListeners("shareIntentReceived", JSObject(payload.toString()), true)
        } catch (_: Throwable) {
        }
    }

    @PluginMethod
    fun readClipboard(call: PluginCall) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val raw = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString()
            } else {
                null
            }
            val extracted = UrlExtractor.extractFirstValidUrl(raw)
            call.resolve(
                JSObject().apply {
                    put("text", extracted ?: JSONObject.NULL)
                },
            )
        } catch (_: Throwable) {
            call.resolve(JSObject().apply { put("text", JSONObject.NULL) })
        }
    }

    @PluginMethod
    fun getPendingShareIntent(call: PluginCall) {
        val pending = queueStore.getPendingShareIntent()
        val result = JSObject()
        if (pending != null) {
            result.put("intent", JSObject(pending.toString()))
        } else {
            result.put("intent", JSONObject.NULL)
        }
        call.resolve(result)
    }

    @PluginMethod
    fun clearPendingShareIntent(call: PluginCall) {
        val id = call.getString("id")
        queueStore.clearPendingShareIntent(id)
        call.resolve()
    }

    @PluginMethod
    fun returnToSourceApp(call: PluginCall) {
        val act = activity
        val returned = if (act != null) {
            act.runOnUiThread { act.moveTaskToBack(true) }
            true
        } else {
            false
        }
        call.resolve(JSObject().apply { put("returned", returned) })
    }

    @PluginMethod
    fun startLocalDownload(call: PluginCall) {
        val url = call.getString("url")?.trim().orEmpty()
        if (!UrlExtractor.isSafePublicHttpUrl(url)) {
            call.reject("URL inválida ou não permitida pela política de segurança.")
            return
        }
        val jobId = call.getString("jobId")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val payload = JSONObject().apply {
            put("jobId", jobId)
            put("url", url)
            put("format", call.getString("format", "auto"))
            put("quality", call.getString("quality", "best"))
            put("statusMode", call.getBoolean("statusMode", false))
            val seg = call.getInt("segmentDurationSeconds")
            put("segmentDurationSeconds", seg ?: JSONObject.NULL)
            put("keepOriginal", call.getBoolean("keepOriginal", false))
        }
        DevwolfDownloadService.enqueueDownload(context.applicationContext, payload)
        call.resolve(
            JSObject().apply {
                put("jobId", jobId)
                put("status", "queued")
            },
        )
    }

    @PluginMethod
    fun cancelLocalDownload(call: PluginCall) {
        val jobId = call.getString("jobId").orEmpty()
        if (jobId.isBlank()) {
            call.reject("jobId obrigatório")
            return
        }
        DevwolfDownloadService.cancelDownload(context.applicationContext, jobId)
        call.resolve(JSObject().apply { put("cancelled", true) })
    }

    @PluginMethod
    fun getLocalDownloadQueue(call: PluginCall) {
        val jobs = queueStore.listJobs()
        call.resolve(
            JSObject().apply {
                put("jobs", JSArray(jobs.toString()))
            },
        )
    }

    @PluginMethod
    fun saveToGallery(call: PluginCall) {
        val filePath = call.getString("filePath").orEmpty()
        val mimeType = call.getString("mimeType") ?: StatusModeProcessor.guessMimeType(filePath)
        val title = call.getString("title")
        pluginScope.launch {
            try {
                val file = File(filePath)
                val saved = MediaStoreHelper.saveFileToGallery(context.applicationContext, file, mimeType, title)
                call.resolve(
                    JSObject().apply {
                        put("mediaUri", saved.mediaUri)
                        put("displayPath", saved.displayPath)
                        put("fileName", saved.fileName)
                        put("sizeBytes", saved.sizeBytes)
                    },
                )
            } catch (e: Throwable) {
                call.reject("Falha ao salvar na galeria: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun downloadRemoteFileToGallery(call: PluginCall) {
        val url = call.getString("url").orEmpty()
        if (url.isBlank()) {
            call.reject("URL remota obrigatória")
            return
        }
        val headersObj = call.getObject("headers")
        val preferredName = call.getString("fileName") ?: "devwolf_remote.mp4"
        val mimeHint = call.getString("mimeType") ?: StatusModeProcessor.guessMimeType(preferredName)
        val statusMode = call.getBoolean("statusMode", false) ?: false
        val segDuration = call.getInt("segmentDurationSeconds")
        val keepOriginal = call.getBoolean("keepOriginal", false) ?: false

        pluginScope.launch {
            val tempDir = File(context.cacheDir, "remote_${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val safeTempName = MediaStoreHelper.sanitizeFileName(preferredName, "mp4")
                val destFile = File(tempDir, safeTempName)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    if (headersObj != null) {
                        val keys = headersObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = headersObj.optString(k)
                            if (v.isNotBlank()) setRequestProperty(k, v)
                        }
                    }
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code ao baixar arquivo da VPS")
                    }
                    conn.inputStream.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                } finally {
                    conn.disconnect()
                }

                val items = if ((statusMode || (segDuration != null && segDuration > 0)) && mimeHint.startsWith("video/")) {
                    LocalEngineManager.ensureInitialized(context.applicationContext)
                    StatusModeProcessor.processForStatusMode(
                        context = context.applicationContext,
                        sourceFile = destFile,
                        workDir = tempDir,
                        segmentDurationSeconds = segDuration,
                        keepOriginal = keepOriginal,
                    )
                } else {
                    listOf(
                        ProcessedMediaItem(
                            file = destFile,
                            mimeType = mimeHint,
                            durationSeconds = null,
                            isSegment = false,
                            segmentIndex = null,
                            isOriginal = true,
                        ),
                    )
                }

                val outArr = JSONArray()
                items.forEachIndexed { idx, item ->
                    val saved = MediaStoreHelper.saveFileToGallery(
                        context = context.applicationContext,
                        sourceFile = item.file,
                        mimeType = item.mimeType,
                        preferredName = item.file.name,
                    )
                    outArr.put(
                        JSONObject().apply {
                            put("id", "remote_${System.currentTimeMillis()}_$idx")
                            put("fileName", saved.fileName)
                            put("filePath", "")
                            put("mediaUri", saved.mediaUri)
                            put("displayPath", saved.displayPath)
                            put("mimeType", item.mimeType)
                            put("sizeBytes", saved.sizeBytes)
                            put("durationSeconds", item.durationSeconds ?: JSONObject.NULL)
                            put("isSegment", item.isSegment)
                            put("segmentIndex", item.segmentIndex ?: JSONObject.NULL)
                            put("isOriginal", item.isOriginal)
                        },
                    )
                }

                call.resolve(JSObject().apply { put("files", JSArray(outArr.toString())) })
            } catch (e: Throwable) {
                call.reject("Erro ao transferir mídia da VPS para a galeria: ${e.message}")
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }

    @PluginMethod
    fun shareMedia(call: PluginCall) {
        val urisArr = call.getArray("uris")
        val pathsArr = call.getArray("filePaths")
        val uris = mutableListOf<Uri>()
        if (urisArr != null) {
            for (i in 0 until urisArr.length()) {
                val u = MediaStoreHelper.resolveSharableUri(context, urisArr.optString(i), null)
                if (u != null) uris.add(u)
            }
        }
        if (uris.isEmpty() && pathsArr != null) {
            for (i in 0 until pathsArr.length()) {
                val u = MediaStoreHelper.resolveSharableUri(context, null, pathsArr.optString(i))
                if (u != null) uris.add(u)
            }
        }
        if (uris.isEmpty()) {
            call.reject("Nenhuma mídia válida para compartilhar.")
            return
        }
        val mime = call.getString("mimeType") ?: "video/*"
        val title = call.getString("title")
        val text = call.getString("text")
        val targetPackage = call.getString("targetPackage")
        val intent = MediaStoreHelper.buildShareIntent(context, uris, mime, title, text, targetPackage)
        activity?.runOnUiThread {
            activity?.startActivity(intent)
        }
        call.resolve(JSObject().apply { put("launched", true) })
    }

    @PluginMethod
    fun openMedia(call: PluginCall) {
        val uri = MediaStoreHelper.resolveSharableUri(
            context,
            call.getString("uri"),
            call.getString("filePath"),
        )
        if (uri == null) {
            call.reject("Arquivo não encontrado para abrir.")
            return
        }
        val mime = call.getString("mimeType") ?: "video/*"
        try {
            MediaStoreHelper.openMedia(context, uri, mime)
            call.resolve(JSObject().apply { put("opened", true) })
        } catch (e: Throwable) {
            call.reject("Nenhum aplicativo compatível encontrado para abrir esta mídia: ${e.message}")
        }
    }

    @PluginMethod
    fun deleteMedia(call: PluginCall) {
        val deleted = MediaStoreHelper.deleteMedia(
            context.applicationContext,
            call.getString("uri"),
            call.getString("filePath"),
        )
        call.resolve(JSObject().apply { put("deleted", deleted) })
    }

    @PluginMethod
    fun getLocalEngineStatus(call: PluginCall) {
        pluginScope.launch {
            val status = LocalEngineManager.getStatus(context.applicationContext, checkRemote = true)
            call.resolve(JSObject(status.toString()))
        }
    }

    @PluginMethod
    fun updateLocalEngine(call: PluginCall) {
        val channel = call.getString("channel", "stable")
        pluginScope.launch {
            val res = LocalEngineManager.updateEngine(context.applicationContext, channel)
            call.resolve(JSObject(res.toString()))
        }
    }

    @PluginMethod
    fun rollbackLocalEngine(call: PluginCall) {
        pluginScope.launch {
            val res = LocalEngineManager.rollbackEngine(context.applicationContext)
            call.resolve(JSObject(res.toString()))
        }
    }

    @PluginMethod
    fun clearTempCache(call: PluginCall) {
        pluginScope.launch {
            var freed = 0L
            var removed = 0
            val jobsDir = File(context.cacheDir, "jobs")
            if (jobsDir.exists()) {
                jobsDir.walkBottomUp().forEach { f ->
                    if (f.isFile) {
                        freed += f.length()
                        if (f.delete()) removed++
                    } else if (f != jobsDir) {
                        f.delete()
                    }
                }
            }
            context.cacheDir.listFiles()?.filter { it.name.startsWith("remote_") }?.forEach { dir ->
                dir.walkBottomUp().forEach { f ->
                    if (f.isFile) {
                        freed += f.length()
                        if (f.delete()) removed++
                    } else {
                        f.delete()
                    }
                }
            }
            call.resolve(
                JSObject().apply {
                    put("freedBytes", freed)
                    put("filesRemoved", removed)
                },
            )
        }
    }

    @PluginMethod
    fun getPlatformSessions(call: PluginCall) {
        val arr = secureVault.getPlatformSessions()
        call.resolve(JSObject().apply { put("sessions", JSArray(arr.toString())) })
    }

    @PluginMethod
    fun savePlatformSession(call: PluginCall) {
        val platform = call.getString("platform").orEmpty()
        val cookieHeader = call.getString("cookieHeader").orEmpty()
        try {
            val updatedAt = secureVault.savePlatformSession(platform, cookieHeader)
            call.resolve(
                JSObject().apply {
                    put("saved", true)
                    put("platform", platform.trim().lowercase())
                    put("updatedAt", updatedAt)
                },
            )
        } catch (e: Throwable) {
            call.reject(e.message ?: "Erro ao salvar sessão local")
        }
    }

    @PluginMethod
    fun revokePlatformSession(call: PluginCall) {
        val platform = call.getString("platform").orEmpty()
        val revoked = secureVault.revokePlatformSession(platform)
        call.resolve(
            JSObject().apply {
                put("revoked", revoked)
                put("platform", platform.trim().lowercase())
            },
        )
    }

    companion object {
        @Volatile
        var instance: DevwolfNativePlugin? = null
            private set
    }
}
