// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-16
package org.openxplay.direlay.video

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.openxplay.direlay.bridge.avcConfig
import org.openxplay.direlay.bridge.hevcConfig
import org.openxplay.stream.core.*
import org.openxplay.direlay.video.VideoCompatibility.Mode.*

internal fun capabilities(vararg decoders: String) = OutputCapabilities(LatencyClass.INTERACTIVE,
    decoders.map { DecoderSupport(it, true) },
    mapOf(ScreenId.MAIN to DisplayInfo(640, 480, 160, 30.0)), 2, emptySet(), emptySet(), false)
internal fun avc() = VideoFormat.create(ScreenId.MAIN, VideoCodec.H264, avcConfig)
// This changes avcC's advertised profile, sufficient for the pure negotiation policy.
internal fun high() = VideoFormat.create(ScreenId.MAIN, VideoCodec.H264, avcConfig.also { it[1] = 100; it[2] = 0; it[3] = 31 })

class VideoCompatibilityTest {
    @Test fun experimentalCarlinkMain41DecoderAllowsHevcPassthrough() {
        val input = VideoFormat.create(ScreenId.MAIN, VideoCodec.H265, hevcConfig.also { it[12] = 123 })
        val avcOnly = capabilities("avc1.42E01F").decoders
        assertEquals(TRANSCODE, VideoCompatibility.decide(input, avcOnly).mode)
        assertEquals(PASSTHROUGH, VideoCompatibility.decide(input,
            avcOnly + DecoderSupport("hvc1.1.6.L123.B0", true)).mode)
    }
    @Test fun policyTable() {
        val high = high()
        val hevc = VideoFormat.create(ScreenId.MAIN, VideoCodec.H265, hevcConfig)
        val cases = listOf(
            Triple(high, listOf("avc1.42E01F"), TRANSCODE),
            Triple(high, listOf("avc1.64001F"), PASSTHROUGH),
            Triple(high, listOf("avc1.64001E"), TRANSCODE),
            Triple(avc(), listOf("avc1.64001F"), PASSTHROUGH),
            Triple(avc(), listOf("avc1.42E01F"), PASSTHROUGH),
            Triple(high, listOf("hvc1.1.6.L120.B0"), TRANSCODE),
            Triple(hevc, listOf(hevc.codecString), PASSTHROUGH),
            Triple(high, emptyList(), UNSUPPORTED),
            Triple(high, listOf("vp09.00.10.08", "avc1.bad", "hvc1.bad"), UNSUPPORTED),
        )
        cases.forEach { (input, decoders, expected) ->
            assertEquals(expected, VideoCompatibility.decide(input, capabilities(*decoders.toTypedArray()).decoders).mode,
                "${input.codecString} -> $decoders")
        }
        val baseline = VideoCompatibility.decide(high, capabilities("avc1.42E01F").decoders).target!!
        assertEquals(66, baseline.profile)
        assertEquals(31, baseline.level)
        assertEquals(VideoCodec.H265, VideoCompatibility.decide(high, capabilities("hvc1.1.6.L120.B0").decoders).target!!.codec)
    }

    @Test fun prefersBaselineThenMainThenHighRegardlessOfListingOrder() {
        val input = VideoFormat.create(ScreenId.MAIN, VideoCodec.H265, hevcConfig)
        val decoders = capabilities("avc1.64001F", "avc1.4D001F", "avc1.42E01F").decoders
        assertEquals(66, VideoCompatibility.decide(input, decoders).target!!.profile)
        assertEquals(77, VideoCompatibility.decide(input, decoders.take(2)).target!!.profile)
        assertEquals(100, VideoCompatibility.decide(input, decoders.take(1)).target!!.profile)
    }

    @Test fun hevcRequiresEveryHardwareReceiverAndNoAvcOutput() {
        val hevc = capabilities("hvc1.1.6.L120.B0")
        assertTrue(requestHevc(listOf(hevc, hevc)))
        assertFalse(requestHevc(listOf(hevc), requiresAvc = true))
        assertFalse(requestHevc(listOf(hevc, capabilities("avc1.42E01F"))))
        assertFalse(requestHevc(listOf(hevc, capabilities())))
        assertFalse(requestHevc(emptyList()))
    }
}
