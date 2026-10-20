// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-16
package org.openxplay.direlay.video

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.openxplay.direlay.bridge.avcConfig
import org.openxplay.stream.core.*

class TranscodingOutputTest {
    private class Pipeline(val listener: VideoPipeline.Listener) : VideoPipeline {
        var started = false
        var closed = false
        var requests = 0
        val frames = mutableListOf<VideoFrame>()
        override fun start() { started = true }
        override fun offer(frame: VideoFrame) { frames.add(frame) }
        override fun requestKeyFrame() { requests++ }
        override fun close() { closed = true }
    }
    private class Host : OutputHost {
        val controls = mutableListOf<ControlEvent>()
        val caps = mutableListOf<OutputCapabilities>()
        override fun capabilities(capabilities: OutputCapabilities) { caps.add(capabilities) }
        override fun control(event: ControlEvent) { controls.add(event) }
        override fun stats(stats: OutputStats) {}
    }
    private fun frame() = VideoFrame(ScreenId.MAIN, SessionId(1), byteArrayOf(0, 0, 0, 2, 0x65, 0x01), 12345, true, 98765)
    private fun request() = ControlEvent.KeyFrameRequest(ScreenId.MAIN, Origin("recording", "receiver"))

    @Test fun switchesWhenCapabilitiesArriveAndRoutesRecoveryToEncoder() {
        val inner = RecordingOutput()
        val host = Host()
        val pipelines = mutableListOf<Pipeline>()
        val output = TranscodingOutput(inner, VideoPipeline.Factory { _, target, listener ->
            assertEquals(66, target.profile)
            Pipeline(listener).also { pipelines.add(it) }
        })
        output.open(host)
        output.onVideoFormat(ScreenId.MAIN, high())
        val frame = frame()
        output.onVideoFrame(frame)
        assertSame(frame, inner.frames.single())
        inner.host!!.control(request())
        assertEquals(1, host.controls.size)
        inner.host!!.capabilities(capabilities("avc1.42E01F"))
        val pipeline = pipelines.single()
        assertTrue(pipeline.started)
        assertNull(inner.videoFormats.last().second)
        val inputRequests = host.controls.size
        inner.host!!.control(request())
        assertEquals(1, pipeline.requests)
        assertEquals(inputRequests, host.controls.size)
        output.onVideoFrame(frame)
        assertSame(frame, pipeline.frames.single())
        pipeline.listener.format(avc())
        pipeline.listener.frame(frame)
        assertSame(frame, inner.frames.last())
        assertEquals(12345, inner.frames.last().ptsUs)
        inner.host!!.capabilities(capabilities("avc1.64001F"))
        assertTrue(pipeline.closed)
        val count = inner.frames.size
        pipeline.listener.frame(frame)
        assertEquals(count, inner.frames.size)
        output.onVideoFrame(frame)
        assertEquals(count + 1, inner.frames.size)
        inner.host!!.control(request())
        assertEquals(inputRequests + 1, host.controls.size)
        output.close()
    }

    @Test fun keyFrameRequestsReachTheHostWithoutHoldingTheTranscoderLock() {
        // The hub can call back into the input, whose thread may be delivering a frame to this output
        // while holding its own lock (the 2026-10-18 ANR). Each request must leave the output free.
        lateinit var output: TranscodingOutput
        val sent = mutableListOf<ControlEvent>()
        val blocked = mutableListOf<ControlEvent>()
        val host = object : OutputHost {
            override fun capabilities(capabilities: OutputCapabilities) {}
            override fun stats(stats: OutputStats) {}
            override fun control(event: ControlEvent) {
                sent += event
                val delivery = Thread { output.onVideoFrame(frame()) }.apply { isDaemon = true; start(); join(2_000) }
                if (delivery.isAlive) blocked += event
            }
        }
        val inner = RecordingOutput()
        val pipelines = mutableListOf<Pipeline>()
        output = TranscodingOutput(inner, VideoPipeline.Factory { _, _, listener -> Pipeline(listener).also { pipelines.add(it) } })
        output.open(host)
        output.onVideoFormat(ScreenId.MAIN, high())
        // Starting the transcoder asks the input for a key frame.
        inner.host!!.capabilities(capabilities("avc1.42E01F"))
        val pipeline = pipelines.single()
        for (callback in listOf({ pipeline.listener.inputKeyFrame() }, { pipeline.listener.failed(IllegalStateException("test")) })) {
            Thread { callback() }.apply { isDaemon = true; start(); join(5_000) }
        }
        assertEquals(3, sent.size)
        assertTrue(blocked.isEmpty(), "requests sent while the transcoder lock was held: $blocked")
        output.close()
    }

    @Test fun repeatedIdenticalFormatKeepsThePipelineAndRequestsNoKeyframe() {
        // The iPhone repeats its configuration with every keyframe; rebuilding looped on the real car.
        val inner = RecordingOutput(reportedCapabilities = capabilities("avc1.42E01F"))
        val pipelines = mutableListOf<Pipeline>()
        val output = TranscodingOutput(inner, VideoPipeline.Factory { _, _, listener -> Pipeline(listener).also { pipelines.add(it) } })
        val host = Host()
        output.open(host)
        output.onVideoFormat(ScreenId.MAIN, high())
        val requests = host.controls.size
        output.onVideoFormat(ScreenId.MAIN, high())
        output.onVideoFormat(ScreenId.MAIN, high())
        assertEquals(1, pipelines.size)
        assertFalse(pipelines.single().closed)
        assertEquals(requests, host.controls.size)
        output.close()
    }

