// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.bridge

import android.media.MediaFormat
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.AudioFormat as CarPlayAudioFormat
import org.openxplay.stream.core.AudioRole
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded receive queue and codec worker per stream run. Close never joins the worker. */
internal class RelayAudioStream(
    private val id: AudioStreamId,
    private val source: CarPlayAudioFormat,
    private val output: (ByteArray, Long) -> Unit,
    private val failed: () -> Unit,
    private val delegate: MediaSink,
    private val playLocally: () -> Boolean,
    private val firstSample: Int,
    private var predecessor: RelayAudioStream? = null,
) : AutoCloseable {
    private data class Packet(val rtp: ByteArray, val sample: Int, val pts: Long) { val payload get() = rtp.copyOfRange(12, rtp.size) }
    private val ended = java.util.concurrent.CountDownLatch(1)
    private val queue = ArrayBlockingQueue<Packet>(25)
    private val running = AtomicBoolean(true)
    private val clock = AudioClock(source.rtpClockRate)
    private val passthrough = AudioPolicy.passthrough(id, source)
    // A stream the head unit plays is encoded to AAC for the transport's single audio PID, the
    // same way the music is. A navigation prompt and an app call reach the head unit that way, so
    // the head unit plays them over the music instead of ducking it.
    private val media = AudioPolicy.playedOnHeadUnit(id)
    private val worker = Thread({ run() }, "direlay-audio-${id.type}").apply { isDaemon = true }
    fun start() = worker.start()
    fun offer(rtp: ByteArray, sample: Int, arrivalUs: Long) {
        if (!running.get() || rtp.size <= 12) return
        val packet = Packet(rtp.copyOf(), sample, clock.pts(sample, arrivalUs))
        if (!queue.offer(packet)) {
            queue.poll(); queue.offer(packet)
            Log.w("DiRelayAudio", "Receive queue full; dropped oldest packet")
        }
    }
    private fun run() {
        var localStarted = false
        var decoder: RelayAudioCodec? = null
        var encoder: RelayAudioCodec? = null
        try {
            predecessor?.ended?.await(); predecessor = null
            if (!running.get()) return
            if (playLocally()) { delegate.onAudioStarted(id, source, firstSample); localStarted = true }
            if (!passthrough && source.codec != AudioCodecKind.LPCM) decoder = RelayAudioCodec(RelayAudioCodec.decoder(source), false)
            if (media && !passthrough) encoder = RelayAudioCodec(
                RelayAudioCodec.encoder(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2, 192_000), true)
            var resampler = StereoResampler(source.sampleRate, source.channels)
            var buffered = ByteArray(0)
            var pcmAnchor: Long? = null
            var pcmSamples = 0L
            var firstDecoderPts: Long? = null
            var decoderOffset: Long? = null
            var firstEncoderPts: Long? = null
            var encoderOffset: Long? = null
            fun encoded(data: ByteArray, pts: Long) {
                // AAC encoder priming can shift its first PTS backwards by one access unit.
                if (encoderOffset == null) encoderOffset = requireNotNull(firstEncoderPts) - pts
                output(data, pts + encoderOffset!!)
            }
            fun pcm(data: ByteArray, pts: Long) {
                if (!media) { output(data, pts); return }
                if (pcmAnchor == null || kotlin.math.abs(pcmAnchor!! + pcmSamples * 1_000_000 / 48_000 - pts) > 100_000) {
                    buffered = ByteArray(0); pcmAnchor = pts; pcmSamples = 0
                    resampler = StereoResampler(source.sampleRate, source.channels)
                }
                buffered += resampler.convert(data)
                while (buffered.size >= 4096) {
                    val inputPts = pcmAnchor!! + pcmSamples * 1_000_000 / 48_000
                    if (firstEncoderPts == null) firstEncoderPts = inputPts
                    encoder!!.offer(buffered.copyOfRange(0, 4096), inputPts)
                    buffered = buffered.copyOfRange(4096, buffered.size)
                    pcmSamples += 1024
                    encoder!!.pump(::encoded)
                }
            }
            fun decoded(data: ByteArray, pts: Long) {
                if (decoderOffset == null) decoderOffset = requireNotNull(firstDecoderPts) - pts
                pcm(data, pts + decoderOffset!!)
            }
            while (running.get()) {
                val packet = queue.poll(5, TimeUnit.MILLISECONDS)
                if (!playLocally() && localStarted) { delegate.onAudioStopped(id); localStarted = false }
                if (packet != null && playLocally()) {
                    if (!localStarted) { delegate.onAudioStarted(id, source, packet.sample); localStarted = true }
                    delegate.onAudioRtp(id, source, packet.rtp, packet.sample)
                }
                if (packet != null) when {
                    passthrough -> output(packet.payload, packet.pts)
                    source.codec == AudioCodecKind.LPCM -> pcm(AudioPolicy.swap(packet.payload), packet.pts)
                    else -> {
                        if (firstDecoderPts == null) firstDecoderPts = packet.pts
                        decoder!!.offer(if (source.codec == AudioCodecKind.AAC_LC)
                            RelayAudioCodec.adts(packet.payload, source) else packet.payload, packet.pts)
                    }
                }
                decoder?.pump(::decoded) { actual ->
                    require(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == source.sampleRate &&
                        actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == source.channels) { "Unexpected decoded audio format" }
                    if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) require(
                        actual.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_16BIT)
                }
                encoder?.pump(::encoded)
            }
        } catch (failure: Exception) {
            if (running.get()) { Log.e("DiRelayAudio", "Audio normalization failed", failure); failed() }
        } finally {
            running.set(false)
            if (localStarted) runCatching { delegate.onAudioStopped(id) }
            runCatching { decoder?.close() }; runCatching { encoder?.close() }
            ended.countDown()
        }
    }
    override fun close() { running.set(false); queue.clear(); worker.interrupt() }
}
