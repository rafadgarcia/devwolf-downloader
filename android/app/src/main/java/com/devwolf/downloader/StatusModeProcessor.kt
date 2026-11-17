package com.devwolf.downloader

import android.content.Context
import android.media.MediaMetadataRetriever
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class MediaProbeInfo(
    val formatName: String,
    val videoCodec: String?,
    val audioCodec: String?,
    val pixFmt: String?,
    val width: Int,
    val height: Int,
    val durationSeconds: Double?,
) {
    fun isWhatsAppStatusCompliant(): Boolean {
        val isMp4Like = formatName.split(',').any { it.trim().lowercase() in setOf("mp4", "mov", "m4a", "3gp", "3g2", "mj2") }
        val isH264 = videoCodec?.lowercase() == "h264"
        val isAacOrSilent = audioCodec == null || audioCodec.lowercase() == "aac"
        val validPixFmt = pixFmt == null || pixFmt.lowercase() in setOf("yuv420p", "yuvj420p")
        val reasonableResolution = width <= 1920 && height <= 1920
        return isMp4Like && isH264 && isAacOrSilent && validPixFmt && reasonableResolution
    }
}

data class ProcessedMediaItem(
    val file: File,
    val mimeType: String,
    val durationSeconds: Double?,
    val isSegment: Boolean,
    val segmentIndex: Int?,
    val isOriginal: Boolean,
)

/**
 * Handles WhatsApp Status Mode inspection, smart stream copy vs H.264/AAC MP4 transcode,
 * and optional segment splitting using bundled FFmpeg/FFprobe binaries (zero shell interpolation).
 */
object StatusModeProcessor {

    fun findFfmpegBinary(context: Context): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidates = listOf(
            File(nativeDir, "libffmpeg.so"),
            File(context.filesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
            File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }
            ?: candidates.firstOrNull { it.exists() }
    }

    fun findFfprobeBinary(context: Context): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidates = listOf(
            File(nativeDir, "libffprobe.so"),
            File(context.filesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffprobe"),
            File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffprobe"),
        )
        return candidates.firstOrNull { it.exists() && it.canExecute() }
            ?: candidates.firstOrNull { it.exists() }
    }

