package org.yoshiz.app.prioritarr.backend.reconcile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Real ffmpeg seam for pulling a Whisper-ready audio track out of a
 * video. Mirrors [FfmpegSubtitleIo] (defined alongside [SubtitleExtractor]):
 * the process call lives here so the ladder's decision logic stays
 * unit-testable without ffmpeg on the box.
 */
object FfmpegAudioIo {
    private val logger = LoggerFactory.getLogger(FfmpegAudioIo::class.java)

    /**
     * Decode the first audio stream to 16 kHz mono WAV — the format
     * Whisper wants, and far smaller than the source audio.
     */
    suspend fun extractWav(file: Path, target: Path): Boolean = withContext(Dispatchers.IO) {
        val cmd = listOf(
            "ffmpeg", "-v", "error", "-y",
            "-i", file.toString(),
            "-vn", "-ac", "1", "-ar", "16000",
            "-f", "wav", target.toString(),
        )
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = p.waitFor(30, TimeUnit.MINUTES)
            if (!finished) {
                p.destroyForcibly()
                logger.warn("sub-ladder: ffmpeg audio extract timed out for {}", file)
                return@withContext false
            }
            if (p.exitValue() != 0) {
                logger.warn("sub-ladder: ffmpeg audio extract exit {} for {}", p.exitValue(), file)
                return@withContext false
            }
            true
        } catch (e: Exception) {
            logger.warn("sub-ladder: ffmpeg audio extract failed for {}: {}", file, e.message)
            false
        }
    }
}
