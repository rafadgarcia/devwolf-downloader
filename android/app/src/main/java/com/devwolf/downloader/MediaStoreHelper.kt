package com.devwolf.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

data class SavedMediaResult(
    val mediaUri: String,
    val displayPath: String,
    val fileName: String,
    val sizeBytes: Long,
)

/**
 * Integrates with Android Scoped Storage (MediaStore IS_PENDING=1 -> 0 on API 29+)
 * to store media inside the "Devwolf Downloader" gallery album and expose native share/open/delete.
 */
object MediaStoreHelper {
    const val ALBUM_NAME = "Devwolf Downloader"

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

    fun saveFileToGallery(context: Context, sourceFile: File, mimeType: String, preferredName: String? = null): SavedMediaResult {
        require(sourceFile.exists() && sourceFile.isFile) { "Arquivo de mídia não encontrado" }
        val safeName = sanitizeFileName(preferredName ?: sourceFile.name, sourceFile.extension.ifBlank { "mp4" })

        val isImage = mimeType.startsWith("image/")
        val isAudio = mimeType.startsWith("audio/")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, relativeDir) = when {
                isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_PICTURES}/$ALBUM_NAME"
                isAudio -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MUSIC}/$ALBUM_NAME"
                else -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "${Environment.DIRECTORY_MOVIES}/$ALBUM_NAME"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
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
                )
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw e
            }
        } else {
            val baseType = when {
                isImage -> Environment.DIRECTORY_PICTURES
                isAudio -> Environment.DIRECTORY_MUSIC
                else -> Environment.DIRECTORY_MOVIES
            }
            @Suppress("DEPRECATION")
            val targetDir = File(Environment.getExternalStoragePublicDirectory(baseType), ALBUM_NAME)
            targetDir.mkdirs()
            val destFile = File(targetDir, safeName)
            sourceFile.copyTo(destFile, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(destFile.absolutePath), arrayOf(mimeType), null)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destFile)
            return SavedMediaResult(
                mediaUri = uri.toString(),
                displayPath = "$baseType/$ALBUM_NAME/$safeName",
                fileName = safeName,
                sizeBytes = destFile.length(),
            )
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
