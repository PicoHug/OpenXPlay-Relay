// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-16
package org.openxplay.direlay.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import org.openxplay.stream.core.*
import org.openxplay.stream.core.codec.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** A bounded, surface-to-surface hardware pipeline. Codec calls belong exclusively to the worker. */
class MediaCodecPipeline(
    private val input: VideoFormat,
    private val target: VideoCompatibility.Target,
    private val listener: VideoPipeline.Listener,
) : VideoPipeline {
    private val running = AtomicBoolean(false)
    private val sync = AtomicBoolean(true)
    private val recovery = AtomicBoolean(false)
    private val frames = java.util.ArrayDeque<VideoFrame>()
    private var queuedBytes = 0
    private var waitingForIdr = true
    private var waitingSince = 0L
    private var seenIdr = false
    private var stalled = false
    private var recoveredAt = 0L
    @Volatile private var pendingDepth = 0
    private var decoder: MediaCodec? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null

    override fun start() {
        check(running.compareAndSet(false, true))
        Thread(::run, "relay-video-transcoder").apply { isDaemon = true; start() }
    }
    override fun offer(frame: VideoFrame) {
        synchronized(frames) {
            if (!running.get()) return
            if (frame.data.size > MAX_FRAME_BYTES) return
            val now = System.nanoTime()
            // The input only guarantees an IDR at session start. After that a wait for one may never
            // end (the CarPlay source does not re-IDR on request), which used to freeze this pipeline
            // for good at ~2 s and drop the session back to passthrough: black screen (2026-10-19).
            if (!seenIdr) {
                if (!frame.keyFrame) return
                seenIdr = true; waitingForIdr = false
            } else if (waitingForIdr) {
                if (frame.keyFrame || now - waitingSince >= IDR_WAIT_NS) waitingForIdr = false else return
            }
            // Never clear the whole backlog: dropping every queued frame breaks the decoder reference
            // chain and re-enters the keyframe wait above. Shed the oldest frames instead.
            var shed = false
            while (frames.isNotEmpty() && (frames.size >= MAX_QUEUED_FRAMES ||
                    queuedBytes + frame.data.size > MAX_QUEUED_BYTES)) {
                queuedBytes -= frames.pollFirst()!!.data.size; shed = true
            }
            frames.addLast(frame); queuedBytes += frame.data.size
            // A shed frame leaves a hole in the reference chain; one IDR per second repairs it.
            if (shed && now - recoveredAt >= RECOVERY_INTERVAL_NS) { recoveredAt = now; recovery.set(true) }
        }
    }
    override fun requestKeyFrame() { sync.set(true) }
    override fun close() {
        running.set(false)
        synchronized(frames) { frames.clear(); queuedBytes = 0 }
    }

    private fun run() {
        try {
            setup()
            val dec = checkNotNull(decoder)
            val enc = checkNotNull(encoder)
            val info = MediaCodec.BufferInfo()
            val pending = linkedMapOf<Long, VideoFrame>()
            var outputFormat: VideoFormat? = null
            var progressAt = System.nanoTime()
            while (running.get()) {
                if (recovery.getAndSet(false)) listener.inputKeyFrame()
                if (sync.getAndSet(false)) enc.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
                // Bound frames inside the codecs as well as at the receive boundary.
                pendingDepth = pending.size
                if (pending.size < MAX_PENDING) {
                    val available = synchronized(frames) { frames.isNotEmpty() }
                    if (available) {
                        val index = dec.dequeueInputBuffer(0)
                        if (index >= 0) {
                            val frame = synchronized(frames) {
                                frames.pollFirst()?.also { queuedBytes -= it.data.size }
                            }
                            if (frame == null) dec.queueInputBuffer(index, 0, 0, 0, 0)
                            else {
                                val bytes = Nal.avccToAnnexB(frame.data)
                                val buffer = checkNotNull(dec.getInputBuffer(index))
                                require(buffer.capacity() >= bytes.size) { "decoder_input_too_large" }
                                buffer.clear(); buffer.put(bytes)
                                if (pending.isEmpty()) progressAt = System.nanoTime()
                                pending[frame.ptsUs] = frame
                                dec.queueInputBuffer(index, 0, bytes.size, frame.ptsUs, 0)
                            }
                        }
                    }
                }
                val decoded = dec.dequeueOutputBuffer(info, 1_000)
                if (decoded >= 0) {
                    // Surface timestamps are nanoseconds; preserve the input clock for audio sync.
                    dec.releaseOutputBuffer(decoded, info.presentationTimeUs * 1000)
                }
                val encoded = enc.dequeueOutputBuffer(info, 1_000)
                if (encoded == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputFormat = CodecConfiguration.fromEncoder(input.screen, target.codec, enc.outputFormat)
                    listener.format(outputFormat)
                } else if (encoded >= 0) {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val source = pending.remove(info.presentationTimeUs)
                            // Real-time encoders can discard older frames; do not retain their metadata.
                            pending.keys.removeAll { it < info.presentationTimeUs }
                            progressAt = System.nanoTime()
                            if (source != null && outputFormat != null) {
                                val buffer = checkNotNull(enc.getOutputBuffer(encoded)).duplicate()
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size).also { buffer.get(it) }
                                val avcc = CodecConfiguration.accessUnit(bytes)
                                listener.frame(VideoFrame(input.screen, source.session, avcc, source.ptsUs,
                                    Nal.isKeyFrame(avcc, target.codec), source.receivedAtNanos))
                            }
                        }
                    } finally { enc.releaseOutputBuffer(encoded, false) }
                }
                // A pause between Wi-Fi batches is normal; ask the source for an IDR first and only
                // declare the pipeline dead at 15 s. Failing here used to drop the whole session
                // back to passthrough, which the receiver cannot decode (2026-10-19).
                if (pending.isEmpty()) stalled = false else {
                    val idle = System.nanoTime() - progressAt
                    // The encoder can sit on its output until the surface is fed again. Releasing one
                    // pending slot keeps the decoder running instead of deadlocking behind the gate above.
                    if (idle > 400_000_000L && pending.size >= MAX_PENDING) {
                        pending.keys.minOrNull()?.let { pending.remove(it) }
                    }
                    if (idle > 1_000_000_000L) {
                        if (!stalled) {
                            stalled = true
                            android.util.Log.w("DiRelayCarlink", "transcoder idle ${idle / 1_000_000}ms pending=${pending.size}")
                            listener.inputKeyFrame()
                        }
                        check(idle < 15_000_000_000L) { "transcoder_stalled" }
                    } else stalled = false
                }
            }
        } catch (failure: Exception) {
            // The message is what tells a stall apart from a codec rejection in logcat.
            android.util.Log.w("DiRelayCarlink", "transcoder stopped ${failure.javaClass.simpleName}: " +
                "${failure.message} pending=$pendingDepth queued=${frames.size}")
            if (running.get()) listener.failed(failure)
        } finally {
            running.set(false)
            release()
            synchronized(frames) { frames.clear(); queuedBytes = 0 }
        }
    }

    private fun setup() {
        val mime = mime(target.codec)
        val profiles = if (target.codec == VideoCodec.H264 && target.profile == 66)
            listOf(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        else listOf(when (target.profile) {
            77 -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
            100 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            else -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        })
        var last: Exception? = null
        // Some vendors reject optional latency hints or ConstrainedBaseline despite supporting Baseline.
        for (profile in profiles) for (hints in listOf(true, false)) {
            try {
                val format = MediaFormat.createVideoFormat(mime, input.width, input.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_PROFILE, profile)
                    setInteger(MediaFormat.KEY_LEVEL, level(target))
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate(target))
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger("max-bframes", 0)
                    if (hints) {
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        setInteger(MediaFormat.KEY_OPERATING_RATE, 30)
                        if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    }
                }
                encoder = createHardware(mime, true)
                encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = encoder!!.createInputSurface()
                encoder!!.start()
                val decoderFormat = MediaFormat.createVideoFormat(mime(input.codec), input.width, input.height)
                val sets = when (input.codec) {
                    VideoCodec.H264 -> AvcConfig.parse(input.config).let { listOf(it.sps, it.pps) }
                    VideoCodec.H265 -> HevcConfig.parse(input.config).let { listOf(it.vps + it.sps + it.pps) }
                }
                sets.forEachIndexed { index, nals ->
                    decoderFormat.setByteBuffer("csd-$index", ByteBuffer.wrap(CodecConfiguration.annexB(nals)))
                }
                decoder = createHardware(mime(input.codec), false)
                decoder!!.configure(decoderFormat, surface, null, 0)
                decoder!!.start()
                return
            } catch (failure: Exception) { last = failure; release() }
        }
        throw checkNotNull(last)
    }

    private fun release() {
        listOfNotNull(decoder, encoder).forEach {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        decoder = null; encoder = null
        surface?.release(); surface = null
    }

    companion object {
        /** Frames allowed outside the codecs; the oldest is shed beyond this, the queue is never wiped. */
        private const val MAX_QUEUED_FRAMES = 16
        private const val MAX_QUEUED_BYTES = 8 * 1024 * 1024
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        /** Longest a reset may wait for an IDR before resuming with whatever the input sends. */
        private const val IDR_WAIT_NS = 700_000_000L
        private const val RECOVERY_INTERVAL_NS = 1_000_000_000L
        private const val MAX_PENDING = 8
        val factory = VideoPipeline.Factory { input, target, listener -> MediaCodecPipeline(input, target, listener) }
        fun hasHevcDecoder(): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { !it.isEncoder &&
                it.supportedTypes.any { type -> type.equals("video/hevc", ignoreCase = true) } }
        }.getOrDefault(false)
        private fun mime(codec: VideoCodec) = if (codec == VideoCodec.H264) "video/avc" else "video/hevc"
        private fun createHardware(mime: String, encode: Boolean): MediaCodec {
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                it.isEncoder == encode && it.supportedTypes.any { type -> type.equals(mime, true) } &&
                    if (Build.VERSION.SDK_INT >= 29) it.isHardwareAccelerated
                    else !it.name.startsWith("OMX.google.") && !it.name.startsWith("c2.android.")
            } ?: error("hardware_codec_unavailable")
            return MediaCodec.createByCodecName(info.name)
        }
        private fun level(target: VideoCompatibility.Target): Int {
            val values = if (target.codec == VideoCodec.H264)
                listOf(10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52, 60, 61, 62)
            else listOf(30, 60, 63, 90, 93, 120, 123, 150, 153, 156, 180, 183, 186)
            val index = values.indexOf(target.level)
            require(index >= 0)
            // AVC has Level1b between Level1 and Level11; HEVC alternates Main/High tier bits.
            return if (target.codec == VideoCodec.H264) 1 shl (if (index == 0) 0 else index + 1)
            else 1 shl (index * 2)
        }
        private fun bitrate(target: VideoCompatibility.Target): Int =
            if (target.codec == VideoCodec.H265) 4_000_000 else when {
                target.level <= 10 -> 64_000
                target.level <= 12 -> 192_000
                target.level <= 20 -> 384_000
                target.level <= 22 -> 2_000_000
                target.level <= 30 -> 4_000_000
                else -> 6_000_000
            }
    }
}