    @Test fun formatReplacementAndEndReleasePipelinesAndFenceLateCallbacks() {
        val inner = RecordingOutput(reportedCapabilities = capabilities("avc1.42E01F"))
        val pipelines = mutableListOf<Pipeline>()
        val output = TranscodingOutput(inner, VideoPipeline.Factory { _, _, listener -> Pipeline(listener).also { pipelines.add(it) } })
        output.open(Host())
        output.onVideoFormat(ScreenId.MAIN, high())
        output.onVideoFormat(ScreenId.MAIN, highLevel32())
        assertEquals(2, pipelines.size)
        assertTrue(pipelines.first().closed)
        val count = inner.videoFormats.size
        pipelines.first().listener.format(avc())
        assertEquals(count, inner.videoFormats.size)
        output.onSession(SessionState(SessionId(1), SessionPhase.ENDED), SessionConfig(emptyMap()))
        assertTrue(pipelines.last().closed)
        pipelines.last().listener.frame(frame())
        assertTrue(inner.frames.isEmpty())
        output.close()
    }

    @Test fun setupAndRuntimeFailureFallBackToPassthroughAndReportFailure() {
        for (setupFailure in listOf(true, false)) {
            val inner = RecordingOutput(reportedCapabilities = capabilities("avc1.42E01F"))
            var pipeline: Pipeline? = null
            var failed = false
            val output = TranscodingOutput(inner, VideoPipeline.Factory { _, _, listener ->
                if (setupFailure) error("unavailable")
                Pipeline(listener).also { pipeline = it }
            }, report = { _, _, failure -> failed = failure })
            output.open(Host())
            val input = high()
            output.onVideoFormat(ScreenId.MAIN, input)
            if (!setupFailure) pipeline!!.listener.failed(IllegalStateException("codec_failed"))
            assertTrue(failed)
            assertSame(input, inner.videoFormats.last().second)
            val frame = frame()
            output.onVideoFrame(frame)
            assertSame(frame, inner.frames.single())
            output.close()
        }
    }

    @Test fun rejectsEncoderThatIgnoresRequestedProfileAndDoesNotForwardUnsupportedFrames() {
        val inner = RecordingOutput(reportedCapabilities = capabilities("avc1.42E01F"))
        lateinit var pipeline: Pipeline
        var failed = false
        val output = TranscodingOutput(inner, VideoPipeline.Factory { _, _, listener -> Pipeline(listener).also { pipeline = it } },
            report = { _, _, failure -> failed = failure })
        output.open(Host())
        output.onVideoFormat(ScreenId.MAIN, high())
        pipeline.listener.format(high())
        assertTrue(failed)
        assertTrue(pipeline.closed)
        inner.host!!.capabilities(capabilities())
        output.onVideoFrame(frame())
        assertTrue(inner.frames.isEmpty())
        output.close()
    }

    @Test fun hevcOnlySessionPassesHevcAndTranscodesAvcToHevc() {
        val inner = RecordingOutput(reportedCapabilities = capabilities("hvc1.1.6.L123.B0"))
        val decisions = mutableListOf<VideoCompatibility.Decision>()
        val pipelines = mutableListOf<Pipeline>()
        val output = TranscodingOutput(inner, VideoPipeline.Factory { _, target, listener ->
            assertEquals(VideoCodec.H265, target.codec)
            assertEquals("hvc1.1.6.L123.B0", target.codecString)
            Pipeline(listener).also { pipelines.add(it) }
        }, report = { _, decision, _ -> decisions.add(decision) })
        output.open(Host())
        val hevc = VideoFormat.create(ScreenId.MAIN, VideoCodec.H265, org.openxplay.direlay.bridge.hevcConfig)
        output.onVideoFormat(ScreenId.MAIN, hevc)
        assertEquals(VideoCompatibility.Mode.PASSTHROUGH, decisions.last().mode)
        assertTrue(pipelines.isEmpty())
        output.onVideoFrame(frame())
        assertEquals(1, inner.frames.size)
        output.onVideoFormat(ScreenId.MAIN, avc())
        assertEquals(VideoCompatibility.Mode.TRANSCODE, decisions.last().mode)
        val pipeline = pipelines.single()
        output.onVideoFrame(frame())
        assertEquals(1, pipeline.frames.size)
        pipeline.listener.format(hevc)
        assertSame(hevc, inner.videoFormats.last().second)
        pipeline.listener.frame(frame())
        assertEquals(2, inner.frames.size)
        output.close()
    }

