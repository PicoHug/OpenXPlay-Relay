// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.bridge

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.SessionControl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.openxplay.stream.core.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiPlayInputTest {
    @Test fun recoverySendsThePendingRequestWhenTheWindowOpensAndCancelsItOnAKeyframe() {
        for (codec in VideoCodec.entries) {
            val now = java.util.concurrent.atomic.AtomicLong(0)
            val sent = java.util.concurrent.LinkedBlockingQueue<Pair<Int, Long>>()
            DiPlayInput(nowMs = now::get).use { input ->
                input.start(configuration, CapturingInputSink())
                val bridge = StreamBridgeSink(input, object : MediaSink {}, SessionControl { false })
                bridge.setVideoRecoveryHandler(110) { sent.offer(0 to now.get()) }
                bridge.setVideoRecoveryHandler(111) { sent.offer(1 to now.get()) }
                bridge.onVideoCodec(110, if (codec == VideoCodec.H265) com.shilapi.xcertplay.airplay.VideoCodec.H265 else com.shilapi.xcertplay.airplay.VideoCodec.H264)
                bridge.onVideoConfig(110, if (codec == VideoCodec.H265) hevcConfig else avcConfig)
                input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin))
                assertEquals(0 to 0L, sent.poll(2, TimeUnit.SECONDS))
                now.set(250); input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin))
                now.set(1_000)
                assertEquals(0 to 1_000L, sent.poll(2, TimeUnit.SECONDS))
                now.set(1_250); input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin))
                bridge.onVideoFrame(110, if (codec == VideoCodec.H265) hex("00 00 00 01 26 01 80") else hex("00 00 00 01 65 80"))
                now.set(2_000); input.control(ControlEvent.KeyFrameRequest(ScreenId.CLUSTER, origin))
                assertEquals(1 to 2_000L, sent.poll(2, TimeUnit.SECONDS))
                now.set(3_000); input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin))
                assertEquals(0 to 3_000L, sent.poll(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun watchdogUsesPublishedMainKeyframesAndOnlyAutomaticHevc() {
        var now = 0L
        var automatic = true
        DiPlayInput(automaticHevc = { automatic }, nowMs = { now }).use { input ->
            input.start(configuration, CapturingInputSink())
            val bridge = StreamBridgeSink(input, object : MediaSink {}, SessionControl { false })
            bridge.setVideoRecoveryHandler(110) {}
            bridge.onVideoCodec(110, com.shilapi.xcertplay.airplay.VideoCodec.H265)
            bridge.onVideoConfig(110, hevcConfig)
            assertTrue(input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin)))
            now = 7_999; assertNull(input.hevcFailure())
            bridge.onVideoFrame(110, hex("00 00 00 01 26 01 80"))
            now = 16_000; assertNull(input.hevcFailure())
            assertTrue(input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin)))
            bridge.onVideoCodec(111, com.shilapi.xcertplay.airplay.VideoCodec.H265)
            bridge.onVideoConfig(111, hevcConfig)
            bridge.onVideoFrame(111, hex("00 00 00 01 26 01 80"))
            now = 24_000; automatic = false; assertNull(input.hevcFailure())
            automatic = true; assertEquals("keyframe-timeout waitMs=8000", input.hevcFailure())
            input.stop(); assertNotNull(input.hevcFailure())
        }
    }

    @Test fun relayRequestedStopDoesNotTripTheWatchdogWhileTheServiceStops() {
        var now = 0L
        DiPlayInput(automaticHevc = { true }, nowMs = { now }).use { input ->
            input.start(configuration, CapturingInputSink())
            val bridge = StreamBridgeSink(input, object : MediaSink {}, SessionControl { false })
            bridge.setVideoRecoveryHandler(110) {}
            input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin))
            input.expectStop(); now = 10_000
            assertNull(input.hevcFailure())
        }
    }

    @Test fun bridgeTracksFirstHevcFormatAndExcludesRelayRequestedStopsFromEarlyDrops() {
        var now = 0L
        DiPlayInput(automaticHevc = { true }, nowMs = { now }).use { input ->
            input.start(configuration, CapturingInputSink())
            fun session(): StreamBridgeSink {
                val bridge = StreamBridgeSink(input, object : MediaSink {}, SessionControl { false })
                bridge.onVideoCodec(110, com.shilapi.xcertplay.airplay.VideoCodec.H265)
                bridge.onVideoConfig(110, hevcConfig)
                return bridge
            }
            val requested = session(); now = 100
            input.expectStop(); requested.onScreenStreamActive(110, false)
            assertNull(input.hevcFailure())
            val first = session(); now = 200; first.onScreenStreamActive(110, false)
            assertNull(input.hevcFailure())
            val second = session(); now = 300; second.onScreenStreamActive(110, false)
            assertEquals("early-drop waitMs=100 count=2", input.hevcFailure())
        }
    }

    @Test fun viewAreaCommandsUseOnlyTheCurrentDecoratedSession() {
        DiPlayInput().use { input ->
            val calls = mutableListOf<Int>()
            fun control(offset: Int) = object : SessionControl {
                override fun sendTouch(contacts: List<AirPlayContact>) = false
                override fun showViewArea(index: Int): Boolean { calls.add(offset + index); return true }
            }
            assertFalse(input.showViewArea(0))
            input.begin(control(0))
            assertFalse(input.showViewArea(-1)); assertTrue(input.showViewArea(2))
            input.begin(control(10))
            assertTrue(input.showViewArea(1))
            input.stop(); assertFalse(input.showViewArea(0))
            assertEquals(listOf(2, 11), calls)
        }
    }

    @Test fun negotiatedFrameRatesApplyBeforeAuthorizedRestart() {
        val calls = mutableListOf<String>()
        DiPlayInput({ calls.add("fps:$it") }, { calls.add("restart") }).use { input ->
            val sink = CapturingInputSink()
            for (fps in 30..60 step 5) {
                val config = SessionConfig(mapOf(ScreenId.MAIN to ScreenConfig(VideoCodec.H264, 1280, 720, fps.toDouble())))
                input.start(configuration, sink)
                calls.clear()
                assertEquals(ReconfigureResult.RESTART_SESSION, input.reconfigure(config))
                assertTrue(calls.isEmpty())
                input.restart(config, sink, authorized = true)
                assertEquals(listOf("fps:$fps", "restart"), calls)
                calls.clear()
                input.restart(config, sink, authorized = false)
                assertEquals(listOf("fps:$fps"), calls)
            }
        }
    }


    @Test fun capabilitiesAndTouchUseTheCompleteContactSet() {
        DiPlayInput().use { input ->
            assertEquals(setOf(VideoCodec.H264, VideoCodec.H265), input.capabilities.codecs)
            assertEquals(setOf(ScreenId.MAIN), input.capabilities.screens)
            assertTrue(input.capabilities.touch)
            assertTrue(input.capabilities.keyFrameRequests)
            assertEquals(setOf(KeyCode.NEXT, KeyCode.PREVIOUS),
                input.capabilities.keys)
            assertFalse(input.capabilities.microphone)
            assertEquals(ReconfigureCost.RESTART_SESSION, input.capabilities.reconfigure)
            assertEquals(ReconfigureResult.RESTART_SESSION, input.reconfigure(configuration))
            val touches = mutableListOf<List<AirPlayContact>>()
            input.start(configuration, CapturingInputSink())
            StreamBridgeSink(input, object : MediaSink {}, SessionControl { touches.add(it); true })
            val contacts = listOf(TouchContact(2, .25, .75, true), TouchContact(3, .5, .125, false))
            assertTrue(input.control(ControlEvent.Touch(ScreenId.MAIN, contacts, origin)))
            assertTrue(input.control(ControlEvent.Touch(ScreenId.MAIN, emptyList(), origin)))
            assertEquals(listOf(listOf(AirPlayContact(2, .25, .75, true), AirPlayContact(3, .5, .125, false)), emptyList<AirPlayContact>()), touches)
            assertFalse(input.control(ControlEvent.Touch(ScreenId.CLUSTER, contacts, origin)))
            assertFalse(input.control(ControlEvent.Key(KeyCode.HOME, true, origin)))
            assertFalse(input.control(ControlEvent.MicPacket(0, byteArrayOf(), 0, origin)))
            input.stop()
            assertFalse(input.control(ControlEvent.Touch(ScreenId.MAIN, contacts, origin)))
        }
    }

    @Test fun mediaKeysReachTheCarPlayHidChannelOncePerPress() {
        val buttons = mutableListOf<Int>()
        DiPlayInput().use { input ->
            input.start(configuration, CapturingInputSink())
            StreamBridgeSink(input, object : MediaSink {}, object : SessionControl {
                override fun sendTouch(contacts: List<AirPlayContact>) = true
                override fun sendMediaButton(index: Int): Boolean { buttons.add(index); return true }
            })
            // The head unit sends a down/up pair per key; CarPlay needs one press per pair.
            for (code in listOf(KeyCode.NEXT, KeyCode.PREVIOUS)) {
                assertTrue(input.control(ControlEvent.Key(code, true, origin)))
                assertTrue(input.control(ControlEvent.Key(code, false, origin)))
            }
            assertEquals(listOf(CarPlayMediaButton.NEXT, CarPlayMediaButton.PREVIOUS), buttons)
            // The head unit handles play/pause itself and its voice key opens its own assistant,
            // so neither is forwarded to the phone.
            assertFalse(input.capabilities.keys.contains(KeyCode.PLAY_PAUSE))
            assertFalse(input.capabilities.keys.contains(KeyCode.VOICE))
            assertFalse(input.control(ControlEvent.Key(KeyCode.PLAY_PAUSE, true, origin)))
            assertFalse(input.control(ControlEvent.Key(KeyCode.VOICE, true, origin)))
            assertFalse(input.control(ControlEvent.Key(KeyCode.HOME, true, origin)))
        }
    }

    @Test fun recoveryIsAsynchronousSerializedAndFencedOnReplacement() {
        DiPlayInput().use { input ->
            input.start(configuration, CapturingInputSink())
            val bridge = StreamBridgeSink(input, object : MediaSink {}, SessionControl { false })
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val stale = CountDownLatch(1)
            val fresh = CountDownLatch(1)
            val caller = Thread.currentThread()
            var worker: Thread? = null
            bridge.setVideoRecoveryHandler(110) {
                worker = Thread.currentThread()
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                finished.countDown()
            }
            assertTrue(input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin)))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNotSame(caller, worker)
            bridge.setVideoRecoveryHandler(111) { stale.countDown() }
            assertTrue(input.control(ControlEvent.KeyFrameRequest(ScreenId.CLUSTER, origin)))
            val replacement = StreamBridgeSink(input, object : MediaSink {}, SessionControl { false })
            replacement.setVideoRecoveryHandler(110) { fresh.countDown() }
            assertTrue(input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin)))
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(fresh.await(2, TimeUnit.SECONDS))
            assertEquals(1L, stale.count)
            assertFalse(input.control(ControlEvent.KeyFrameRequest(ScreenId.CLUSTER, origin)))
        }
    }

    @Test fun recoveryRegisteredBeforeTheSessionIsCurrentStillServesKeyframes() {
        // Seen on a real iPhone: DiPlay registered the handler before the relay input could deliver,
        // and late-joining browsers then waited forever for a keyframe.
        DiPlayInput().use { input ->
            val bridge = StreamBridgeSink(input, object : MediaSink {}, SessionControl { false })
            val requested = CountDownLatch(1)
            bridge.setVideoRecoveryHandler(110) { requested.countDown() }
            input.start(configuration, CapturingInputSink())
            bridge.onScreenStreamActive(110, true)
            assertTrue(input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin)))
            assertTrue(requested.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun recoverySurvivesAMainStreamPauseAndResume() {
        // DiPlay registers the handler once per stream; a pause/resume of the main screen must keep it.
        DiPlayInput().use { input ->
            input.start(configuration, CapturingInputSink())
            val bridge = StreamBridgeSink(input, object : MediaSink {}, SessionControl { false })
            val requested = CountDownLatch(1)
            bridge.setVideoRecoveryHandler(110) { requested.countDown() }
            bridge.onScreenStreamActive(110, false)
            assertFalse(input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin)))
            bridge.onScreenStreamActive(110, true)
            assertTrue(input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin)))
            assertTrue(requested.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun touchReportsDiPlayRefusalAndEndedSessionHasNoControl() {
        DiPlayInput().use { input ->
            input.start(configuration, CapturingInputSink())
            val bridge = StreamBridgeSink(input, object : MediaSink {}, SessionControl { false })
            assertFalse(input.control(ControlEvent.Touch(ScreenId.MAIN, emptyList(), origin)))
            bridge.onScreenStreamActive(110, false)
            assertFalse(input.control(ControlEvent.KeyFrameRequest(ScreenId.MAIN, origin)))
        }
    }
}