/** Encoder CSD uses Annex B; the Stream contract uses avcC/hvcC and four-byte NAL lengths. */
internal object CodecConfiguration {
    fun annexB(nals: List<ByteArray>): ByteArray = nals.fold(ByteArray(0)) { data, nal -> data + byteArrayOf(0, 0, 0, 1) + nal }
    fun accessUnit(bytes: ByteArray): ByteArray {
        // Prefer AVCC when both interpretations are possible (a one-byte NAL has length 00 00 00 01).
        return try { Nal.units(bytes).toList(); bytes }
        catch (_: IllegalArgumentException) { Nal.annexBToAvcc(bytes) }
    }
    fun fromEncoder(screen: ScreenId, codec: VideoCodec, format: MediaFormat): VideoFormat {
        val nals = (0..2).flatMap { index ->
            format.getByteBuffer("csd-$index")?.duplicate()?.let { buffer ->
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                Nal.units(accessUnit(bytes)).toList()
            }.orEmpty()
        }
        return VideoFormat.create(screen, codec, build(codec, nals), 30.0)
    }
    fun build(codec: VideoCodec, nals: List<ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        fun parameter(nal: ByteArray) { require(nal.size <= 65535); out.writeShort(nal.size); out.write(nal) }
        if (codec == VideoCodec.H264) {
            val sps = nals.filter { it[0].toInt() and 31 == 7 }
            val pps = nals.filter { it[0].toInt() and 31 == 8 }
            require(sps.isNotEmpty() && pps.isNotEmpty() && sps.size <= 31 && pps.size <= 255)
            out.writeByte(1); out.write(sps.first(), 1, 3); out.writeByte(255)
            out.writeByte(224 or sps.size); sps.forEach(::parameter)
            out.writeByte(pps.size); pps.forEach(::parameter)
        } else {
            val groups = (32..34).map { type -> nals.filter { (it[0].toInt() ushr 1) and 63 == type } }
            require(groups.all { it.isNotEmpty() })
            // SPS starts with one byte of sublayer flags followed by general profile_tier_level.
            val rbsp = mutableListOf<Byte>()
            var zeros = 0
            for (byte in groups[1].first().drop(2)) {
                if (zeros >= 2 && byte == 3.toByte()) { zeros = 0; continue }
                rbsp.add(byte); zeros = if (byte == 0.toByte()) zeros + 1 else 0
            }
            require(rbsp.size >= 13)
            out.writeByte(1); out.write(rbsp.subList(1, 13).toByteArray())
            out.writeShort(0xf000); out.writeByte(0xfc); out.writeByte(0xfd)
            out.writeByte(0xf8); out.writeByte(0xf8); out.writeShort(0)
            out.writeByte(0x0f); out.writeByte(3)
            groups.forEachIndexed { index, group ->
                out.writeByte(0x80 or (32 + index)); out.writeShort(group.size); group.forEach(::parameter)
            }
        }
        return bytes.toByteArray()
    }
}
