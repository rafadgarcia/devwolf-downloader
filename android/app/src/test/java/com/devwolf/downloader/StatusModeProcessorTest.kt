package com.devwolf.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusModeProcessorTest {

    @Test
    fun detectsWhatsAppStatusCompliantMp4AndUsesCopy() {
        val compliantJson = """
            {
              "format": { "format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "22.5" },
              "streams": [
                { "codec_type": "video", "codec_name": "h264", "pix_fmt": "yuv420p", "width": 1080, "height": 1920 },
                { "codec_type": "audio", "codec_name": "aac" }
              ]
            }
        """.trimIndent()
        val probe = StatusModeProcessor.parseProbeJson(compliantJson)
        assertNotNull(probe)
        assertTrue(probe!!.isWhatsAppStatusCompliant())

        val args = StatusModeProcessor.buildStatusTranscodeArgs("/bin/ffmpeg", "/in.mp4", "/out.mp4", probe)
        assertTrue(args.contains("-c"))
        assertTrue(args.contains("copy"))
        assertFalse(args.contains("libx264"))
    }

    @Test
    fun transcodesNonCompliantVideoPreservingAspectRatio() {
        val webmJson = """
            {
              "format": { "format_name": "matroska,webm", "duration": "45.0" },
              "streams": [
                { "codec_type": "video", "codec_name": "vp9", "pix_fmt": "yuv420p", "width": 1081, "height": 1921 },
                { "codec_type": "audio", "codec_name": "opus" }
              ]
            }
        """.trimIndent()
        val probe = StatusModeProcessor.parseProbeJson(webmJson)
        assertNotNull(probe)
        assertFalse(probe!!.isWhatsAppStatusCompliant())

        val args = StatusModeProcessor.buildStatusTranscodeArgs("/bin/ffmpeg", "/in.webm", "/out.mp4", probe)
        assertTrue(args.contains("libx264"))
        assertTrue(args.contains("aac"))
        assertTrue(args.contains("scale=trunc(iw/2)*2:trunc(ih/2)*2"))
        assertTrue(args.contains("+faststart"))
    }

    @Test
    fun buildsSegmentSplittingCommandSafely() {
        val args = StatusModeProcessor.buildSegmentArgs("/bin/ffmpeg", "/in.mp4", "/dir/part%02d.mp4", 30)
        assertEquals(
            listOf(
                "/bin/ffmpeg",
                "-y",
                "-i", "/in.mp4",
                "-c", "copy",
                "-map", "0",
                "-f", "segment",
                "-segment_time", "30",
                "-reset_timestamps", "1",
                "-movflags", "+faststart",
                "/dir/part%02d.mp4",
            ),
            args,
        )
    }

    @Test
    fun parsesYtdlpProgressHonestlyWithoutFakingIndeterminatePercent() {
        val knownTotal = DevwolfDownloadService.parseYtdlpProgressLine(
            "[download]  42.5% of   10.00MiB at    2.00MiB/s ETA 00:03",
        )
        assertNotNull(knownTotal)
        assertFalse(knownTotal!!.isIndeterminate)
        assertEquals(42.5, knownTotal.percent!!, 0.01)
        assertEquals(10_485_760L, knownTotal.totalBytes)
        assertEquals(2_097_152L, knownTotal.speedBytesPerSec)
        assertEquals(3L, knownTotal.etaSeconds)

        val unknownTotal = DevwolfDownloadService.parseYtdlpProgressLine(
            "[download]   2.00MiB at  512.00KiB/s (00:04)",
        )
        assertNotNull(unknownTotal)
        assertTrue(unknownTotal!!.isIndeterminate)
        assertNull(unknownTotal.totalBytes)
        assertNull(unknownTotal.percent)
        assertEquals(2_097_152L, unknownTotal.bytesDownloaded)
    }
}
