// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19
package org.openxplay.direlay.video

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.openxplay.direlay.settings.VideoCodecSetting
import org.openxplay.stream.core.*

class RelayVideoCodecTest {
    private val hevc = capabilities("hvc1.1.6.L123.B0")
    private fun decision(setting: VideoCodecSetting = VideoCodecSetting.AUTO,
        outputs: List<OutputCapabilities> = listOf(hevc), avc: Boolean = false,
        failed: Boolean = false, remembered: VideoCodec? = null) =
        relayVideoCodec(setting, outputs, avc, failed, remembered)

    @Test fun carlinkRequiresAvcOnlyUntilItsSessionReportsHevc() {
        assertTrue(carlinkRequiresAvc(true, emptyList()))
        assertTrue(carlinkRequiresAvc(true, capabilities("avc1.42E01F").decoders))
        assertFalse(carlinkRequiresAvc(true, hevc.decoders))
        assertFalse(carlinkRequiresAvc(false, emptyList()))
        val decision = relayVideoCodec(VideoCodecSetting.AUTO, listOf(hevc),
            carlinkRequiresAvc(true, hevc.decoders), false, null, carlinkHevc = true)
        assertEquals(VideoCodecDecision(VideoCodec.H265, "carlink-hevc"), decision)
        val browser = capabilities("avc1.42E01F")
        assertEquals(VideoCodec.H264, relayVideoCodec(VideoCodecSetting.AUTO, listOf(hevc, browser),
            false, false, null, carlinkHevc = true).codec)
    }
    @Test fun forcedChoicesOverrideReceiversCarlinkAndFailure() {
        for (outputs in listOf(emptyList(), listOf(hevc), listOf(capabilities("avc1.42E01F"))))
            for (avc in listOf(false, true)) for (failed in listOf(false, true)) {
                assertEquals(VideoCodecDecision(VideoCodec.H264, "setting-h264"), decision(VideoCodecSetting.H264, outputs, avc, failed))
                assertEquals(VideoCodecDecision(VideoCodec.H265, "setting-h265"), decision(VideoCodecSetting.H265, outputs, avc, failed))
            }
    }
    @Test fun automaticUsesTheUnchangedStreamPolicyWithAMainScreenViewer() {
        for (outputs in listOf(listOf(hevc), listOf(hevc.copy(prefersH264 = true)),
            listOf(hevc.copy(decoders = hevc.decoders.map { it.copy(hardware = false) })),
            listOf(hevc, capabilities("avc1.42E01F"))))
            for (avc in listOf(false, true)) for (remembered in listOf(null, VideoCodec.H264, VideoCodec.H265))
                assertEquals(VideoCodecPolicy.resolve(outputs, avc), decision(outputs = outputs, avc = avc, remembered = remembered))
        for (outputs in listOf(emptyList(), listOf(hevc.copy(displays = emptyMap()))))
            assertEquals(VideoCodecPolicy.resolve(outputs), decision(outputs = outputs))
    }
    @Test fun rememberedCodecIsOnlyAStartupChoiceAndNeverOverridesFailureOrCarlink() {
        for (codec in VideoCodec.entries) assertEquals(VideoCodecDecision(codec, "remembered-codec"), decision(outputs = emptyList(), remembered = codec))
        assertEquals(VideoCodecDecision(VideoCodec.H264, "hevc-failed"), decision(outputs = emptyList(), failed = true, remembered = VideoCodec.H265))
        assertEquals("output-requires-avc", decision(outputs = emptyList(), avc = true, remembered = VideoCodec.H265).reason)
        assertEquals(VideoCodecDecision(VideoCodec.H265, "remembered-codec"), decision(outputs = listOf(hevc.copy(displays = emptyMap())), remembered = VideoCodec.H265))
        assertEquals("hardware-hevc-unavailable", decision(outputs = listOf(capabilities("avc1.42E01F")), remembered = VideoCodec.H265).reason)
        assertEquals("no-main-screen-output", decision(outputs = emptyList()).reason)
    }
    @Test fun watchdogWaitsEightSecondsWithoutExtendingOnRepeatedRequestsAndLatches() {
        val requests = KeyframeRequests()
        val health = HevcFailureTracker()
        requests.request(0, 10)
        for (now in 10L..8_009L step 250) requests.request(0, now)
        health.watchdog(requests.waitMs(0, 8_009), true); assertNull(health.failure)
        health.watchdog(requests.waitMs(0, 8_010), false); assertNull(health.failure)
        health.watchdog(requests.waitMs(0, 8_010), true)
        assertEquals("keyframe-timeout waitMs=8000", health.failure)
        requests.published(0); health.watchdog(null, true)
        assertEquals("keyframe-timeout waitMs=8000", health.failure)
    }
    @Test fun aMainKeyframeCancelsTheWatchdogAndOtherScreensDoNot() {
        val requests = KeyframeRequests(); val health = HevcFailureTracker()
        requests.request(0, 0); requests.request(1, 0); requests.published(1)
        assertEquals(8_000L, requests.waitMs(0, 8_000))
        requests.published(0)
        health.watchdog(requests.waitMs(0, 9_000), true); assertNull(health.failure)
        requests.request(0, 10_000)
        assertEquals(0L, requests.waitMs(0, 10_000))
    }
    @Test fun onlyTheSecondUnrequestedEarlyAutomaticDropFailsHevc() {
        val health = HevcFailureTracker()
        health.ended(10, true, false); assertEquals(0, health.earlyDrops)
        health.format(100); health.ended(200, true, true)
        health.format(300); health.ended(400, false, false)
        health.format(500); health.ended(30_500, true, false)
        health.format(40_000); health.format(69_999); health.ended(70_000, true, false)
        assertEquals(0, health.earlyDrops)
        health.format(80_000); health.ended(80_001, true, false)
        assertEquals(1, health.earlyDrops); assertNull(health.failure)
        health.ended(80_002, true, false); assertEquals(1, health.earlyDrops)
        health.format(90_000); health.ended(119_999, true, false)
        assertEquals("early-drop waitMs=29999 count=2", health.failure)
        health.resetSession(); assertNotNull(health.failure)
    }
    @Test fun requestsAreCoalescedPerScreenAndCancelledByPublishedFramesForBothCodecs() {
        for (codec in VideoCodec.entries) {
            val requests = KeyframeRequests()
            requests.request(0, 0); assertTrue(requests.due(0, 0), codec.name)
            for (now in 250L..750L step 250) {
                requests.request(0, now); assertFalse(requests.due(0, now))
            }
            assertFalse(requests.due(0, 999)); assertTrue(requests.due(0, 1_000))
            assertFalse(requests.due(0, 1_100))
            requests.request(0, 1_250); requests.published(0)
            assertFalse(requests.due(0, 1_400)); assertNull(requests.waitMs(0, 1_400))
            requests.request(1, 1_500); assertTrue(requests.due(1, 1_500))
            requests.request(0, 1_500); assertFalse(requests.due(0, 1_999)); assertTrue(requests.due(0, 2_000))
        }
    }
}
