package gg.seven.cleaner

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VideoCompressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun nativeTrimKeepsAudioUprightFramesMetadataAndOriginal() = runBlocking {
        val folder = File(context.cacheDir, "video-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(folder, "source.mp4")
            instrumentation.context.assets.open("video-profile.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val original = source.readBytes()
            val result = VideoCompression.makeCandidate(context, source, folder, 1250u, 4750u)
            assertTrue(result.issues.joinToString(), result.issues.isEmpty())
            assertEquals(720u, result.after.facts.width)
            assertEquals(1280u, result.after.facts.height)
            assertEquals(2u, result.after.facts.audioChannels)
            assertEquals(48000u, result.after.facts.audioSampleRate)
            assertAudioDecodes(result.output)
            assertTrue(result.after.facts.durationMs in 3400uL..3600uL)
            assertTrue(
                "Fixture must exercise actual size reduction",
                result.output.length() < source.length(),
            )
            assertArrayEquals(original, source.readBytes())
            // A non-keyframe start must show the selected source time, not the preceding keyframe.
            val inputReader = MediaMetadataRetriever()
            val outputReader = MediaMetadataRetriever()
            try {
                inputReader.setDataSource(source.path)
                outputReader.setDataSource(result.output.path)
                for (offset in listOf(0L, 1700L, 3300L)) {
                    val a =
                        inputReader.getScaledFrameAtTime(
                            (1250 + offset) * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            90,
                            160,
                        )!!
                    val b =
                        outputReader.getScaledFrameAtTime(
                            offset * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            90,
                            160,
                        )!!
                    var difference = 0L
                    for (y in 0 until a.height) for (x in 0 until a.width) {
                        val p = a.getPixel(x, y)
                        val q = b.getPixel(x, y)
                        difference +=
                            kotlin.math.abs(
                                android.graphics.Color.red(p) - android.graphics.Color.red(q)
                            )
                        difference +=
                            kotlin.math.abs(
                                android.graphics.Color.green(p) - android.graphics.Color.green(q)
                            )
                        difference +=
                            kotlin.math.abs(
                                android.graphics.Color.blue(p) - android.graphics.Color.blue(q)
                            )
                    }
                    val samples = a.width * a.height * 3
                    a.recycle()
                    b.recycle()
                    assertTrue(
                        "Selected frame mismatch at $offset ms: $difference",
                        difference.toDouble() / samples < 18,
                    )
                }
            } finally {
                inputReader.release()
                outputReader.release()
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    private fun assertAudioDecodes(file: File) {
        val extractor = android.media.MediaExtractor()
        var decoder: android.media.MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            val format = extractor.getTrackFormat(track); extractor.selectTrack(track)
            val codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            decoder = codec; codec.configure(format, null, null, 0); codec.start()
            var inputDone = false; var outputDone = false; var decodedBytes = 0L; var audible = false
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            val info = android.media.MediaCodec.BufferInfo()
            while (!outputDone && android.os.SystemClock.elapsedRealtime() < deadline) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    for (i in info.offset until info.offset + info.size) if (buffer.get(i).toInt() != 0) audible = true
                    decodedBytes += info.size
                    outputDone = info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("AAC must decode to a complete audible PCM stream", outputDone && decodedBytes > 100_000 && audible)
        } finally { decoder?.release(); extractor.release() }
    }

    @Test
    fun cancellingNativeExportRemovesOnlyItsTemporaryOutput() = runBlocking {
        val folder = File(context.cacheDir, "video-cancel-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source = File(folder, "source.mp4")
            instrumentation.context.assets.open("video-profile.mp4").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val bytes = source.readBytes()
            val job = launch { VideoCompression.makeCandidate(context, source, folder, 0u, 6000u) }
            delay(150)
            job.cancelAndJoin()
            assertEquals(listOf("source.mp4"), folder.listFiles()!!.map { it.name })
            assertArrayEquals(bytes, source.readBytes())
        } finally {
            folder.deleteRecursively()
        }
    }
}
