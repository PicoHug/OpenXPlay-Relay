// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
package org.openxplay.direlay.clink

import org.openxplay.stream.clink.*

/** Observes Stream's existing ports without inspecting identities, PINs or key material. */
class ClinkSessionDiagnostics(pins: PinProvider, private val trust: TrustStore,
    private val diagnostics: ClinkDiagnostics) {
    @Volatile private var phase = ClinkStage.SESSION
    @Volatile private var peerId: String? = null
    val pinProvider: PinProvider = object : PinProvider {
        override fun requestPin(headUnitId: String): String? {
            diagnostics.emit(ClinkStage.AUTH_PIN)
            return pins.requestPin(headUnitId)
        }
        override fun confirmPairing(headUnitId: String): Boolean {
            diagnostics.emit(ClinkStage.AUTH_CONFIRM)
            return pins.confirmPairing(headUnitId)
        }
    }
    val trustStore: TrustStore = object : TrustStore by trust {
        override fun loadPeerKey(headUnitId: String): ByteArray? {
            // Stream reaches this port only after the authentication socket has connected.
            diagnostics.emit(ClinkStage.TCP_CONNECTED)
            phase = ClinkStage.AUTH
            peerId = headUnitId
            return trust.loadPeerKey(headUnitId).also {
                if (it != null) diagnostics.emit(ClinkStage.AUTH_PINNED)
            }
        }
    }
    fun state(state: ClinkState) {
        diagnostics.emit(ClinkDiagnostic.State(state))
        when (state) {
            ClinkState.CONNECTING -> {
                phase = ClinkStage.SESSION
                diagnostics.emit(ClinkStage.OUTPUT_START)
            }
            ClinkState.AUTHENTICATING -> {
                phase = ClinkStage.TCP_CONNECT
                diagnostics.emit(ClinkStage.TCP_CONNECT)
            }
            ClinkState.NEGOTIATING -> {
                diagnostics.emit(ClinkStage.AUTH_SUCCESS)
                phase = ClinkStage.SESSION
            }
            ClinkState.ENDED -> diagnostics.emit(ClinkStage.OUTPUT_STOP)
            else -> Unit
        }
    }
    fun error(error: Throwable) {
        val failure = ClinkFailures.describe(phase, error)
        diagnostics.emit(failure)
        // A pin the head unit no longer honours fails every later attempt, and only wiping app data
        // clears it (2026-10-19). ClinkSession retries in-session; this is the safety net.
        if (failure.code in setOf("peer_key_changed", "auth_signature_failed")) {
            peerId?.let { id -> runCatching { trust.forgetPeer(id) } }
        }
        if (failure.code in setOf("link_lost", "wifi_link_lost", "heartbeat_timeout", "vehicle_channel_closed") ||
            phase == ClinkStage.SESSION && (error is java.io.EOFException || error is java.net.SocketException)) {
            diagnostics.emit(ClinkStage.LINK_LOST)
        }
    }
}
