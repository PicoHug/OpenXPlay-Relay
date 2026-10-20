// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-16
package org.openxplay.direlay.video

import org.openxplay.stream.core.*

/** Implementations enqueue work; close cancels promptly and releases resources on their worker. */
interface VideoPipeline {
    fun start()
    fun offer(frame: VideoFrame)
    fun requestKeyFrame()
    fun close()
    interface Listener {
        fun format(format: VideoFormat)
        fun frame(frame: VideoFrame)
        fun failed(error: Exception)
        fun inputKeyFrame() {}
    }
    fun interface Factory {
        fun create(input: VideoFormat, target: VideoCompatibility.Target, listener: Listener): VideoPipeline
    }
}

/** Adapts any Stream output without exposing platform codecs to Stream or to the input. */
class TranscodingOutput(
    private val inner: ProjectionOutput,
    private val factory: VideoPipeline.Factory,
    private val report: (VideoFormat, VideoCompatibility.Decision, Boolean) -> Unit = { _, _, _ -> },
    private val observeCapabilities: (OutputCapabilities?) -> Unit = {},
    private val deliveryCodecs: Set<VideoCodec> = VideoCodec.entries.toSet(),
) : ProjectionOutput by inner {
    private class Stream(val format: VideoFormat) {
        var decision = VideoCompatibility.Decision(VideoCompatibility.Mode.PASSTHROUGH)
        var pipeline: VideoPipeline? = null
        var revision = 0L
        var failed = false
        var attempts = 0
        var startedAt = 0L
    }
    private val gate = Any()
    private val reported = mutableMapOf<ScreenId, VideoCompatibility.ReportKey>()
    private fun reportChange(format: VideoFormat, decision: VideoCompatibility.Decision, failed: Boolean) {
        val key = VideoCompatibility.reportKey(format, decision, failed)
        if (reported.put(format.screen, key) != key) report(format, decision, failed)
    }
    private val streams = mutableMapOf<ScreenId, Stream>()
    private companion object {
        /** Transcode restarts before a failed stream is allowed to fall back to passthrough. */
        const val MAX_TRANSCODE_ATTEMPTS = 3
    }
    private var capabilities: OutputCapabilities? = null
    private var host: OutputHost? = null
    private var closed = false
    // Requests to the host are queued under the gate and sent after it is released: the hub can call
    // back into the input while the input thread holds its own lock and waits for this gate.
    private val outbox = ArrayList<ControlEvent>()
    private fun requestKeyFrame(screen: ScreenId) { outbox += ControlEvent.KeyFrameRequest(screen, Origin(id, "transcoder")) }
    private var depth = 0
    /** Runs [block] under the gate; the outermost call sends the queued requests once the gate is free. */
    private fun <T> locked(block: () -> T): T {
        val (result, target, events) = synchronized(gate) {
            depth++
            val result = try { block() } finally { depth-- }
            val events = if (depth == 0) outbox.toList().also { outbox.clear() } else emptyList()
            Triple(result, host, events)
        }
        events.forEach { target?.control(it) }
        return result
    }

    override fun open(host: OutputHost) {
        synchronized(gate) { this.host = host; closed = false }
        inner.open(object : OutputHost by host {
            override fun capabilities(capabilities: OutputCapabilities) {
                val open = locked {
                    if (!closed) {
                        this@TranscodingOutput.capabilities = capabilities
                        streams.values.forEach { configure(it) }
                    }
                    !closed
                }
                if (!open) return
                observeCapabilities(capabilities)
                host.capabilities(capabilities)
            }
            override fun control(event: ControlEvent) {
                synchronized(gate) {
                    if (closed) return
                    if (event is ControlEvent.KeyFrameRequest) {
                        val stream = streams[event.screen]
                        stream?.pipeline?.let { it.requestKeyFrame(); return }
                        if (stream?.decision?.mode == VideoCompatibility.Mode.UNSUPPORTED) return
                    }
                }
                host.control(event)
            }
        })
    }

    override fun onVideoFormat(screen: ScreenId, format: VideoFormat?) = locked {
        if (closed) return@locked
        val existing = streams[screen]
        if (format != null && existing != null && sameFormat(existing.format, format)) {
            // The iPhone repeats an identical configuration with every keyframe. Rebuilding the
            // transcoder for it requests another keyframe and loops; keep the running pipeline.
            if (existing.pipeline != null && !existing.failed) return@locked
            if (existing.decision.mode != VideoCompatibility.Mode.TRANSCODE || existing.failed) {
                inner.onVideoFormat(screen, format)
                return@locked
            }
        }
        streams.remove(screen)?.let { it.revision++; it.pipeline?.close() }
        if (format == null) {
            reported.remove(screen)
            inner.onVideoFormat(screen, null)
        } else Stream(format).also { streams[screen] = it; configure(it, force = true) }
    }

    private fun configure(stream: Stream, force: Boolean = false, retry: Boolean = false) {
        // No capability report yet: preserve the existing output's negotiation behavior.
        val decision = capabilities?.let { caps -> VideoCompatibility.decide(stream.format, caps.decoders.filter {
            (if (it.codecString.startsWith("avc1.")) VideoCodec.H264 else VideoCodec.H265) in deliveryCodecs
        }) }
            ?: VideoCompatibility.Decision(VideoCompatibility.Mode.PASSTHROUGH)
        if (!force && decision == stream.decision) {
            reportChange(stream.format, decision, stream.failed)
            return
        }
        stream.revision++
        stream.pipeline?.close(); stream.pipeline = null
        stream.failed = false
        if (!retry) stream.attempts = 0
        stream.decision = decision
        reportChange(stream.format, decision, false)
        if (decision.mode != VideoCompatibility.Mode.TRANSCODE) {
            inner.onVideoFormat(stream.format.screen, stream.format)
            return
        }
        inner.onVideoFormat(stream.format.screen, null)
        val revision = stream.revision
        val listener = object : VideoPipeline.Listener {
            private fun current() = !closed && streams[stream.format.screen] === stream && stream.revision == revision
            override fun format(format: VideoFormat) = locked {
                if (current()) {
                    if (capabilities?.decoders?.any { VideoCompatibility.accepts(format, it) } != true) {
                        failed(IllegalStateException("encoder_profile_mismatch"))
                    } else inner.onVideoFormat(format.screen, format)
                }
            }
            override fun frame(frame: VideoFrame) = synchronized(gate) {
                if (current()) inner.onVideoFrame(frame)
            }
            override fun inputKeyFrame() = locked {
                if (current()) requestKeyFrame(stream.format.screen)
            }
            override fun failed(error: Exception) = locked {
                if (!current()) return@locked
                android.util.Log.w("DiRelayCarlink", "video pipeline failed attempt=${stream.attempts} " +
                    "after=${(System.nanoTime() - stream.startedAt) / 1_000_000}ms ${error.javaClass.simpleName}: ${error.message}")
                stream.revision++
                stream.pipeline?.close(); stream.pipeline = null
                stream.failed = true
                reportChange(stream.format, decision, true)
                // A pipeline that played for a while is not a broken configuration: give it a fresh set
                // of attempts, otherwise two hiccups in a session end in the passthrough black screen.
                if (stream.startedAt != 0L && System.nanoTime() - stream.startedAt > 15_000_000_000L) stream.attempts = 0
                // The receiver rejected the input profile, so forwarding the input format here is a
                // guaranteed black screen. Restart the transcoder a few times before giving up.
                if (decision.mode == VideoCompatibility.Mode.TRANSCODE && stream.attempts < MAX_TRANSCODE_ATTEMPTS) {
                    stream.attempts++
                    configure(stream, force = true, retry = true)
                } else {
                    inner.onVideoFormat(stream.format.screen, stream.format)
                }
                requestKeyFrame(stream.format.screen)
            }
        }
        try {
            stream.startedAt = System.nanoTime()
            stream.pipeline = factory.create(stream.format, checkNotNull(decision.target), listener)
            stream.pipeline?.start()
            // A new decoder needs an input IDR once; receiver recovery thereafter targets the encoder.
            if (!stream.failed) requestKeyFrame(stream.format.screen)
        } catch (failure: Exception) { listener.failed(failure) }
    }

    override fun onVideoFrame(frame: VideoFrame) = synchronized(gate) {
        if (closed) return@synchronized
        val stream = streams[frame.screen] ?: return@synchronized
        val pipeline = stream.pipeline
        if (pipeline != null) pipeline.offer(frame)
        else if (stream.decision.mode == VideoCompatibility.Mode.PASSTHROUGH || stream.failed) inner.onVideoFrame(frame)
    }

    override fun onSession(state: SessionState, config: SessionConfig) = synchronized(gate) {
        if (state.phase == SessionPhase.ENDED) {
            streams.values.forEach { it.revision++; it.pipeline?.close() }; streams.clear(); reported.clear()
        }
        inner.onSession(state, config)
    }

    override fun close() {
        synchronized(gate) {
            closed = true
            streams.values.forEach { it.revision++; it.pipeline?.close() }; streams.clear(); reported.clear()
            host = null; capabilities = null
        }
        observeCapabilities(null)
        inner.close()
    }

    private fun sameFormat(a: VideoFormat, b: VideoFormat): Boolean =
        a.codec == b.codec && a.codecString == b.codecString && a.width == b.width && a.height == b.height && a.config.contentEquals(b.config)
}
