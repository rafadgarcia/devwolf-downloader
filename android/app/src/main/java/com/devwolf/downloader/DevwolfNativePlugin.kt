package com.devwolf.downloader

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Size
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

internal fun whatsappStatusesToJsArray(items: JSONArray): JSArray = JSArray(items.toString())

/**
 * Capacitor Native Bridge for Devwolf Downloader (`DevwolfNative`).
 * Exposes local DNR + yt-dlp/FFmpeg + gallery-dl engines, Status Mode, individual carousel
 * MediaStore integration, Share receiver, engine updater/rollback, WebView Instagram session,
 * Keystore-encrypted cookie vault, and local performance diagnostics.
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
        queueStore.markInterruptedJobsOnStartup(isStillRunning = { jobId -> DevwolfDownloadService.isJobTracked(jobId) })
        DevwolfDownloadService.addProgressListener(progressListener)
        instance = this
        PerformanceTracker.markAppOpenCompleted()

        // Drop downloaded app-update APKs that were already installed (or are stale).
        pluginScope.launch {
            try {
                AppUpdateManager.cleanupObsoleteDownloads(appCtx)
            } catch (_: Throwable) {
            }
        }

        // Non-blocking first-init / 24h yt-dlp update check delayed by 15s so startup/first download never race
        pluginScope.launch {
            delay(15_000L)
            LocalEngineManager.maybeAutoUpdateInBackground(appCtx)
        }
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
        val startMs = System.currentTimeMillis()
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            val raw = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString()
            } else {
                null
            }
            val extracted = UrlExtractor.extractFirstValidUrl(raw)
            PerformanceTracker.recordClipboardDetect(System.currentTimeMillis() - startMs)
            call.resolve(
                JSObject().apply {
                    put("text", extracted ?: JSONObject.NULL)
                },
            )
        } catch (_: Throwable) {
            PerformanceTracker.recordClipboardDetect(System.currentTimeMillis() - startMs)
            call.resolve(JSObject().apply { put("text", JSONObject.NULL) })
        }
    }

    fun emitClipboardBubbleTapped() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val raw = runCatching {
            cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        val url = UrlExtractor.extractFirstValidUrl(raw)
        val intent = if (url != null) JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("url", url)
            put("rawText", "")
            put("title", JSONObject.NULL)
            put("receivedAt", java.time.Instant.now().toString())
            put("autoStart", true)
        } else JSONObject().apply { put("error", "Nenhum link compatível encontrado na área de transferência.") }
        try {
            if (activity != null && !activity!!.isFinishing) {
                activity!!.runOnUiThread {
                    notifyListeners("clipboardBubbleResult", JSObject(intent.toString()), true)
                }
            } else {
                LocalQueueStore(context.applicationContext).savePendingShareIntent(intent)
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (launch != null) context.startActivity(launch)
            }
        } catch (_: Throwable) { }
    }

    @PluginMethod
    fun setClipboardBubbleEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        context.getSharedPreferences("devwolf_features", Context.MODE_PRIVATE)
            .edit().putBoolean("clipboard_bubble_enabled", enabled).apply()
        val act = activity
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(context)) {
            if (act == null) { call.reject("Abra o Devwolf no Android para autorizar a bolha."); return }
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            startActivityForResult(call, intent, "handleClipboardOverlayPermission")
        } else {
            if (enabled) ClipboardBubbleService.start(context) else ClipboardBubbleService.stop(context)
            call.resolve(JSObject().apply { put("enabled", enabled) })
        }
    }

    @ActivityCallback
    private fun handleClipboardOverlayPermission(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(context)
        context.getSharedPreferences("devwolf_features", Context.MODE_PRIVATE).edit()
            .putBoolean("clipboard_bubble_enabled", granted).apply()
        if (granted) ClipboardBubbleService.start(context)
        call.resolve(JSObject().apply { put("enabled", granted) })
    }

    @PluginMethod
    fun getClipboardBubbleStatus(call: PluginCall) {
        val requested = context.getSharedPreferences("devwolf_features", Context.MODE_PRIVATE)
            .getBoolean("clipboard_bubble_enabled", false)
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)
        call.resolve(JSObject().apply { put("enabled", requested && allowed); put("permissionGranted", allowed) })
    }

    @PluginMethod
    fun pickWhatsAppStatusesFolder(call: PluginCall) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            // This is only a starting location hint. SAF still requires an explicit user selection
            // and grant; providers/devices that cannot resolve it may open their normal location.
            if (call.getBoolean("defaultLocation", false) == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
                    ),
                )
            }
        }
        startActivityForResult(call, intent, "handleWhatsAppStatusesFolder")
    }

    @ActivityCallback
    private fun handleWhatsAppStatusesFolder(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.resolve(JSObject().apply { put("cancelled", true); put("items", JSArray()) })
            return
        }
        pluginScope.launch {
            try {
                val rootDoc = DocumentsContract.getTreeDocumentId(uri)
                if (rootDoc.substringAfter(':', rootDoc).trimEnd('/').substringAfterLast('/').removePrefix(".")
                        .equals("Statuses", ignoreCase = true).not()) {
                    call.reject("Escolha a pasta Statuses do WhatsApp.")
                    return@launch
                }
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    call.reject("Não foi possível guardar a autorização de leitura da pasta escolhida.")
                    return@launch
                }
                val items = listStatusFiles(uri, rootDoc, maxItems = 200)
                context.getSharedPreferences(PREFS_FEATURES, Context.MODE_PRIVATE).edit()
                    .putString(KEY_WHATSAPP_STATUSES_TREE_URI, uri.toString())
                    .apply()
                call.resolve(JSObject().apply {
                    put("cancelled", false)
                    put("linked", true)
                    put("folderUri", uri.toString())
                    put("items", whatsappStatusesToJsArray(items))
                })
            } catch (error: Throwable) {
                call.reject("Não foi possível ler a pasta de status: ${error.message}")
            }
        }
    }

    @PluginMethod
    fun getWhatsAppStatuses(call: PluginCall) {
        pluginScope.launch {
            val prefs = context.getSharedPreferences(PREFS_FEATURES, Context.MODE_PRIVATE)
            var rawUri = prefs.getString(KEY_WHATSAPP_STATUSES_TREE_URI, null)
            if (rawUri.isNullOrBlank()) {
                // Recover SAF grants created by earlier app builds that did not save the URI string.
                val previousGrant = context.contentResolver.persistedUriPermissions.firstOrNull { grant ->
                    grant.isReadPermission && runCatching {
                        val docId = DocumentsContract.getTreeDocumentId(grant.uri)
                        docId.substringAfter(':', docId).trimEnd('/').substringAfterLast('/').removePrefix(".")
                            .equals("Statuses", ignoreCase = true)
                    }.getOrDefault(false)
                }
                rawUri = previousGrant?.uri?.toString()
                if (!rawUri.isNullOrBlank()) {
                    prefs.edit().putString(KEY_WHATSAPP_STATUSES_TREE_URI, rawUri).apply()
                }
            }
            if (rawUri.isNullOrBlank()) {
                call.resolve(JSObject().apply { put("linked", false); put("items", JSArray()) })
                return@launch
            }
            val uri = Uri.parse(rawUri)
            val hasGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
            if (!hasGrant) {
                prefs.edit().remove(KEY_WHATSAPP_STATUSES_TREE_URI).apply()
                call.resolve(JSObject().apply { put("linked", false); put("items", JSArray()) })
                return@launch
            }
            try {
                val rootDoc = DocumentsContract.getTreeDocumentId(uri)
                val items = listStatusFiles(uri, rootDoc, maxItems = 200)
                call.resolve(JSObject().apply {
                    put("linked", true)
                    put("folderUri", uri.toString())
                    put("items", whatsappStatusesToJsArray(items))
                })
            } catch (error: Throwable) {
                call.reject("Não foi possível ler a pasta de status vinculada: ${error.message}")
            }
        }
    }

    private fun listStatusFiles(treeUri: Uri, parentId: String, maxItems: Int): JSONArray {
        val output = JSONArray()
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext() && output.length() < maxItems) {
                val documentId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol).orEmpty()
                val mime = cursor.getString(mimeCol).orEmpty().lowercase()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val nested = listStatusFiles(treeUri, documentId, maxItems - output.length())
                    for (i in 0 until nested.length()) output.put(nested.getJSONObject(i))
                } else if ((mime.startsWith("image/") || mime.startsWith("video/")) && !name.startsWith(".")) {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in setOf("jpg", "jpeg", "png", "webp", "mp4", "m4v", "3gp", "webm")) continue
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    output.put(JSONObject().apply {
                        put("uri", childUri.toString()); put("name", name); put("mimeType", mime)
                        put("sizeBytes", if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else JSONObject.NULL)
                    })
                }
            }
        }
        return output
    }

    @PluginMethod
    fun getWhatsAppStatusThumbnail(call: PluginCall) {
        val sourceUri = call.getString("uri")?.let(Uri::parse)
        if (sourceUri == null || sourceUri.scheme != "content") {
            call.reject("URI de Status inválida.")
            return
        }
        pluginScope.launch {
            var bitmap: Bitmap? = null
            try {
                val prefs = context.getSharedPreferences(PREFS_FEATURES, Context.MODE_PRIVATE)
                val linkedTree = prefs.getString(KEY_WHATSAPP_STATUSES_TREE_URI, null)?.let(Uri::parse)
                    ?: error("Vincule novamente a pasta de Status do WhatsApp.")
                val hasGrant = context.contentResolver.persistedUriPermissions.any {
                    it.uri == linkedTree && it.isReadPermission
                }
                check(hasGrant) { "A autorização da pasta foi revogada. Vincule-a novamente." }
                val rootDocumentId = DocumentsContract.getTreeDocumentId(linkedTree)
                check(DocumentsContract.isDocumentUri(context, sourceUri)) { "O arquivo não pertence à pasta SAF." }
                check(DocumentsContract.getTreeDocumentId(sourceUri) == rootDocumentId) {
                    "O arquivo está fora da pasta Status vinculada."
                }
                val documentId = DocumentsContract.getDocumentId(sourceUri)
                check(documentId.startsWith("${rootDocumentId.trimEnd('/')}/")) {
                    "O arquivo está fora da pasta Status vinculada."
                }
                val mimeType = context.contentResolver.getType(sourceUri).orEmpty().lowercase()
                check(mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                    "Miniaturas só estão disponíveis para imagens e vídeos."
                }
                bitmap = createWhatsAppStatusThumbnail(sourceUri, mimeType)
                    ?: error("O Android não conseguiu gerar a miniatura deste arquivo.")
                val output = ByteArrayOutputStream()
                bitmap!!.compress(Bitmap.CompressFormat.JPEG, 78, output)
                call.resolve(JSObject().apply {
                    put("thumbnailDataUrl", "data:image/jpeg;base64,${Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)}")
                })
            } catch (error: Throwable) {
                call.reject("Não foi possível gerar a miniatura do Status: ${error.message}")
            } finally {
                bitmap?.recycle()
            }
        }
    }

    private fun createWhatsAppStatusThumbnail(uri: Uri, mimeType: String): Bitmap? {
        val resolver = context.contentResolver
        val targetSize = Point(480, 480)
        val documentThumbnail = runCatching {
            DocumentsContract.getDocumentThumbnail(resolver, uri, targetSize, null)
        }.getOrNull()
        if (documentThumbnail != null) return scaleStatusThumbnail(documentThumbnail)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val providerThumbnail = runCatching {
                resolver.loadThumbnail(uri, Size(targetSize.x, targetSize.y), null)
            }.getOrNull()
            if (providerThumbnail != null) return scaleStatusThumbnail(providerThumbnail)
        }

        if (mimeType.startsWith("image/")) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            while (maxOf(bounds.outWidth / (sampleSize * 2), bounds.outHeight / (sampleSize * 2)) >= targetSize.x) {
                sampleSize *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?.let(::scaleStatusThumbnail)
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let(::scaleStatusThumbnail)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleStatusThumbnail(bitmap: Bitmap): Bitmap {
        val maxDimension = 480
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        if (largestDimension <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / largestDimension
        val resized = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (resized !== bitmap) bitmap.recycle()
        return resized
    }

    @PluginMethod
    fun importWhatsAppStatus(call: PluginCall) {
        val sourceUri = call.getString("uri")?.let(Uri::parse)
        val mime = call.getString("mimeType").orEmpty().lowercase()
        if (sourceUri == null || !(mime.startsWith("image/") || mime.startsWith("video/"))) {
            call.reject("Status inválido: só são aceitos arquivos de imagem e vídeo.")
            return
        }
        pluginScope.launch {
            var tempFile: File? = null
            try {
                val name = queryDisplayName(sourceUri).ifBlank { "status_${System.currentTimeMillis()}${if (mime.startsWith("image/")) ".jpg" else ".mp4"}" }
                val ext = name.substringAfterLast('.', "").lowercase()
                require(ext in setOf("jpg", "jpeg", "png", "webp", "mp4", "m4v", "3gp", "webm")) { "Formato de Status não suportado." }
                tempFile = File(context.cacheDir, "status_${UUID.randomUUID()}.$ext")
                val input = context.contentResolver.openInputStream(sourceUri) ?: error("Arquivo de Status indisponível.")
                input.use { stream -> FileOutputStream(tempFile).use { output -> stream.copyTo(output) } }
                if (tempFile.length() !in 1..500L * 1024 * 1024) error("Arquivo vazio ou maior que 500 MB.")
                val saved = MediaStoreHelper.saveFileToGallery(context.applicationContext, tempFile, mime, name)
                call.resolve(JSObject().apply {
                    put("mediaUri", saved.mediaUri); put("displayPath", saved.displayPath)
                    put("fileName", saved.fileName); put("sizeBytes", saved.sizeBytes); put("mimeType", saved.mimeType)
                })
            } catch (error: Throwable) {
                call.reject("Falha ao importar Status: ${error.message}")
            } finally { tempFile?.delete() }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0).orEmpty()
        }
        return ""
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
    fun clearLocalDownloadHistory(call: PluginCall) {
        val keepActiveJobs = call.getBoolean("keepActiveJobs", true) ?: true
        val removed = queueStore.clearHistory(keepActiveJobs = keepActiveJobs)
        call.resolve(
            JSObject().apply {
                put("cleared", true)
                put("removedCount", removed)
            },
        )
    }

    @PluginMethod
    fun checkMediaExists(call: PluginCall) {
        val urisArr = call.getArray("uris")
        val pathsArr = call.getArray("filePaths")
        pluginScope.launch {
            val appCtx = context.applicationContext
            var exists = false
            if (urisArr != null) {
                for (i in 0 until urisArr.length()) {
                    val uriStr = urisArr.optString(i)
                        .trim()
                        .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                    if (uriStr != null && MediaStoreHelper.doesMediaExist(appCtx, uriStr, null)) {
                        exists = true
                        break
                    }
                }
            }
            if (!exists && pathsArr != null) {
                for (i in 0 until pathsArr.length()) {
                    val pathStr = pathsArr.optString(i)
                        .trim()
                        .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                    if (pathStr != null && MediaStoreHelper.doesMediaExist(appCtx, null, pathStr)) {
                        exists = true
                        break
                    }
                }
            }
            call.resolve(JSObject().apply { put("exists", exists) })
        }
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
                        put("mimeType", saved.mimeType)
                        put("type", saved.mediaType)
                        put("width", saved.width ?: JSONObject.NULL)
                        put("height", saved.height ?: JSONObject.NULL)
                    },
                )
            } catch (e: Throwable) {
                call.reject("Falha ao salvar na galeria: ${e.message}")
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
        val mime = call.getString("mimeType") ?: if (uris.size > 1) "*/*" else "video/*"
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
    fun getDiagnosticsReport(call: PluginCall) {
        val appCtx = context.applicationContext
        val ytdlpVer = LocalEngineManager.getFastYtdlpVersion(appCtx)
        val report = PerformanceTracker.buildDiagnosticsReport(
            ytdlpVersion = ytdlpVer,
            lastUpdateAt = secureVault.lastUpdateAt,
        )
        call.resolve(JSObject(report.toString()))
    }

    // ---- Atualizações do app (GitHub Releases) ------------------------------------------------

    private val appUpdateProgress: (JSONObject) -> Unit = { progress ->
        try {
            notifyListeners("appUpdateProgress", JSObject(progress.toString()), true)
        } catch (_: Throwable) {
        }
    }

    @PluginMethod
    fun getAppUpdateState(call: PluginCall) {
        call.resolve(JSObject(AppUpdateManager.getState(context.applicationContext).toString()))
    }

    @PluginMethod
    fun checkAppUpdate(call: PluginCall) {
        val includePreReleases = call.getBoolean("includePreReleases", true) ?: true
        pluginScope.launch {
            try {
                val state = AppUpdateManager.checkForUpdate(context.applicationContext, includePreReleases)
                call.resolve(JSObject(state.toString()))
            } catch (e: Throwable) {
                call.reject(updateErrorMessage(e, "Não foi possível verificar atualizações."))
            }
        }
    }

    @PluginMethod
    fun downloadAppUpdate(call: PluginCall) {
        pluginScope.launch {
            try {
                val state = AppUpdateManager.downloadLatest(context.applicationContext, appUpdateProgress)
                call.resolve(JSObject(state.toString()))
            } catch (e: Throwable) {
                call.reject(updateErrorMessage(e, "Não foi possível baixar a atualização."))
            }
        }
    }

    @PluginMethod
    fun cancelAppUpdateDownload(call: PluginCall) {
        AppUpdateManager.cancelDownload()
        call.resolve(JSObject().put("cancelled", true))
    }

    @PluginMethod
    fun deleteDownloadedAppUpdate(call: PluginCall) {
        AppUpdateManager.deleteDownloaded(context.applicationContext)
        call.resolve(JSObject(AppUpdateManager.getState(context.applicationContext).toString()))
    }

    /**
     * Opens the system installer for the downloaded APK. When "install unknown apps" is not yet
     * allowed, opens that settings screen instead and resolves `permission_required`; the APK is
     * kept so the user can retry without downloading again.
     */
    @PluginMethod
    fun installAppUpdate(call: PluginCall) {
        val appCtx = context.applicationContext
        try {
            if (!AppUpdateManager.canRequestPackageInstalls(appCtx)) {
                val settingsIntent = AppUpdateManager.unknownSourcesSettingsIntent(appCtx)
                if (settingsIntent != null) {
                    (activity ?: context).startActivity(settingsIntent)
                }
                call.resolve(JSObject().put("status", "permission_required"))
                return
            }
            val installIntent = AppUpdateManager.buildInstallIntent(appCtx)
            (activity ?: context).startActivity(installIntent)
            call.resolve(JSObject().put("status", "installer_opened"))
        } catch (e: Throwable) {
            call.reject(updateErrorMessage(e, "Não foi possível iniciar a instalação."))
        }
    }

    private fun updateErrorMessage(e: Throwable, fallback: String): String {
        val raw = (e as? com.devwolf.downloader.resolvers.ResolverException)?.userMessage ?: e.message
        return raw?.takeIf { it.isNotBlank() }?.let { PerformanceTracker.sanitizeText(it) } ?: fallback
    }

    @PluginMethod
    fun openInstagramLoginWebView(call: PluginCall) {
        launchPlatformLoginActivity(call, InstagramSessionActivity.PLATFORM_INSTAGRAM, null)
    }

    @PluginMethod
    fun openPlatformLoginWebView(call: PluginCall) {
        val platform = call.getString("platform", InstagramSessionActivity.PLATFORM_INSTAGRAM)
            ?.trim()
            ?.lowercase()
            ?: InstagramSessionActivity.PLATFORM_INSTAGRAM
        if (platform !in InstagramSessionActivity.SUPPORTED_PLATFORMS) {
            call.reject("Plataforma não suportada para login: $platform")
            return
        }
        val redditClientId = call.getString("redditClientId")?.trim()?.takeIf { it.isNotEmpty() }
        launchPlatformLoginActivity(call, platform, redditClientId)
    }

    private fun launchPlatformLoginActivity(
        call: PluginCall,
        platform: String,
        redditClientId: String?,
    ) {
        val act = activity
        if (act == null) {
            call.reject("Atividade Android indisponível para abrir WebView de login.")
            return
        }
        val intent = Intent(act, InstagramSessionActivity::class.java).apply {
            putExtra(InstagramSessionActivity.EXTRA_PLATFORM, platform)
            if (!redditClientId.isNullOrBlank()) {
                putExtra(InstagramSessionActivity.EXTRA_REDDIT_CLIENT_ID, redditClientId)
            }
        }
        startActivityForResult(call, intent, "handlePlatformLoginResult")
    }

    @ActivityCallback
    private fun handlePlatformLoginResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val data = result.data
        val platform = data?.getStringExtra(InstagramSessionActivity.EXTRA_PLATFORM)
            ?: InstagramSessionActivity.PLATFORM_INSTAGRAM
        val saved = result.resultCode == Activity.RESULT_OK &&
            (data?.getBooleanExtra(InstagramSessionActivity.EXTRA_SAVED, false) == true)
        val updatedAt = data?.getStringExtra(InstagramSessionActivity.EXTRA_UPDATED_AT)
        val message = data?.getStringExtra(InstagramSessionActivity.EXTRA_MESSAGE)
            ?: if (saved) "Sessão de $platform salva localmente." else "Login de $platform não concluído."
        call.resolve(
            JSObject().apply {
                put("saved", saved)
                put("platform", platform)
                put("updatedAt", updatedAt ?: JSONObject.NULL)
                put("message", message)
            },
        )
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
        val platform = call.getString("platform").orEmpty().trim().lowercase()
        val cookieHeader = call.getString("cookieHeader")?.trim()?.takeIf { it.isNotEmpty() }
        val accessToken = call.getString("accessToken")?.trim()?.takeIf { it.isNotEmpty() }
        val clientId = call.getString("clientId")?.trim()?.takeIf { it.isNotEmpty() }
        val username = call.getString("username")?.trim()?.takeIf { it.isNotEmpty() }
        val userAgent = call.getString("userAgent")?.trim()?.takeIf { it.isNotEmpty() }
        val authType = call.getString("authType")?.trim()?.takeIf { it.isNotEmpty() }
        try {
            val updatedAt = when {
                platform == "instagram" && cookieHeader != null ->
                    secureVault.saveInstagramSession(cookieHeader = cookieHeader, userAgent = userAgent, source = "manual")
                accessToken != null ->
                    secureVault.savePlatformOAuthSession(
                        platform = platform,
                        accessToken = accessToken,
                        clientId = clientId,
                        username = username,
                        source = "manual",
                    )
                else ->
                    secureVault.savePlatformSession(
                        platform = platform,
                        cookieHeader = cookieHeader,
                        username = username,
                        userAgent = userAgent,
                        authType = authType,
                        source = "manual",
                    )
            }
            call.resolve(
                JSObject().apply {
                    put("saved", true)
                    put("platform", platform)
                    put("updatedAt", updatedAt)
                },
            )
        } catch (e: Throwable) {
            call.reject(e.message ?: "Erro ao salvar sessão local")
        }
    }

    @PluginMethod
    fun revokePlatformSession(call: PluginCall) {
        val platform = call.getString("platform").orEmpty().trim().lowercase()
        pluginScope.launch {
            val revoked = if (platform in InstagramSessionActivity.SUPPORTED_PLATFORMS) {
                withContext(Dispatchers.Main) {
                    InstagramSessionActivity.clearWebViewSession(context.applicationContext, platform)
                }
            } else {
                secureVault.revokePlatformSession(platform)
            }
            call.resolve(
                JSObject().apply {
                    put("revoked", revoked)
                    put("platform", platform)
                },
            )
        }
    }

    companion object {
        private const val PREFS_FEATURES = "devwolf_features"
        private const val KEY_WHATSAPP_STATUSES_TREE_URI = "whatsapp_statuses_tree_uri"

        @Volatile
        var instance: DevwolfNativePlugin? = null
            private set
    }
}
