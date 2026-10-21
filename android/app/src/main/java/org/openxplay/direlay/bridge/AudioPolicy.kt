// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.bridge

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.AudioFormat as CarPlayAudioFormat
import org.openxplay.stream.core.AudioRole
import org.openxplay.stream.core.AudioCodec
import org.openxplay.stream.core.AudioFormat

internal object AudioPolicy {
    // CarPlay sends music, Siri, calls and system sounds as type 100 and distinguishes them only by
    // audioType. Mapping the type alone made every one of them share slot 1: a keyboard click closed
    // the music stream and a Siri reply could not resume it (2026-10-19). Each role now owns its own
    // slot, so a second stream never replaces the one that is already playing. These numbers stay
    // inside the bridge; nothing about them reaches the head unit.
    private const val BASE_BUFFERED = 0   // type 102
    private const val BASE_MAIN = 10      // type 100
    private const val BASE_ALT = 20       // type 101
    /** CarPlay's main audio stream: music, Siri, calls and navigation prompts all arrive here. */
    private const val MAIN_AUDIO = 100
    fun stream(id: AudioStreamId): Int? {
        val base = when (id.type) { 102 -> BASE_BUFFERED; 100 -> BASE_MAIN; 101 -> BASE_ALT; else -> return null }
        return base + when (role(id.audioType)) {
            AudioRole.MEDIA -> 0
            AudioRole.VOICE -> 1
            AudioRole.TELEPHONY -> 2
            AudioRole.OTHER -> 3
            AudioRole.ALERT -> return null
        }
    }
    fun role(type: String): AudioRole = when (type) {
        "media" -> AudioRole.MEDIA
        "alert" -> AudioRole.ALERT
        "speechrecognition" -> AudioRole.VOICE
        "telephony" -> AudioRole.TELEPHONY
        else -> AudioRole.OTHER
    }
    /**
     * Whether a stream is played on the head unit's speakers.
     *
     * A navigation prompt and an app call both arrive as type 100 with audioType "default" and
     * neither is music. A head unit plays a turn instruction over the music and plays the far end
     * of a call on its own speakers, so both belong on the media channel, not on the voice
     * channel: opening the voice channel makes the head unit duck or pause the music (2026-10-21).
     * The buffered stream (type 102) carries short system sounds such as keyboard clicks, which a
     * head unit mixes itself, so it stays out.
     */
    fun playedOnHeadUnit(id: AudioStreamId): Boolean = when (role(id.audioType)) {
        AudioRole.MEDIA -> true
        AudioRole.OTHER -> id.type == MAIN_AUDIO
        else -> false
    }
    /**
     * System sounds (keyboard clicks, alerts) are mixed by a real head unit. Forwarding them makes
     * Carlink duck the media channel, and they must never take a stream slot either.
     */
    fun forwarded(id: AudioStreamId): Boolean = role(id.audioType) != AudioRole.ALERT
    fun passthrough(id: AudioStreamId, source: CarPlayAudioFormat): Boolean =
        role(id.audioType) == AudioRole.MEDIA && source.codec == AudioCodecKind.AAC_LC &&
            source.sampleRate == 48_000 && source.channels == 2
    fun format(id: AudioStreamId, source: CarPlayAudioFormat): AudioFormat {
        val role = role(id.audioType)
        // Everything the head unit plays is delivered as AAC at 48 kHz in stereo, because the
        // transport carries one audio PID and the head unit decodes AAC there. A navigation prompt
        // arrives as 8 kHz PCM and an app call as PCM as well; both are resampled and encoded on
        // the way out. The voice channel keeps PCM, which is what it expects.
        return if (playedOnHeadUnit(id)) AudioFormat(requireNotNull(stream(id)), role,
            AudioCodec.AAC_LC, 48_000, 2, byteArrayOf(0x11, 0x90.toByte()))
        else AudioFormat(requireNotNull(stream(id)), role, AudioCodec.PCM_S16LE, source.sampleRate, source.channels)
    }
    fun swap(pcm: ByteArray): ByteArray {
        require(pcm.size % 2 == 0)
        return pcm.copyOf().also { result ->
            for (i in result.indices step 2) { result[i] = pcm[i + 1]; result[i + 1] = pcm[i] }
        }
    }
}

/** RTP wraps at 32 bits; anchors use the same System.nanoTime clock as relay video. */
internal class AudioClock(private val rate: Int) {
    private var sample: Int? = null
    private var anchorUs = 0L
    private var elapsed = 0L
    fun pts(sample: Int, arrivalUs: Long): Long {
        val previous = this.sample
        if (previous == null) { anchorUs = arrivalUs; elapsed = 0 }
        else elapsed += (sample - previous).toLong()
        this.sample = sample
        var pts = anchorUs + elapsed * 1_000_000L / rate
        if (kotlin.math.abs(pts - arrivalUs) > 100_000) {
            anchorUs = arrivalUs; elapsed = 0; pts = arrivalUs
        }
        return pts
    }
}

/** Streaming linear interpolation retains phase and the boundary sample across packets. */
internal class StereoResampler(private val rate: Int, private val channels: Int) {
    private var pending = ShortArray(0)
    private var position = 0L
    fun convert(pcm: ByteArray): ByteArray {
        require(channels in 1..2 && pcm.size % (channels * 2) == 0)
        if (rate == 48_000) {
            if (channels == 2) return pcm.copyOf()
            return ByteArray(pcm.size * 2).also { output ->
                for (i in pcm.indices step 2) {
                    output[i * 2] = pcm[i]; output[i * 2 + 1] = pcm[i + 1]
                    output[i * 2 + 2] = pcm[i]; output[i * 2 + 3] = pcm[i + 1]
                }
            }
        }
        val samples = ShortArray(pending.size + pcm.size / 2)
        pending.copyInto(samples)
        for (i in 0 until pcm.size / 2) samples[pending.size + i] =
            ((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
        val frames = samples.size / channels
        val output = java.io.ByteArrayOutputStream()
        while (position / 48_000 + 1 < frames) {
            val frame = (position / 48_000).toInt()
            val fraction = position % 48_000
            for (channel in 0..1) {
                val c = minOf(channel, channels - 1)
                val a = samples[frame * channels + c].toInt()
                val b = samples[(frame + 1) * channels + c].toInt()
                val value = a + ((b - a) * fraction / 48_000).toInt()
                output.write(value and 255); output.write((value ushr 8) and 255)
            }
            position += rate
        }
        val consumed = minOf((position / 48_000).toInt(), frames)
        pending = samples.copyOfRange(consumed * channels, samples.size)
        position -= consumed * 48_000L
        return output.toByteArray()
    }
}