    @Test fun transportCanRequireAvcEvenWhenReceiverAlsoListsHevc() {
        val inner = RecordingOutput(reportedCapabilities = capabilities("avc1.42E01F", "hvc1.1.6.L120.B0"))
        var codec: VideoCodec? = null
        val output = TranscodingOutput(inner, VideoPipeline.Factory { _, target, listener ->
            codec = target.codec; Pipeline(listener)
        }, deliveryCodecs = setOf(VideoCodec.H264))
        output.open(Host())
        output.onVideoFormat(ScreenId.MAIN, VideoFormat.create(ScreenId.MAIN, VideoCodec.H265, org.openxplay.direlay.bridge.hevcConfig))
        assertEquals(VideoCodec.H264, codec)
        output.close()
    }

    @Test fun reportsOncePerDecisionChangeDespiteFramesAndConfigurationChurn() {
        val inner = RecordingOutput(reportedCapabilities = capabilities("avc1.42E01F"))
        val reports = mutableListOf<VideoCompatibility.ReportKey>()
        val pipelines = mutableListOf<Pipeline>()
        val output = TranscodingOutput(inner, VideoPipeline.Factory { _, _, listener ->
            Pipeline(listener).also { pipelines.add(it) }
        }, report = { format, decision, failed ->
            reports.add(VideoCompatibility.reportKey(format, decision, failed))
        })
        output.open(Host())
        val input = high()
        repeat(20) {
            output.onVideoFormat(ScreenId.MAIN, input)
            output.onVideoFrame(frame())
            inner.host!!.capabilities(capabilities("avc1.42E01F"))
        }
        assertEquals(1, reports.size)
        // Change PPS bytes without changing the advertised codec or SPS dimensions.
        val changed = VideoFormat.create(ScreenId.MAIN, VideoCodec.H264,
            input.config.copyOf().also { it[it.lastIndex] = 0x81.toByte() })
        output.onVideoFormat(ScreenId.MAIN, changed)
        val afterConfig = reports.size
        assertEquals(1, afterConfig)
        assertEquals(2, pipelines.size)
        output.onVideoFormat(ScreenId.MAIN, changed)
        assertEquals(afterConfig, reports.size)
        pipelines.last().listener.failed(IllegalStateException("codec_failed"))
        pipelines.last().listener.failed(IllegalStateException("codec_failed"))
        assertEquals(afterConfig + 1, reports.size)
        inner.host!!.capabilities(capabilities())
        assertEquals(afterConfig + 2, reports.size)
        output.close()
    }

    @Test fun reportsChangesToReasonProfileSizeCodecAndTarget() {
        val inner = RecordingOutput()
        val reports = mutableListOf<VideoCompatibility.ReportKey>()
        val output = TranscodingOutput(inner, VideoPipeline.Factory { _, _, listener -> Pipeline(listener) },
            report = { format, decision, failed ->
                reports.add(VideoCompatibility.reportKey(format, decision, failed))
            })
        output.open(Host())
        output.onVideoFormat(ScreenId.MAIN, high())
        assertEquals(1, reports.size)
        inner.host!!.capabilities(capabilities("avc1.64001F"))
        assertEquals(1, reports.size) // Confirmed capabilities preserve the same decision.
        output.onVideoFormat(ScreenId.MAIN, highLevel32())
        assertEquals(2, reports.size)
        val resized = VideoFormat.create(ScreenId.MAIN, VideoCodec.H264,
            highLevel32().config.copyOf().also { it[14] = 0x21 })
        assertNotEquals(highLevel32().width, resized.width)
        output.onVideoFormat(ScreenId.MAIN, resized)
        assertEquals(3, reports.size)
        inner.host!!.capabilities(capabilities("avc1.42E01F"))
        assertEquals(4, reports.size)
        output.onVideoFormat(ScreenId.MAIN, VideoFormat.create(ScreenId.MAIN, VideoCodec.H265,
            org.openxplay.direlay.bridge.hevcConfig))
        assertEquals(5, reports.size)
        repeat(10) { inner.host!!.capabilities(capabilities("avc1.42E01F")) }
        assertEquals(5, reports.size)
        output.close()
    }

    @Test fun nonVideoCallbacksPassThrough() {
        val inner = RecordingOutput()
        val host = Host()
        val output = TranscodingOutput(inner, VideoPipeline.Factory { _, _, _ -> error("unexpected") })
        output.open(host)
        val format = AudioFormat(1, AudioRole.MEDIA, AudioCodec.AAC_LC, 48000, 2)
        val packet = AudioPacket(1, SessionId(1), byteArrayOf(1), 123)
        val request = MicRequest(1, AudioCodec.PCM_S16LE, 16000, 1, 20)
        output.onAudioFormat(1, format); output.onAudioPacket(packet); output.onMicrophone(1, request)
        assertSame(format, inner.audioFormats.single().second)
        assertSame(packet, inner.packets.single())
        assertSame(request, inner.micRequests.single().second)
        val key = ControlEvent.Key(KeyCode.HOME, true, Origin(inner.id, "receiver"))
        inner.host!!.control(key)
        assertSame(key, host.controls.single())
        output.close()
    }
}

private fun highLevel32() = VideoFormat.create(ScreenId.MAIN, VideoCodec.H264,
    avcConfig.copyOf().also { it[1] = 100; it[2] = 0; it[3] = 32 })
