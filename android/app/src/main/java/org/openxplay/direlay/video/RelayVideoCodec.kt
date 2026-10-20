// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19
package org.openxplay.direlay.video

import org.openxplay.direlay.settings.VideoCodecSetting
import org.openxplay.stream.core.*

/** Until Carlink reports its session decoder, Automatic retains the H.264 startup choice. */
fun carlinkRequiresAvc(connected: Boolean, decoders: List<DecoderSupport>): Boolean =
    connected && !VideoCompatibility.hasHevc(decoders)

/** Without a main-screen viewer, Automatic keeps the last codec it chose so a page reload costs no CarPlay restart. */
fun relayVideoCodec(setting: VideoCodecSetting, outputs: List<OutputCapabilities>, requiresAvc: Boolean,
    hevcFailed: Boolean, remembered: VideoCodec?, carlinkHevc: Boolean = false): VideoCodecDecision = when {
    setting == VideoCodecSetting.H264 -> VideoCodecDecision(VideoCodec.H264, "setting-h264")
    setting == VideoCodecSetting.H265 -> VideoCodecDecision(VideoCodec.H265, "setting-h265")
    hevcFailed -> VideoCodecDecision(VideoCodec.H264, "hevc-failed")
    !requiresAvc && outputs.none { ScreenId.MAIN in it.displays } && remembered != null -> VideoCodecDecision(remembered, "remembered-codec")
    else -> VideoCodecPolicy.resolve(outputs, requiresAvc).let { decision ->
        if (carlinkHevc && decision.codec == VideoCodec.H265) decision.copy(reason = "carlink-hevc") else decision
    }
}

class HevcFailureTracker {
    var failure: String? = null
        private set
    var earlyDrops = 0
        private set
    private var firstFormatMs: Long? = null
    fun format(nowMs: Long) { if (firstFormatMs == null) firstFormatMs = nowMs }
    fun resetSession() { firstFormatMs = null }
    fun ended(nowMs: Long, automaticHevc: Boolean, requested: Boolean) {
        val first = firstFormatMs
        firstFormatMs = null
        if (automaticHevc && !requested && first != null && nowMs - first in 0L..29_999L) {
            earlyDrops++
            if (earlyDrops >= 2 && failure == null) failure = "early-drop waitMs=${nowMs - first} count=$earlyDrops"
        }
    }
    fun watchdog(waitMs: Long?, automaticHevc: Boolean) {
        if (automaticHevc && waitMs != null && waitMs >= 8_000 && failure == null)
            failure = "keyframe-timeout waitMs=$waitMs"
    }
}

/** One coalesced request per screen, cancelled by a published key frame. Caller serializes access. */
class KeyframeRequests {
    private data class Request(var lastSent: Long? = null, var pending: Boolean = false, var since: Long? = null)
    private val screens = mutableMapOf<Int, Request>()
    fun request(screen: Int, nowMs: Long) {
        val state = screens.getOrPut(screen) { Request() }
        state.pending = true
        if (state.since == null) state.since = nowMs
    }
    fun published(screen: Int) { screens[screen]?.let { it.pending = false; it.since = null } }
    fun waitMs(screen: Int, nowMs: Long): Long? = screens[screen]?.since?.let { nowMs - it }
    fun due(screen: Int, nowMs: Long): Boolean {
        val state = screens[screen] ?: return false
        if (!state.pending || state.lastSent?.let { nowMs - it < 1_000 } == true) return false
        state.pending = false
        state.lastSent = nowMs
        return true
    }
}
