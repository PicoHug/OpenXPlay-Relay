// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.bridge

import android.util.Log
import kotlinx.coroutines.flow.asStateFlow
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.SessionControl
import org.openxplay.stream.core.*
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import org.openxplay.direlay.video.HevcFailureTracker
import org.openxplay.direlay.video.KeyframeRequests
import java.util.concurrent.TimeUnit

/** Owns the session fence and serializes all sink callbacks, including replacement and stop. */
class DiPlayInput(private val applyFrameRate: (Int) -> Unit = {}, private val restartCarPlay: () -> Unit = {},
    private val automaticHevc: () -> Boolean = { false }, private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) : ProjectionInput, AutoCloseable {
    @Volatile override var capabilities = InputCapabilities(
        setOf(VideoCodec.H264, VideoCodec.H265), setOf(ScreenId.MAIN),
        touch = true, keys = setOf(KeyCode.NEXT, KeyCode.PREVIOUS), microphone = false,
        keyFrameRequests = true, reconfigure = ReconfigureCost.RESTART_SESSION,
    )
        private set
    private val requestedScreen = kotlinx.coroutines.flow.MutableStateFlow<ScreenConfig?>(null)
    val mainScreen: kotlinx.coroutines.flow.StateFlow<ScreenConfig?> = requestedScreen.asStateFlow()
    private val keyframes = java.util.ArrayDeque<String>()
    val keyframeLog: List<String> get() = synchronized(keyframes) { keyframes.toList() }
    private fun recordKeyframe(message: String) = synchronized(keyframes) {
        if (keyframes.size == 32) keyframes.removeFirst()
        keyframes.addLast("${System.currentTimeMillis()}: $message")
    }
    private var requestedMicrophone = false
    fun microphoneAvailable(enabled: Boolean) = synchronized(gate) {
        requestedMicrophone = enabled
        refreshMicrophoneCapability()
    }
    internal fun refreshMicrophoneCapability() = synchronized(gate) {
        val enabled = requestedMicrophone || current?.microphoneStreams?.isNotEmpty() == true
        if (capabilities.microphone != enabled) {
            capabilities = capabilities.copy(microphone = enabled)
            sink?.inputCapabilities(capabilities)
        }
    }
    private val gate = Any()
    private var sink: InputSink? = null
    private val latestContext = linkedMapOf<ContextTopic, ContextUpdate>()
    private var current: Session? = null
    private var latest: Session? = null
    private var nextId = 0L
    private val health = HevcFailureTracker()
    fun hevcFailure(): String? = synchronized(gate) {
        health.watchdog(current?.requests?.waitMs(ScreenId.MAIN.value, nowMs()), automaticHevc() && current?.requestedEnd != true)
        health.failure
    }
    fun expectStop() = synchronized(gate) { current?.requestedEnd = true }
    internal fun videoFormat(session: Session, screen: ScreenId, format: VideoFormat) = synchronized(gate) {
        if (current === session && screen == ScreenId.MAIN && format.codec == VideoCodec.H265) health.format(nowMs())
    }
    internal fun keyframePublished(session: Session, screen: ScreenId) = synchronized(gate) {
        if (current === session) session.requests.published(screen.value)
    }
    private val recoveryQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val recovery = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "direlay-recovery").apply { isDaemon = true }
    }.apply { scheduleWithFixedDelay({ flushKeyframes() }, 50, 50, TimeUnit.MILLISECONDS) }
    private fun flushKeyframes() {
        val requests = synchronized(gate) {
            val session = current ?: return
            if (sink == null) return
            session.handlers.map { (screen, handler) -> Triple(session, screen, handler) }
        }
        for ((session, screen, handler) in requests) {
            val valid = synchronized(gate) {
                if (sink == null || current !== session || session.handlers[screen] !== handler) {
                    recordKeyframe("Screen ${screen.value}: stale request")
                    false
                } else session.requests.due(screen.value, nowMs())
            }
            if (valid) try {
                handler()
                recordKeyframe("Screen ${screen.value}: requested")
                Log.i(TAG, "Keyframe requested for screen ${screen.value}")
            } catch (_: Exception) { recordKeyframe("Screen ${screen.value}: request failed") }
        }
    }

    internal class Session(var id: SessionId, val control: SessionControl) {
        val handlers = mutableMapOf<ScreenId, () -> Unit>()
        var requests = KeyframeRequests()
        var requestedEnd = false
        var active = false
        var announced = false
        val audio = mutableSetOf<Int>()
        val microphones = mutableMapOf<Int, MicrophoneUplink>()
        val microphoneStreams = mutableSetOf<Int>()
        var cleanup: () -> Unit = {}
    }

    override fun start(config: SessionConfig, sink: InputSink) {
        synchronized(gate) {
            requestedScreen.value = config.screens[ScreenId.MAIN]
            this.sink = sink
            config.screens[ScreenId.MAIN]?.let { applyFrameRate(it.fps.toInt()) }
        }
    }

    /** Called only from the isolated plugin context worker. */
    fun context(update: ContextUpdate) = synchronized(gate) {
        latestContext[update.topic] = update
        sink?.context(update)
        Unit
    }

    override fun reconfigure(config: SessionConfig): ReconfigureResult = synchronized(gate) {
        requestedScreen.value = config.screens[ScreenId.MAIN]
        ReconfigureResult.RESTART_SESSION
    }

    override fun restart(config: SessionConfig, sink: InputSink, authorized: Boolean) {
        if (authorized) stop()
        start(config, sink)
        if (authorized) restartCarPlay()
    }

    override fun stop() = synchronized(gate) {
        current?.let { it.requestedEnd = true; endLocked(it) }
        sink = null
        current = null
        latest = null
        latestContext.clear()
    }

    fun showViewArea(index: Int): Boolean = synchronized(gate) {
        index >= 0 && current?.control?.showViewArea(index) == true
    }

    /**
     * DiPlay builds the media engine when CarPlay starts, before any iPhone connects, and drops an
     * engine that never saw a stream without further callbacks. The new session fences the previous
     * one at once but is announced (CONNECTING) only when the iPhone's first stream arrives, so a
     * start and stop without an iPhone leaves no session behind.
     */
    internal fun begin(control: SessionControl): Session = synchronized(gate) {
        current?.let { it.requestedEnd = true; endLocked(it) }
        Session(SessionId(++nextId), control).also {
            current = it
            latest = it
        }
    }

    internal fun announce(session: Session) = synchronized(gate) {
        if (current === session) announceLocked(session)
    }

    private fun announceLocked(session: Session) {
        if (session.announced) return
        session.announced = true
        sink?.session(SessionState(session.id, SessionPhase.CONNECTING))
        // RECORD and iAP2 observations can precede the first media stream. The hub resets its
        // topics for a new session, so replay the observations after announcing that session.
        latestContext.values.forEach { sink?.context(it) }
    }

    /** A new main stream can reconnect within the same decorated media engine. */
    internal fun resume(session: Session, reset: () -> Unit) = synchronized(gate) {
        if (sink != null && current == null && latest === session) {
            reset()
            session.id = SessionId(++nextId)
            session.active = false
            session.requests = KeyframeRequests()
            session.requestedEnd = false
            health.resetSession()
            session.announced = true
            current = session
            sink?.session(SessionState(session.id, SessionPhase.CONNECTING))
            latestContext.values.forEach { sink?.context(it) }
        }
    }

    /**
     * Recovery handlers belong to the session even before it becomes current: DiPlay registers each
     * screen's handler once, and dropping it would leave late-joining receivers without keyframes.
     */
    internal fun registerRecovery(session: Session, screen: ScreenId, handler: () -> Unit) = synchronized(gate) {
        session.handlers[screen] = handler
    }

    internal fun deliver(session: Session, action: (InputSink) -> Unit) = synchronized(gate) {
        if (current === session) sink?.let { announceLocked(session); action(it) }
    }

    internal fun end(session: Session) = synchronized(gate) {
        if (current === session) endLocked(session)
    }

    private fun endLocked(session: Session) {
        health.ended(nowMs(), automaticHevc(), session.requestedEnd)
        session.cleanup()
        session.audio.forEach { sink?.audioFormat(it, null) }
        session.microphoneStreams.forEach { sink?.microphone(it, null) }
        session.audio.clear()
        session.microphoneStreams.clear()
        session.microphones.values.forEach { it.close() }
        session.microphones.clear()
        current = null
        latestContext.clear()
        refreshMicrophoneCapability()
        // Recovery handlers stay with the session: a resumed main stream keeps DiPlay's original
        // registration, and DiPlay itself ignores requests for a stream that is gone.
        if (session.announced) sink?.session(SessionState(session.id, SessionPhase.ENDED))
    }

    override fun control(event: ControlEvent): Boolean {
        val session = synchronized(gate) { if (sink == null) null else current } ?: return false
        return when (event) {
            is ControlEvent.MicPacket -> synchronized(gate) {
                if (current !== session || sink == null) false
                else session.microphones[event.stream]?.offer(event.data) ?: false
            }
            is ControlEvent.Touch -> {
                if (event.screen != ScreenId.MAIN) return false
                // DiPlay's SessionControl queues touch without I/O on this thread.
                synchronized(gate) {
                    if (current !== session || sink == null) false else session.control.sendTouch(
                        event.contacts.map { AirPlayContact(it.id, it.x, it.y, it.pressed) },
                    )
                }
            }
            is ControlEvent.KeyFrameRequest -> {
                val accepted = synchronized(gate) {
                    if (sink == null || current !== session || event.screen !in session.handlers) false
                    else { session.requests.request(event.screen.value, nowMs()); true }
                }
                if (!accepted) {
                    recordKeyframe("Screen ${event.screen.value}: no recovery handler")
                    Log.w(TAG, "Keyframe request for ${event.screen.value} dropped: no recovery handler")
                    return false
                }
                try {
                    if (recoveryQueued.compareAndSet(false, true)) recovery.execute {
                        recoveryQueued.set(false)
                        flushKeyframes()
                    }
                    true
                } catch (_: RejectedExecutionException) {
                    recoveryQueued.set(false)
                    recordKeyframe("Screen ${event.screen.value}: queue full")
                    false
                }
            }
            is ControlEvent.Key -> {
                // 车机的方向盘/中控按键down 和 up 成对到达，而一次 CarPlay media HID
                // 报告就代表一次完整按压，所以只在 down 边沿发送、up 边沿消费掉，
                // 否则成对的事件会重复触发。真实车机在 CarPlay 会话之外
                // 也会忽略媒体键，所以会话结束后这里返回 false。
                if (!event.down) return true
                synchronized(gate) {
                    if (current !== session || sink == null) false
                    else when (event.code) {
                        KeyCode.NEXT -> session.control.sendMediaButton(CarPlayMediaButton.NEXT)
                        KeyCode.PREVIOUS -> session.control.sendMediaButton(CarPlayMediaButton.PREVIOUS)
                        // 故意不处理 PLAY_PAUSE 和 VOICE：
                        // 播放/暂停由车机自己处理（CarPlayMediaButton.forCarPlayWindow
                        //  只接管 next 和 previous，把 play/pause 留给车机），
                        //  这里再转发只会和车机抢，根本到不了 iPhone；
                        //  语音键唤起的是车机自己的助手，不需要转发给 iPhone 的 Siri。
                        else -> false
                    }
                }
            }
            else -> false
        }
    }

    override fun close() {
        stop()
        recovery.shutdownNow()
    }
}

private const val TAG = "OpenXPlay Relay"
