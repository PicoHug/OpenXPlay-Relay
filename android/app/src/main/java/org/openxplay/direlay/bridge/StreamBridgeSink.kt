// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.bridge

import android.util.Log
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.AudioFormat as CarPlayAudioFormat
import org.openxplay.direlay.settings.MicrophoneSource
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.SessionControl
import com.shilapi.xcertplay.airplay.VideoCodec as DiPlayCodec
import org.openxplay.stream.core.*
import org.openxplay.stream.core.codec.Nal

/** Translates DiPlay media and keeps codec, playback and microphone work off receive threads. */
class StreamBridgeSink(
    private val input: DiPlayInput,
    private val delegate: MediaSink,
    control: SessionControl,
    private val localPreview: () -> Boolean = { false },
    private val fpsHint: Double? = null,
    private val playAudioLocally: () -> Boolean = { false },
    private val microphoneSource: () -> MicrophoneSource = { MicrophoneSource.HEAD_UNIT },
    private val reportInvalid: (Int, Exception) -> Unit = { _, _ -> },
) : MediaSink by delegate {
    internal var microphoneUplinkFactory: (MicrophoneConfig, () -> Unit) -> MicrophoneUplink =
        { config, failed -> RelayMicrophoneUplink(config, failed) }
    private val session = input.begin(control)
    private val audio = mutableMapOf<Int, Pair<AudioStreamId, RelayAudioStream>>()
    private val audioTails = mutableMapOf<Int, RelayAudioStream>()
    private val microphoneIds = mutableMapOf<Int, AudioStreamId>()
    private val microphoneTails = mutableMapOf<Int, DelegatedMicrophone>()
    private val localMicrophones = mutableMapOf<Int, Pair<AudioStreamId, DelegatedMicrophone>>()
    init {
        session.cleanup = {
            audio.values.forEach { it.second.close() }; audio.clear()
            localMicrophones.values.forEach { it.second.close() }; localMicrophones.clear()
            microphoneIds.clear()
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: CarPlayAudioFormat, firstSample: Int) {
        // Every role is logged: a phone call that the head unit plays through the media channel
        // arrives with an audioType that is not "telephony", and the role alone does not tell
        // which streams the user is meant to hear (2026-10-20).
        Log.i("DiRelayAudio", "Audio stream type=${id.type} audioType=${id.audioType} " +
            "role=${AudioPolicy.role(id.audioType)} voice=${AudioPolicy.voiceChannel(id)} " +
            "codec=${format.codec} rate=${format.sampleRate} " +
            "channels=${format.channels} forwarded=${AudioPolicy.forwarded(id)}")
        if (!AudioPolicy.forwarded(id)) {
            Log.i("DiRelayAudio", "Ignoring system sound stream type=${id.type} audioType=${id.audioType}")
            return
        }
        val stream = AudioPolicy.stream(id) ?: return
        input.deliver(session) { sink ->
            audio.remove(stream)?.second?.close()
            if (session.audio.remove(stream)) sink.audioFormat(stream, null)
            lateinit var run: RelayAudioStream
            run = RelayAudioStream(id, format, { bytes, pts ->
                input.deliver(session) { target ->
                    if (audio[stream]?.second === run) target.audioPacket(AudioPacket(stream, session.id, bytes, pts))
                }
            }, {
                input.deliver(session) { target ->
                    if (audio[stream]?.second === run) {
                        audio.remove(stream)
                        if (session.audio.remove(stream)) target.audioFormat(stream, null)
                    }
                }
            }, delegate, playAudioLocally, firstSample, audioTails[stream])
            audioTails[stream] = run
            audio[stream] = id to run
            session.audio.add(stream)
            sink.audioFormat(stream, AudioPolicy.format(id, format))
            run.start()
        }
    }

    override fun onAudioRtp(id: AudioStreamId, format: CarPlayAudioFormat, rtp: ByteArray, sample: Int) {
        if (!AudioPolicy.forwarded(id)) return
        val arrival = System.nanoTime() / 1000
        val stream = AudioPolicy.stream(id) ?: return
        input.deliver(session) { audio[stream]?.takeIf { it.first == id }?.second?.offer(rtp, sample, arrival) }
    }

    override fun onAudioStopped(id: AudioStreamId) {
        if (!AudioPolicy.forwarded(id)) return
        val stream = AudioPolicy.stream(id) ?: return
        input.deliver(session) { sink ->
            if (audio[stream]?.first == id) {
                audio.remove(stream)?.second?.close()
                if (session.audio.remove(stream)) sink.audioFormat(stream, null)
            }
        }
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        val stream = AudioPolicy.stream(id) ?: return
        input.deliver(session) { sink ->
            stopMicrophone(stream, sink)
            microphoneIds[stream] = id
            if (microphoneSource() == MicrophoneSource.THIS_DEVICE) {
                localMicrophones[stream] = id to DelegatedMicrophone(delegate, id, config, microphoneTails[stream]).also {
                    microphoneTails[stream] = it; it.start()
                }
            } else {
                lateinit var uplink: MicrophoneUplink
                uplink = microphoneUplinkFactory(config.copy(key = config.key.copyOf()), {
                    input.deliver(session) { target ->
                        if (session.microphones[stream] === uplink) stopMicrophone(stream, target)
                    }
                })
                session.microphones[stream] = uplink
                session.microphoneStreams.add(stream)
                input.refreshMicrophoneCapability()
                sink.microphone(stream, MicRequest(stream, AudioCodec.PCM_S16LE, config.sampleRate, 1, 20))
                uplink.start()
            }
        }
    }

    private fun stopMicrophone(stream: Int, sink: InputSink) {
        microphoneIds.remove(stream)
        localMicrophones.remove(stream)?.second?.close()
        session.microphones.remove(stream)?.close()
        if (session.microphoneStreams.remove(stream)) sink.microphone(stream, null)
        input.refreshMicrophoneCapability()
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        val stream = AudioPolicy.stream(id) ?: return
        input.deliver(session) { sink -> if (microphoneIds[stream] == id) stopMicrophone(stream, sink) }
    }

    private val codecs = mutableMapOf<ScreenId, VideoCodec>()
    private val configs = mutableMapOf<ScreenId, ByteArray>()
    private val formats = mutableMapOf<ScreenId, VideoFormat>()
    private val previewCodecs = mutableSetOf<ScreenId>()
    private val previewConfigured = mutableSetOf<ScreenId>()

    private fun screen(type: Int): ScreenId? = when (type) {
        DIPLAY_MAIN_SCREEN -> ScreenId.MAIN
        DIPLAY_ALT_SCREEN -> ScreenId.CLUSTER
        else -> null
    }

    private fun invalid(type: Int, failure: Exception) {
        try { reportInvalid(type, failure) } catch (_: Exception) { /* Diagnostics cannot break DiPlay. */ }
    }

    override fun onVideoCodec(type: Int, codec: DiPlayCodec) {
        val screen = screen(type) ?: return
        input.deliver(session) { sink ->
            val mapped = when (codec) {
                DiPlayCodec.H264 -> VideoCodec.H264
                DiPlayCodec.H265 -> VideoCodec.H265
            }
            if (codecs.put(screen, mapped) != mapped) {
                configs.remove(screen)
                formats.remove(screen)
                previewConfigured.remove(screen)
                sink.videoFormat(screen, null)
            }
            if (localPreview()) {
                delegate.onVideoCodec(type, codec)
                previewCodecs.add(screen)
            } else previewCodecs.remove(screen)
        }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val screen = screen(type) ?: return
        if (codecData.isEmpty()) return
        input.deliver(session) { sink ->
            formats.remove(screen)
            previewConfigured.remove(screen)
            val format = try {
                val codec = codecs[screen] ?: VideoCodec.H264
                fun create(candidate: VideoCodec) = VideoFormat.create(screen, candidate,
                    if (candidate == VideoCodec.H265) hevcRecord(codecData) else codecData.copyOf(), fpsHint)
                val parsed = try {
                    create(codec)
                } catch (announced: IllegalArgumentException) {
                    val other = if (codec == VideoCodec.H264) VideoCodec.H265 else VideoCodec.H264
                    try {
                        create(other)
                    } catch (fallback: IllegalArgumentException) {
                        // Keep both reasons and the record (codec parameters only) so a rejected configuration can be reproduced.
                        val record = codecData.take(256).joinToString("") { "%02x".format(it) }
                        throw IllegalArgumentException("$codec: ${announced.message}; $other: ${fallback.message}; " +
                            "config(${codecData.size}B)=$record", fallback)
                    }
                }
                if (codecs[screen] != parsed.codec) {
                    Log.i("OpenXPlay Relay", "Video configuration identifies ${parsed.codec}; correcting announced codec")
                    codecs[screen] = parsed.codec
                    previewCodecs.remove(screen)
                }
                parsed
            } catch (failure: Exception) {
                configs.remove(screen)
                sink.videoFormat(screen, null)
                invalid(type, failure)
                return@deliver
            }
            configs[screen] = format.config
            formats[screen] = format
            sink.videoFormat(screen, format)
            input.videoFormat(session, screen, format)
            if (!session.active) {
                session.active = true
                sink.session(SessionState(session.id, SessionPhase.ACTIVE))
            }
            if (localPreview()) configurePreview(type, screen)
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        val now = System.nanoTime()
        frame(type, naluBytes, now, now, legacy = true)
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        frame(type, naluBytes, senderNanos, arrivalNanos, legacy = false)
    }

    private val videoClockOffsets = mutableMapOf<ScreenId, Long>()

    /**
     * Maps the iPhone's frame clock (an NTP time in the phone's own clock) into this relay's monotonic
     * clock, which audio timestamps already use, keeping the phone's frame spacing. Outputs that mux
     * audio and video together (Carlink TS) need one clock; mixed clocks produced jumps of hours and an
     * OutOfMemoryError in the TS muxer (2026-10-15). Re-anchors when the mapping drifts by over 1 s.
     */
    private fun videoPtsUs(screen: ScreenId, sender: Long, arrival: Long): Long {
        val offset = videoClockOffsets[screen]?.takeIf { kotlin.math.abs(sender + it - arrival) <= 1_000_000_000L }
            ?: (arrival - sender).also { videoClockOffsets[screen] = it }
        return (sender + offset) / 1000
    }

    private fun frame(type: Int, bytes: ByteArray, sender: Long, arrival: Long, legacy: Boolean) {
        val screen = screen(type) ?: return
        input.deliver(session) { sink ->
            val format = formats[screen] ?: run {
                if (missingFormatLogged.add(screen)) Log.w("OpenXPlay Relay", "Video for screen ${screen.value} dropped: no format yet")
                return@deliver
            }
            missingFormatLogged.remove(screen)
            try {
                val avcc = Nal.annexBToAvcc(bytes)
                val keyframe = Nal.isKeyFrame(avcc, format.codec)
                sink.videoFrame(VideoFrame(screen, session.id, avcc, videoPtsUs(screen, sender, arrival), keyframe, arrival))
                if (keyframe) input.keyframePublished(session, screen)
            } catch (failure: IllegalArgumentException) {
                invalid(type, failure)
                return@deliver
            }
            if (localPreview()) {
                configurePreview(type, screen)
                if (legacy) delegate.onVideoFrame(type, bytes)
                else delegate.onVideoFrame(type, bytes, sender, arrival)
            } else {
                previewConfigured.remove(screen)
                previewCodecs.remove(screen)
            }
        }
    }

    private fun configurePreview(type: Int, screen: ScreenId) {
        if (screen in previewConfigured) return
        val config = configs[screen] ?: return
        if (screen !in previewCodecs) {
            delegate.onVideoCodec(type, when (codecs[screen]) {
                VideoCodec.H265 -> DiPlayCodec.H265
                else -> DiPlayCodec.H264
            })
            previewCodecs.add(screen)
        }
        // Keep the model's borrowed configuration immutable even if the renderer modifies its copy.
        delegate.onVideoConfig(type, config.copyOf())
        previewConfigured.add(screen)
    }

    private val missingFormatLogged = java.util.Collections.synchronizedSet(mutableSetOf<ScreenId>())

    private fun resumeMain() {
        input.resume(session) {
            Log.i("OpenXPlay Relay", "Main screen resumed; waiting for codec configuration")
            codecs.clear()
            configs.clear()
            formats.clear()
            previewConfigured.clear()
            previewCodecs.clear()
        }
        // The iPhone is setting up its main screen: the session is now really connecting.
        input.announce(session)
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        if (screen(type) == ScreenId.MAIN) resumeMain()
        screen(type)?.let { screen -> input.registerRecovery(session, screen, handler) }
        delegate.setVideoRecoveryHandler(type, handler)
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        val screen = screen(type)
        Log.i("OpenXPlay Relay", "Screen stream $type active=$active")
        if (active && screen == ScreenId.MAIN) resumeMain()
        if (!active && screen != null) {
            input.deliver(session) { sink ->
                formats.remove(screen)
                configs.remove(screen)
                codecs.remove(screen)
                previewConfigured.remove(screen)
                previewCodecs.remove(screen)
                sink.videoFormat(screen, null)
            }
            if (screen == ScreenId.MAIN) input.end(session)
        }
        delegate.onScreenStreamActive(type, active)
    }
}

// DiPlay's screen stream types (AirPlaySession keeps them private).
private const val DIPLAY_MAIN_SCREEN = 110
private const val DIPLAY_ALT_SCREEN = 111