    fun buildEnv(context: Context): Map<String, String> {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val ffmpegLibDir = File(context.filesDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath
        val noBackupLibDir = File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath
        val existing = System.getenv("LD_LIBRARY_PATH").orEmpty()
        val combined = listOf(nativeDir, ffmpegLibDir, noBackupLibDir, existing)
            .filter { it.isNotBlank() }
            .joinToString(":")
        return mapOf("LD_LIBRARY_PATH" to combined)
    }

    fun probe(context: Context, inputFile: File): MediaProbeInfo? {
        var ffprobe = findFfprobeBinary(context)
        if (ffprobe == null) {
            LocalEngineManager.ensureFfmpegInitialized(context)
            ffprobe = findFfprobeBinary(context)
        }
        ffprobe ?: return null
        val cmd = listOf(
            ffprobe.absolutePath,
            "-v", "quiet",
            "-print_format", "json",
            "-show_format",
            "-show_streams",
            inputFile.absolutePath,
        )
        val output = runProcessCapture(cmd, buildEnv(context), 30) ?: return null
        return parseProbeJson(output)
    }

    /**
     * Lightweight duration probe via Android MediaMetadataRetriever so jobs with Status Mode OFF
     * never initialize FFmpeg/FFprobe at all.
     */
    fun probeDurationLightweight(inputFile: File): Double? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(inputFile.absolutePath)
            val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ms?.takeIf { it > 0L }?.let { it / 1000.0 }
        } catch (_: Throwable) {
            null
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    fun parseProbeJson(jsonText: String): MediaProbeInfo? {
        return try {
            val root = JSONObject(jsonText)
            val formatObj = root.optJSONObject("format")
            val formatName = formatObj?.optString("format_name", "") ?: ""
            val duration = formatObj?.optString("duration")?.toDoubleOrNull()
            val streams = root.optJSONArray("streams")
            var videoCodec: String? = null
            var audioCodec: String? = null
            var pixFmt: String? = null
            var width = 0
            var height = 0
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    when (s.optString("codec_type")) {
                        "video" -> if (videoCodec == null) {
                            videoCodec = s.optString("codec_name").takeIf { it.isNotBlank() }
                            pixFmt = s.optString("pix_fmt").takeIf { it.isNotBlank() }
                            width = s.optInt("width", 0)
                            height = s.optInt("height", 0)
                        }
                        "audio" -> if (audioCodec == null) {
                            audioCodec = s.optString("codec_name").takeIf { it.isNotBlank() }
                        }
                    }
                }
            }
            MediaProbeInfo(
                formatName = formatName,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                pixFmt = pixFmt,
                width = width,
                height = height,
                durationSeconds = duration,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun buildStatusTranscodeArgs(
        ffmpegPath: String,
        inputPath: String,
        outputPath: String,
        probe: MediaProbeInfo?,
    ): List<String> {
        val base = mutableListOf(ffmpegPath, "-y", "-i", inputPath)
        if (probe != null && probe.isWhatsAppStatusCompliant()) {
            base.addAll(listOf("-c", "copy", "-movflags", "+faststart", outputPath))
        } else {
            // Preserve original aspect ratio while ensuring even dimensions for yuv420p/libx264
            base.addAll(
                listOf(
                    "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "23",
                    "-pix_fmt", "yuv420p",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-movflags", "+faststart",
                    outputPath,
                ),
            )
        }
        return base
    }

    fun buildSegmentArgs(
        ffmpegPath: String,
        inputPath: String,
        outputPattern: String,
        segmentDurationSeconds: Int,
    ): List<String> {
        return listOf(
            ffmpegPath,
            "-y",
            "-i", inputPath,
            "-c", "copy",
            "-map", "0",
            "-f", "segment",
            "-segment_time", segmentDurationSeconds.toString(),
            "-reset_timestamps", "1",
            "-movflags", "+faststart",
            outputPattern,
        )
    }

    fun processForStatusMode(
        context: Context,
        sourceFile: File,
        workDir: File,
        segmentDurationSeconds: Int?,
        keepOriginal: Boolean,
    ): List<ProcessedMediaItem> {
        val mimeGuess = guessMimeType(sourceFile.name)
        if (mimeGuess.startsWith("image/") || mimeGuess.startsWith("audio/")) {
            return listOf(
                ProcessedMediaItem(
                    file = sourceFile,
                    mimeType = mimeGuess,
                    durationSeconds = if (mimeGuess.startsWith("audio/")) probeDurationLightweight(sourceFile) else null,
                    isSegment = false,
                    segmentIndex = null,
                    isOriginal = true,
                ),
            )
        }

        LocalEngineManager.ensureFfmpegInitialized(context)
        val ffmpeg = findFfmpegBinary(context)
        val probe = probe(context, sourceFile)
        val env = buildEnv(context)

        // Non-video media (images/audio) bypass Status video transcoding
        if (probe != null && probe.videoCodec == null) {
            return listOf(
                ProcessedMediaItem(
                    file = sourceFile,
                    mimeType = guessMimeType(sourceFile.name),
                    durationSeconds = probe.durationSeconds,
                    isSegment = false,
                    segmentIndex = null,
                    isOriginal = true,
                ),
            )
        }

        if (ffmpeg == null) {
            return listOf(
                ProcessedMediaItem(
                    file = sourceFile,
                    mimeType = guessMimeType(sourceFile.name),
                    durationSeconds = probe?.durationSeconds,
                    isSegment = false,
                    segmentIndex = null,
                    isOriginal = true,
                ),
            )
        }

        val baseName = sourceFile.nameWithoutExtension.replace(Regex("""[^a-zA-Z0-9._-]"""), "_").take(48)
            .ifBlank { "devwolf_media" }
        val statusOut = File(workDir, "${baseName}_status.mp4")

        val statusFile: File = if (probe != null && probe.isWhatsAppStatusCompliant() && sourceFile.extension.lowercase() == "mp4") {
            // Already compliant H.264/AAC MP4 — avoid unnecessary recompression
            if (keepOriginal) {
                sourceFile.copyTo(statusOut, overwrite = true)
                statusOut
            } else {
                sourceFile
            }
        } else {
            val args = buildStatusTranscodeArgs(ffmpeg.absolutePath, sourceFile.absolutePath, statusOut.absolutePath, probe)
            val ok = runProcessSuccess(args, env, 300)
            if (ok && statusOut.exists() && statusOut.length() > 0L) {
                statusOut
            } else {
                sourceFile
            }
        }

        val results = mutableListOf<ProcessedMediaItem>()
        if (keepOriginal && statusFile.absolutePath != sourceFile.absolutePath) {
            results.add(
                ProcessedMediaItem(
                    file = sourceFile,
                    mimeType = guessMimeType(sourceFile.name),
                    durationSeconds = probe?.durationSeconds,
                    isSegment = false,
                    segmentIndex = null,
                    isOriginal = true,
                ),
            )
        }

        val durationSec = probe?.durationSeconds
        if (segmentDurationSeconds != null &&
            segmentDurationSeconds >= 5 &&
            (durationSec == null || durationSec > segmentDurationSeconds + 1.0)
        ) {
            val segPattern = File(workDir, "${baseName}_part%02d.mp4").absolutePath
            val segArgs = buildSegmentArgs(ffmpeg.absolutePath, statusFile.absolutePath, segPattern, segmentDurationSeconds)
            val segOk = runProcessSuccess(segArgs, env, 240)
            val segmentFiles = workDir.listFiles { f ->
                f.isFile && f.name.startsWith("${baseName}_part") && f.extension.lowercase() == "mp4" && f.length() > 0L
            }?.sortedBy { it.name }.orEmpty()

            if (segOk && segmentFiles.isNotEmpty()) {
                segmentFiles.forEachIndexed { idx, segFile ->
                    results.add(
                        ProcessedMediaItem(
                            file = segFile,
                            mimeType = "video/mp4",
                            durationSeconds = probe(context, segFile)?.durationSeconds,
                            isSegment = true,
                            segmentIndex = idx + 1,
                            isOriginal = false,
                        ),
                    )
                }
                return results
            }
        }

        results.add(
            ProcessedMediaItem(
                file = statusFile,
                mimeType = guessMimeType(statusFile.name),
                durationSeconds = probe(context, statusFile)?.durationSeconds ?: probe?.durationSeconds,
                isSegment = false,
                segmentIndex = null,
                isOriginal = statusFile.absolutePath == sourceFile.absolutePath,
            ),
        )
        return results
    }

    fun guessMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun runProcessCapture(cmd: List<String>, env: Map<String, String>, timeoutSec: Long): String? {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().putAll(env)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return null
            }
            if (proc.exitValue() == 0) out else null
        } catch (_: Exception) {
            null
        }
    }

    private fun runProcessSuccess(cmd: List<String>, env: Map<String, String>, timeoutSec: Long): Boolean {
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { }
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return false
            }
            proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
