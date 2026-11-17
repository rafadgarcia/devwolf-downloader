package com.devwolf.downloader.resolvers

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Streams resolved [ResolvedMediaItem] objects directly to disk using the shared [SafeHttpClient]
 * connection pool, with honest byte progress, TTFB measurement, SSRF redirect validation,
 * host-scoped headers, and MIME-type verification against disguised HTML responses.
 */
class HttpMediaDownloader(
    private val httpClient: SafeHttpClient = SafeHttpClient.shared,
) {

    fun downloadAll(
        items: List<ResolvedMediaItem>,
        workDir: File,
        cancellationCheck: () -> Boolean = { false },
        onProgress: (HttpDownloadProgress) -> Unit = {},
    ): List<HttpDownloadResult> {
        if (items.isEmpty()) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.EXTRACTION_FAILED,
                technicalReason = "Lista de itens resolvidos está vazia",
            )
        }
        if (!workDir.exists()) {
            workDir.mkdirs()
        }

        val results = mutableListOf<HttpDownloadResult>()
        val totalItems = items.size

        for ((idx, item) in items.withIndex()) {
            if (cancellationCheck()) {
                throw ResolverException(DevwolfErrorCode.CANCELLED)
            }
            val result = downloadSingle(
                item = item,
                itemPos = idx + 1,
                totalItems = totalItems,
                workDir = workDir,
                cancellationCheck = cancellationCheck,
                onProgress = onProgress,
            )
            results.add(result)
        }

        return results
    }

    fun downloadSingle(
        item: ResolvedMediaItem,
        itemPos: Int,
        totalItems: Int,
        workDir: File,
        cancellationCheck: () -> Boolean = { false },
        onProgress: (HttpDownloadProgress) -> Unit = {},
    ): HttpDownloadResult {
        val safeName = sanitizeTargetFileName(item.fileName, item.type)
        val canonicalWork = workDir.canonicalPath
        val finalFile = File(workDir, safeName)
        if (!finalFile.canonicalPath.startsWith(canonicalWork)) {
            throw ResolverException(
                errorCode = DevwolfErrorCode.STORAGE_ERROR,
                technicalReason = "Caminho de saída inválido fora do diretório de trabalho",
            )
        }
        val partFile = File(workDir, "$safeName.part")

        val headers = LinkedHashMap<String, String>().apply {
            put("User-Agent", SafeHttpClient.DEFAULT_MOBILE_CHROME_UA)
            put("Accept", "*/*")
            for ((k, v) in item.headers) {
                if (!k.equals("cookie", ignoreCase = true) && !k.equals("authorization", ignoreCase = true)) {
                    put(k, v)
                }
            }
        }

        return try {
            httpClient.executeStreamingGet(
                url = item.url,
                headers = headers,
                cancellationCheck = cancellationCheck,
            ) { response, _, ttfbMs, httpRequests ->
                val code = response.code
                if (code !in 200..299) {
                    val errCode = ErrorClassifier.fromHttpStatus(code)
                    throw ResolverException(
                        errorCode = errCode,
                        technicalReason = "CDN HTTP $code ao baixar item ${item.index}",
                        httpStatus = code,
                    )
                }

                val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                if (contentType.startsWith("text/html") ||
                    contentType.startsWith("application/xhtml") ||
                    contentType.contains("xml")
                ) {
                    throw ResolverException(
                        errorCode = DevwolfErrorCode.MEDIA_UNAVAILABLE,
                        technicalReason = "Servidor retornou conteúdo HTML/XML ($contentType) em vez de mídia",
                    )
                }

                val body = response.body
                    ?: throw ResolverException(
                        errorCode = DevwolfErrorCode.NETWORK_ERROR,
                        technicalReason = "Corpo HTTP vazio",
                    )

                val totalBytes = body.contentLength().takeIf { it > 0L }
                val transferStartMs = System.currentTimeMillis()
                var bytesDownloaded = 0L
                var lastEmitMs = 0L

                onProgress(
                    HttpDownloadProgress(
                        itemIndex = itemPos,
                        totalItems = totalItems,
                        bytesDownloaded = 0L,
                        totalBytes = totalBytes,
                        percent = if (totalBytes != null) 0.0 else null,
                        isIndeterminate = totalBytes == null,
                        speedBytesPerSec = null,
                        etaSeconds = null,
                        ttfbMs = ttfbMs,
                    ),
                )

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            if (cancellationCheck()) {
                                throw ResolverException(DevwolfErrorCode.CANCELLED)
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            bytesDownloaded += read

                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= 150L) {
                                lastEmitMs = now
                                val elapsedMs = (now - transferStartMs).coerceAtLeast(1L)
                                val speedBps = (bytesDownloaded * 1000L) / elapsedMs
                                val pct = totalBytes?.let { ((bytesDownloaded.toDouble() / it.toDouble()) * 100.0).coerceIn(0.0, 100.0) }
                                val etaSec = if (totalBytes != null && speedBps > 0L) {
                                    ((totalBytes - bytesDownloaded).coerceAtLeast(0L) / speedBps)
                                } else {
                                    null
                                }
                                onProgress(
                                    HttpDownloadProgress(
                                        itemIndex = itemPos,
                                        totalItems = totalItems,
                                        bytesDownloaded = bytesDownloaded,
                                        totalBytes = totalBytes,
                                        percent = pct,
                                        isIndeterminate = totalBytes == null,
                                        speedBytesPerSec = speedBps,
                                        etaSeconds = etaSec,
                                        ttfbMs = ttfbMs,
                                    ),
                                )
                            }
                        }
                        output.flush()
                    }
                }

                if (bytesDownloaded <= 0L) {
                    throw ResolverException(
                        errorCode = DevwolfErrorCode.MEDIA_UNAVAILABLE,
                        technicalReason = "Arquivo baixado com 0 bytes",
                    )
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }

                val transferMs = (System.currentTimeMillis() - transferStartMs).coerceAtLeast(1L)
                val finalSpeed = (bytesDownloaded * 1000L) / transferMs
                onProgress(
                    HttpDownloadProgress(
                        itemIndex = itemPos,
                        totalItems = totalItems,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes ?: bytesDownloaded,
                        percent = 100.0,
                        isIndeterminate = false,
                        speedBytesPerSec = finalSpeed,
                        etaSeconds = 0L,
                        ttfbMs = ttfbMs,
                    ),
                )

                HttpDownloadResult(
                    item = item,
                    file = finalFile,
                    bytesDownloaded = bytesDownloaded,
                    ttfbMs = ttfbMs,
                    transferMs = transferMs,
                    httpRequests = httpRequests,
                )
            }
        } catch (e: ResolverException) {
            partFile.delete()
            throw e
        } catch (e: IOException) {
            partFile.delete()
            throw ResolverException(
                errorCode = DevwolfErrorCode.NETWORK_ERROR,
                technicalReason = "Erro de rede ao transferir mídia: ${e.message}",
                cause = e,
            )
        } catch (e: SecurityException) {
            partFile.delete()
            throw ResolverException(
                errorCode = DevwolfErrorCode.STORAGE_ERROR,
                technicalReason = e.message,
                cause = e,
            )
        }
    }

    companion object {
        internal fun sanitizeTargetFileName(raw: String, type: MediaItemType): String {
            val defaultExt = when (type) {
                MediaItemType.VIDEO -> "mp4"
                MediaItemType.IMAGE -> "jpg"
                MediaItemType.AUDIO -> "mp3"
            }
            val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                .trim('.')
            val base = cleaned.substringBeforeLast('.', cleaned).take(72).ifBlank { "devwolf_item" }
            val ext = cleaned.substringAfterLast('.', defaultExt).lowercase().take(8).ifBlank { defaultExt }
            return "$base.$ext"
        }
    }
}
