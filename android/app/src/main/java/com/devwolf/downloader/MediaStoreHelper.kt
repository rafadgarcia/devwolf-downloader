package com.devwolf.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

data class SavedMediaResult(
    val mediaUri: String,
    val displayPath: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val mediaType: String,
    val width: Int? = null,
    val height: Int? = null,
    val thumbnailDataUrl: String? = null,
)

/**
 * Integrates with Android Scoped Storage (MediaStore IS_PENDING=1 -> 0 on API 29+)
 * to store individual photos, videos, and audio inside the "Devwolf Downloader" gallery album
 * and expose native share/open/delete. Never packages or registers .zip archives as video.
 */
object MediaStoreHelper {
    const val ALBUM_NAME = "Devwolf Downloader"
    const val THUMBNAIL_MAX_DIM = 480
    const val THUMBNAIL_JPEG_QUALITY = 75

    internal fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, maxDim: Int = THUMBNAIL_MAX_DIM): Int {
        var inSampleSize = 1
        if (rawHeight > maxDim || rawWidth > maxDim) {
            val halfHeight = rawHeight / 2
            val halfWidth = rawWidth / 2
            while ((halfHeight / inSampleSize) >= maxDim && (halfWidth / inSampleSize) >= maxDim) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    internal fun computeScaledDimensions(width: Int, height: Int, maxDim: Int = THUMBNAIL_MAX_DIM): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return maxDim to maxDim
        val longest = maxOf(width, height)
        if (longest <= maxDim) return width to height
        val scale = maxDim.toDouble() / longest.toDouble()
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        return targetW to targetH
    }

    /**
     * Generates a compact JPEG base64 Data URL (`data:image/jpeg;base64,...`) scaled to at most
     * [THUMBNAIL_MAX_DIM]px for video and image MIME types without needing external
     * storage read permissions in the Capacitor WebView.
     */
    fun generateThumbnailDataUrl(file: File, mimeType: String): String? {
        if (!file.exists() || !file.isFile) return null
        val normalizedMime = mimeType.trim().lowercase()
        var bitmap: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            bitmap = when {
                normalizedMime.startsWith("video/") -> decodeVideoFrame(file)
                normalizedMime.startsWith("image/") -> decodeSampledImage(file)
                else -> null
            } ?: return null

            val (targetW, targetH) = computeScaledDimensions(bitmap.width, bitmap.height, THUMBNAIL_MAX_DIM)
            scaled = if (bitmap.width == targetW && bitmap.height == targetH) {
                bitmap
            } else {
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            }

            val baos = ByteArrayOutputStream()
            val ok = scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_JPEG_QUALITY, baos)
            if (!ok) return null
            val bytes = baos.toByteArray()
            if (bytes.isEmpty()) return null
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        } finally {
            if (scaled != null && scaled !== bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private fun decodeVideoFrame(file: File): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.frameAtTime
        } finally {
            try {
                mmr.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun decodeSampledImage(file: File): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, THUMBNAIL_MAX_DIM)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
    }

    fun sanitizeFileName(rawName: String, fallbackExt: String = "mp4"): String {
        val base = rawName.substringBeforeLast('.', rawName)
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .take(80)
            .ifBlank { "devwolf_${System.currentTimeMillis()}" }
        val ext = rawName.substringAfterLast('.', fallbackExt)
            .replace(Regex("""[^a-zA-Z0-9]"""), "")
            .lowercase()
            .ifBlank { fallbackExt }
        return "$base.$ext"
    }

    fun resolveEffectiveMimeType(sourceFile: File, hintMimeType: String?): String {
        val cleanHint = hintMimeType?.trim()?.lowercase().orEmpty()
        val guessed = StatusModeProcessor.guessMimeType(sourceFile.name).lowercase()
        val ext = sourceFile.extension.lowercase()
        if (ext == "zip" || cleanHint == "application/zip" || cleanHint == "application/x-zip-compressed") {
            throw IllegalArgumentException("Arquivos ZIP não podem ser registrados como mídia na galeria Android.")
        }
        val effective = when {
            cleanHint.startsWith("image/") || cleanHint.startsWith("video/") || cleanHint.startsWith("audio/") -> cleanHint
            guessed.startsWith("image/") || guessed.startsWith("video/") || guessed.startsWith("audio/") -> guessed
            else -> throw IllegalArgumentException("Tipo de arquivo não suportado para galeria: ${sourceFile.name}")
        }
        return effective
    }

    fun resolveMediaType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("audio/") -> "audio"
        mimeType.startsWith("video/") -> "video"
        else -> throw IllegalArgumentException("MIME inválido para mídia: $mimeType")
    }

    fun saveFileToGallery(
        context: Context,
        sourceFile: File,
        mimeType: String,
        preferredName: String? = null,
        widthHint: Int? = null,
        heightHint: Int? = null,
    ): SavedMediaResult {
        require(sourceFile.exists() && sourceFile.isFile) { "Arquivo de mídia não encontrado" }
        val effectiveMime = resolveEffectiveMimeType(sourceFile, mimeType)
        val mediaType = resolveMediaType(effectiveMime)
        val defaultExt = when (mediaType) {
            "image" -> "jpg"
            "audio" -> "mp3"
            else -> "mp4"
        }
        val safeName = sanitizeFileName(preferredName ?: sourceFile.name, sourceFile.extension.ifBlank { defaultExt })
        val (detectedW, detectedH) = if (widthHint != null && heightHint != null) {
            widthHint to heightHint
        } else {
            probeDimensions(sourceFile, mediaType)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, relativeDir) = when (mediaType) {
                "image" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"
                "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MUSIC}/$ALBUM_NAME"
                else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, effectiveMime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (detectedW != null && detectedW > 0) put(MediaStore.MediaColumns.WIDTH, detectedW)
                if (detectedH != null && detectedH > 0) put(MediaStore.MediaColumns.HEIGHT, detectedH)
            }

            val resolver = context.contentResolver
            val itemUri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Não foi possível registrar mídia no MediaStore")

            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                    }
                } ?: throw IllegalStateException("Não foi possível abrir stream do MediaStore")

                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(itemUri, doneValues, null, null)

                return SavedMediaResult(
                    mediaUri = itemUri.toString(),
                    displayPath = "$relativeDir/$safeName",
                    fileName = safeName,
                    sizeBytes = sourceFile.length(),
                    mimeType = effectiveMime,
                    mediaType = mediaType,
                    width = detectedW,
                    height = detectedH,
                )
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw e
            }
        } else {
            val baseType = when (mediaType) {
                "image" -> Environment.DIRECTORY_PICTURES
                "audio" -> Environment.DIRECTORY_MUSIC
                else -> Environment.DIRECTORY_MOVIES
            }
            @Suppress("DEPRECATION")
            val targetDir = File(Environment.getExternalStoragePublicDirectory(baseType), ALBUM_NAME)
            targetDir.mkdirs()
            val destFile = File(targetDir, safeName)
            sourceFile.copyTo(destFile, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(effectiveMime), null)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
            return SavedMediaResult(
                mediaUri = uri.toString(),
                displayPath = "$baseType/$ALBUM_NAME/$safeName",
                fileName = safeName,
                sizeBytes = destFile.length(),
                mimeType = effectiveMime,
                mediaType = mediaType,
                width = detectedW,
                height = detectedH,
            )
        }
    }

    private fun probeDimensions(file: File, mediaType: String): Pair<Int?, Int?> {
        return try {
            when (mediaType) {
                "image" -> {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, opts)
                    val w = opts.outWidth.takeIf { it > 0 }
                    val h = opts.outHeight.takeIf { it > 0 }
                    w to h
                }
                "video" -> {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(file.absolutePath)
                        val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                        w to h
                    } finally {
                        mmr.release()
                    }
                }
                else -> null to null
            }
        } catch (_: Throwable) {
            null to null
        }
    }

    fun resolveSharableUri(context: Context, uriString: String?, filePath: String?): Uri? {
        if (!uriString.isNullOrBlank()) {
            val parsed = Uri.parse(uriString)
            if (parsed.scheme == "content") return parsed
        }
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            if (file.exists()) {
                return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        }
        return null
    }

    fun buildShareIntent(
        context: Context,
        uris: List<Uri>,
        mimeType: String,
        title: String?,
        text: String?,
        targetPackage: String? = null,
    ): Intent {
        val intent = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        }
        if (!title.isNullOrBlank()) intent.putExtra(Intent.EXTRA_SUBJECT, title)
        if (!text.isNullOrBlank()) intent.putExtra(Intent.EXTRA_TEXT, text)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!targetPackage.isNullOrBlank()) {
            intent.setPackage(targetPackage)
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
            intent.setPackage(null)
        }
        return Intent.createChooser(intent, title ?: "Compartilhar mídia")
    }

    fun openMedia(context: Context, uri: Uri, mimeType: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun deleteMedia(context: Context, uriString: String?, filePath: String?): Boolean {
        var deleted = false
        if (!uriString.isNullOrBlank()) {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content") {
                try {
                    deleted = context.contentResolver.delete(uri, null, null) > 0
                } catch (_: Exception) {
                }
            }
        }
        if (!filePath.isNullOrBlank()) {
            val file = File(filePath)
            val appFiles = context.filesDir.canonicalPath
            val appCache = context.cacheDir.canonicalPath
            val canonical = try { file.canonicalPath } catch (_: Exception) { "" }
            if (canonical.startsWith(appFiles) || canonical.startsWith(appCache)) {
                if (file.exists() && file.delete()) {
                    deleted = true
                }
            }
        }
        return deleted
    }
}
