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
