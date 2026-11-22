@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package gg.seven.cleaner

import android.content.res.Resources
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.container.NalUnitUtil
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import gg.seven.cleaner.core.*
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

data class VideoInspection(
    val facts: VideoFacts,
    val captureDate: Instant?,
    val location: Pair<Float, Float>?,
)

data class VideoCandidate(
    val source: File,
    val output: File,
    val before: VideoInspection,
    val after: VideoInspection,
    val plan: VideoCopyPlan,
    val issues: List<String>,
)

/** Native codecs and Media3 own decoding, clipping, orientation, scaling and muxing. */
object VideoCompression {
    fun inspect(res: Resources, file: File): VideoInspection {
        val extractor = MediaExtractor()
        val reader = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.path)
            reader.setDataSource(file.path)
            val tracks = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video =
                tracks.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audio =
                tracks.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (video.size != 1 || audio.size > 1 || tracks.size != video.size + audio.size)
                throw blocked(
                    res.getString(R.string.video_tracks)
                )
            val v = video.single()
            if (v.getString(MediaFormat.KEY_MIME) != MimeTypes.VIDEO_H264)
                throw blocked(
                    res.getString(R.string.video_codec)
                )
            val csd =
                v.getByteBuffer("csd-0")?.let { b ->
                    ByteArray(b.remaining()).also { b.duplicate().get(it) }
                } ?: throw blocked(res.getString(R.string.video_color_unverified))
            val start = NalUnitUtil.findNalUnit(csd, 0, csd.size, BooleanArray(3)) + 3
            if (start >= csd.size || csd[start].toInt() and 31 != 7)
                throw blocked(res.getString(R.string.video_color_unverified))
            val sps = NalUnitUtil.parseSpsNalUnit(csd, start, csd.size)
            if (sps.colorTransfer != C.COLOR_TRANSFER_SDR || sps.colorSpace != C.COLOR_SPACE_BT709)
                throw blocked(
                    res.getString(R.string.video_color_profile)
                )
            val rotation =
                reader
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            if (rotation !in listOf(0, 90, 180, 270))
                throw blocked(res.getString(R.string.video_orientation))
            val w = v.getInteger(MediaFormat.KEY_WIDTH)
            val h = v.getInteger(MediaFormat.KEY_HEIGHT)
            val duration = v.getLong(MediaFormat.KEY_DURATION) / 1000
            val a = audio.singleOrNull()
            val rate =
                if (v.containsKey(MediaFormat.KEY_FRAME_RATE))
                    runCatching { v.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                        .getOrElse {
                            runCatching { v.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }
                                .getOrNull()
                        }
                else null
            val frameRate =
                rate
                    ?: reader
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                        ?.toDoubleOrNull()
                    ?: throw blocked(res.getString(R.string.video_frame_rate))
            if (!regularTiming(extractor, tracks.indexOf(v), frameRate))
                throw blocked(res.getString(R.string.video_timing))
            val facts =
                VideoFacts(
                    (if (rotation % 180 == 0) w else h).toUInt(),
                    (if (rotation % 180 == 0) h else w).toUInt(),
                    duration.toULong(),
                    frameRate,
                    file.length().toULong(),
                    "h264",
                    (8 + maxOf(sps.bitDepthLumaMinus8, sps.bitDepthChromaMinus8)).toUInt(),
                    false,
                    !sps.frameMbsOnlyFlag ||
                        sps.pixelWidthHeightRatio != 1f ||
                        sps.separateColorPlaneFlag,
                    a?.getString(MediaFormat.KEY_MIME)?.let {
                        if (it == MimeTypes.AUDIO_AAC) "aac" else it
                    },
                    (a?.getInteger(MediaFormat.KEY_CHANNEL_COUNT) ?: 0).toUInt(),
                    (a?.getInteger(MediaFormat.KEY_SAMPLE_RATE) ?: 0).toUInt(),
                    ((a?.getLong(MediaFormat.KEY_DURATION) ?: 0) / 1000).toULong(),
                )
            val dateText = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            val date =
                dateText?.let {
                    runCatching { Instant.parse(it) }
                        .getOrElse { _ ->
                            runCatching {
                                    Instant.from(
                                        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSX")
                                            .parse(it)
                                    )
                                }
                                .getOrElse {
                                    throw blocked(
                                        res.getString(R.string.video_timestamp)
                                    )
                                }
                        }
                }
            val locationText = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val location =
                locationText?.let {
                    val m =
                        Regex("^([+-][0-9.]+)([+-][0-9.]+)(?:[+-][0-9.]+)?/?$").matchEntire(it)
                            ?: throw blocked(res.getString(R.string.video_location))
                    m.groupValues[1].toFloat() to m.groupValues[2].toFloat()
                }
            return VideoInspection(facts, date, location)
        } finally {
            extractor.release()
            reader.release()
        }
    }

    suspend fun makeCandidate(
        context: Context,
        source: File,
        directory: File,
        start: ULong,
        end: ULong,
    ): VideoCandidate {
        val res = context.resources
        val before = withContext(Dispatchers.IO) { inspect(res, source) }
        val plan = videoCopyPlan(before.facts, start, end)
        directory.mkdirs()
        if (availableBytes(context, directory).toULong() < plan.requiredFreeBytes)
            throw blocked(
                res.getString(R.string.video_storage)
            )
        val output = File(directory, "${UUID.randomUUID()}.mp4")
        try {
            encode(context, source, output, before, plan)
            val result =
                withContext(Dispatchers.IO) {
                    ensureActive()
                    val after = inspect(res, output)
                    val issues = videoCopyIssues(before.facts, after.facts, plan).toMutableList()
                    if (before.captureDate != after.captureDate)
                        issues += res.getString(R.string.video_timestamp_not_kept)
                    if (!sameLocation(before.location, after.location))
                        issues += res.getString(R.string.video_location_not_kept)
                    checkFrames(res, output, after.facts.durationMs.toLong())
                    VideoCandidate(source, output, before, after, plan, issues)
                }
            return result
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private suspend fun encode(
        context: Context,
        source: File,
        output: File,
        before: VideoInspection,
        plan: VideoCopyPlan,
    ) =
        withContext(Dispatchers.Main.immediate) {
            val item =
                EditedMediaItem.Builder(
                        MediaItem.Builder()
                            .setUri(source.toUri())
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(plan.startMs.toLong())
                                    .setEndPositionMs(plan.endMs.toLong())
                                    .build()
                            )
                            .build()
                    )
                    .setEffects(
                        Effects(
                            emptyList(),
                            listOf(
                                Presentation.createForWidthAndHeight(
                                    plan.width.toInt(),
                                    plan.height.toInt(),
                                    Presentation.LAYOUT_SCALE_TO_FIT,
                                )
                            ),
                        )
                    )
                    .build()
            val muxer =
                InAppMp4Muxer.Factory { entries ->
                    // Explicit capture metadata. Other descriptive container tags are not claimed.
                    entries.removeAll { it is Mp4TimestampData || it is Mp4LocationData }
                    before.captureDate?.let {
                        val seconds = it.epochSecond + 2_082_844_800L
                        entries.add(Mp4TimestampData(seconds, seconds))
                    }
                    before.location?.let { entries.add(Mp4LocationData(it.first, it.second)) }
                }
            val encoder =
                DefaultEncoderFactory.Builder(context)
                    .setEnableFallback(false)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(plan.bitrate.toInt()).build()
                    )
                    .build()
            var transformer: Transformer? = null
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    transformer =
                        Transformer.Builder(context)
                            .setVideoMimeType(MimeTypes.VIDEO_H264)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(encoder)
                            .setMuxerFactory(muxer)
                            .addListener(
                                object : Transformer.Listener {
                                    override fun onCompleted(
                                        composition: Composition,
                                        exportResult: ExportResult,
                                    ) {
                                        if (continuation.isActive) continuation.resume(Unit)
                                    }

                                    override fun onError(
                                        composition: Composition,
                                        exportResult: ExportResult,
                                        exportException: ExportException,
                                    ) {
                                        if (continuation.isActive)
                                            continuation.resumeWithException(
                                                blocked(
                                                    context.getString(
                                                        R.string.video_export_failed,
                                                        exportException.errorCodeName,
                                                    )
                                                )
                                            )
                                    }
                                }
                            )
                            .build()
                    continuation.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { transformer?.cancel() }
                    }
                    transformer!!.start(item, output.path)
                }
            } finally {
                // Cancellation must finish on Transformer's application looper before deleting
                // output.
                withContext(NonCancellable + Dispatchers.Main.immediate) { transformer?.cancel() }
            }
        }

    internal fun checkFrames(res: Resources, file: File, duration: Long) {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.path)
            for (ms in listOf(0L, duration / 2, (duration - 100).coerceAtLeast(0))) {
                val frame =
                    reader.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: throw blocked(res.getString(R.string.video_frame_decode))
                frame.recycle()
            }
        } finally {
            reader.release()
        }
    }

    private fun sameLocation(a: Pair<Float, Float>?, b: Pair<Float, Float>?): Boolean =
        if (a == null || b == null) a == b
        else
            kotlin.math.abs(a.first - b.first) < 0.0001f &&
                kotlin.math.abs(a.second - b.second) < 0.0001f

    internal fun availableBytes(context: Context, directory: File): Long {
        val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
        return storage.getAllocatableBytes(storage.getUuidForPath(directory))
    }

    private fun regularTiming(extractor: MediaExtractor, index: Int, rate: Double): Boolean {
        if (!rate.isFinite() || rate < 1 || rate > 30.01) return false
        extractor.selectTrack(index)
        val times = ArrayList<Long>()
        while (extractor.sampleTime >= 0) {
            times.add(extractor.sampleTime)
            if (times.size > 18_001) return false
            if (!extractor.advance()) break
        }
        times.sort()
        val period = 1_000_000.0 / rate
        return times.size >= 2 && times.first() <= 100_000 &&
            times.zipWithNext().all { (a, b) -> kotlin.math.abs((b - a) - period) <= maxOf(2000.0, period * 0.03) }
    }
}
