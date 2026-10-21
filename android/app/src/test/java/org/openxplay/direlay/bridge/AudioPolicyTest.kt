// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.bridge

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.AudioFormat as CarPlayAudioFormat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.openxplay.stream.core.AudioCodec
import org.openxplay.stream.core.AudioRole

class AudioPolicyTest {
    @Test fun mapsEveryRoleAndOnlyEnabledStreamNumbers() {
        for ((name, role) in mapOf("media" to AudioRole.MEDIA, "alert" to AudioRole.ALERT,
            "speechrecognition" to AudioRole.VOICE, "telephony" to AudioRole.TELEPHONY,
            "default" to AudioRole.OTHER, "compatibility" to AudioRole.OTHER, "unknown" to AudioRole.OTHER)) {
            assertEquals(role, AudioPolicy.role(name))
            if (role == AudioRole.ALERT)
                for (type in listOf(100, 101, 102)) assertNull(AudioPolicy.stream(AudioStreamId(type, name)))
            else for ((type, base) in mapOf(102 to 0, 100 to 10, 101 to 20)) assertEquals(
                base + listOf(AudioRole.MEDIA, AudioRole.VOICE, AudioRole.TELEPHONY, AudioRole.OTHER).indexOf(role),
                AudioPolicy.stream(AudioStreamId(type, name)))
        }
        assertNull(AudioPolicy.stream(AudioStreamId(103, "media")))
        assertNull(AudioPolicy.stream(AudioStreamId(999, "media")))
    }
    @Test fun onlyCanonicalMediaAacPassesThroughAndAllOtherShapesNormalize() {
        for (role in listOf("media", "speechrecognition", "telephony", "default", "compatibility"))
            for (codec in AudioCodecKind.entries) for (rate in listOf(16000, 44100, 48000)) for (channels in 1..2) {
                val id = AudioStreamId(102, role)
                val source = CarPlayAudioFormat(codec, rate, channels, 102, role)
                assertEquals(role == "media" && codec == AudioCodecKind.AAC_LC && rate == 48000 && channels == 2,
                    AudioPolicy.passthrough(id, source))
                val normalized = AudioPolicy.format(id, source)
                assertEquals(if (role == "media") AudioCodec.AAC_LC else AudioCodec.PCM_S16LE, normalized.codec)
                assertEquals(if (role == "media") 48000 else rate, normalized.sampleRate)
                assertEquals(if (role == "media") 2 else channels, normalized.channels)
                if (role == "media") assertArrayEquals(hex("11 90"), normalized.config) else assertNull(normalized.config)
            }
    }
    @Test fun everyRoleOwnsItsStreamSlotSoMusicIsNeverReplaced() {
        assertFalse(AudioPolicy.forwarded(AudioStreamId(100, "alert")))
        for (role in listOf("media", "speechrecognition", "telephony", "default"))
            assertTrue(AudioPolicy.forwarded(AudioStreamId(100, role)))
        // Music, Siri and calls all arrive as type 100; each must land in a different slot.
        val slots = listOf("media", "speechrecognition", "telephony", "default").map { AudioPolicy.stream(AudioStreamId(100, it)) }
        assertEquals(slots.size, slots.toSet().size)
    }
    @Test fun aNavigationPromptAndAnAppCallPlayOnTheHeadUnitOverTheMusic() {
        // A navigation prompt and an app call both arrive as type 100 with audioType "default".
        // The head unit plays them on its own speakers, over the music, so they belong on the
        // media channel and must be delivered as AAC like the music is (2026-10-21).
        val prompt = AudioStreamId(100, "default")
        assertTrue(AudioPolicy.playedOnHeadUnit(prompt))
        val source = CarPlayAudioFormat(AudioCodecKind.LPCM, 8000, 1, 100, "default")
        val format = AudioPolicy.format(prompt, source)
        assertEquals(AudioCodec.AAC_LC, format.codec)
        assertEquals(48000, format.sampleRate)
        assertEquals(2, format.channels)
        // The buffered stream carries short system sounds; a head unit mixes them itself.
        assertFalse(AudioPolicy.playedOnHeadUnit(AudioStreamId(102, "default")))
        // Siri and a native call keep the voice channel, and the music keeps the media channel.
        assertFalse(AudioPolicy.playedOnHeadUnit(AudioStreamId(100, "speechrecognition")))
        assertFalse(AudioPolicy.playedOnHeadUnit(AudioStreamId(100, "telephony")))
        assertTrue(AudioPolicy.playedOnHeadUnit(AudioStreamId(100, "media")))
        // The prompt keeps its own slot, so it never replaces the music.
        assertNotEquals(AudioPolicy.stream(AudioStreamId(100, "media")), AudioPolicy.stream(prompt))
    }
    @Test fun pcmSwapPreservesInterleavingAndLeavesBorrowedDataUntouched() {
        val pcm = hex("12 34 fe dc 80 00 7f ff")
        assertArrayEquals(hex("34 12 dc fe 00 80 ff 7f"), AudioPolicy.swap(pcm))
        assertArrayEquals(hex("12 34 fe dc 80 00 7f ff"), pcm)
        assertThrows(IllegalArgumentException::class.java) { AudioPolicy.swap(byteArrayOf(1)) }
    }
    @Test fun timestampsAnchorAtArrivalAdvanceBySamplesAndReanchorOnlyBeyond100ms() {
        val clock = AudioClock(48000)
        assertEquals(1_000_000L, clock.pts(900, 1_000_000))
        assertEquals(1_020_000L, clock.pts(1860, 1_080_000))
        assertEquals(1_040_000L, clock.pts(2820, 1_140_000))
        assertEquals(1_200_001L, clock.pts(3780, 1_200_001))
        assertEquals(1_220_001L, clock.pts(4740, 1_220_000))
        assertEquals(2_000_000L, AudioClock(48000).pts(4740, 2_000_000))
        val opus = AudioClock(24000)
        assertEquals(5_000_000L, opus.pts(0, 5_000_000))
        assertEquals(5_020_000L, opus.pts(480, 5_021_000))
    }
    @Test fun timestampWrapAndFractionalDurationsDoNotAccumulateRoundingError() {
        val clock = AudioClock(44100)
        val first = Int.MAX_VALUE - 100
        assertEquals(1_000_000L, clock.pts(first, 1_000_000))
        for (i in 1..10) {
            val expected = 1_000_000L + i * 1024L * 1_000_000 / 44100
            assertEquals(expected, clock.pts(first + i * 1024, expected))
        }
    }
    @Test fun resamplingIsIndependentOfPacketBoundariesAndDuplicatesMono() {
        val pcm = ByteArray(960 * 2) { (it % 127).toByte() }
        val whole = StereoResampler(16000, 1).convert(pcm)
        val split = StereoResampler(16000, 1)
        val chunks = split.convert(pcm.copyOfRange(0, 320)) + split.convert(pcm.copyOfRange(320, 962)) +
            split.convert(pcm.copyOfRange(962, pcm.size))
        assertArrayEquals(whole, chunks)
        for (offset in whole.indices step 4) assertArrayEquals(whole.copyOfRange(offset, offset + 2), whole.copyOfRange(offset + 2, offset + 4))
    }
}
