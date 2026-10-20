// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
package org.openxplay.direlay.clink

import org.openxplay.stream.clink.ClinkState
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Only structured, credential-free values cross the diagnostics boundary. */
fun interface ClinkDiagnostics {
    fun emit(event: ClinkDiagnostic)
    fun traceLink(stage: String, fields: Map<String, String>) { }
    fun handoff(ssid: String, passphraseLength: Int) { }

    companion object {
        val NONE = ClinkDiagnostics { }
    }
}

enum class ClinkStage(val code: String) : ClinkDiagnostic {
    CLEANUP_WAIT("link_cleanup_wait"), CLEANUP_FORCE("link_cleanup_force"), CLEANUP_DONE("link_cleanup_done"),
    GATT_DISCONNECT("gatt_disconnect_started"), NETWORK_RELEASE("network_request_releasing"),
    GATT_CLOSED("gatt_closed"), NETWORK_RELEASED("network_request_released"),
    OUTPUT_START("output_start"), OUTPUT_STOP("output_stop"), LINK_START("link_start"),
    LINK_STOP("link_stop"), BLE_SCAN("ble_scan_started"), BLE_PEER("ble_peer_found"),
    GATT_CONNECT("gatt_connect_started"), GATT_CONNECTED("gatt_connected"),
    GATT_SERVICES("gatt_services_discovered"), BLE_EXCHANGE("ble_exchange"),
    WIFI_JOIN("wifi_join"), TCP_CONNECT("tcp_connect_started"), TCP_CONNECTED("tcp_connected"),
    AUTH_PINNED("auth_pinned_key"), AUTH_PIN("auth_pin_requested"),
    AUTH_CONFIRM("auth_confirmation_requested"), AUTH("auth"), AUTH_SUCCESS("auth_success"),
    LINK_LOST("link_lost"), SESSION("session");
    override fun line(): String = code
}

sealed interface ClinkDiagnostic {
    fun line(): String
    data class Retry(val delayMs: Long) : ClinkDiagnostic {
        override fun line() = "link_retry delayMs=$delayMs"
    }
    data class Handoff(val ssid: String, val passphraseLength: Int) : ClinkDiagnostic {
        override fun line() = "wifi_handoff ssid=$ssid passphraseLength=$passphraseLength"
    }
    data class Exchange(val frequency: Int) : ClinkDiagnostic {
        override fun line() = "ble_exchange_done frequency=$frequency"
    }
    data class BleRequest(val type: Int, val legacy: Boolean) : ClinkDiagnostic {
        override fun line() = "ble_request type=$type subscription=${if (legacy) "legacy" else "cccd"}"
    }
    enum class CccdResult { SUCCESS, FAILED, TIMEOUT }
    data class Cccd(val result: CccdResult) : ClinkDiagnostic {
        override fun line() = "ble_cccd result=${result.name.lowercase()}"
    }
    data class ServiceChanged(val state: String) : ClinkDiagnostic {
        override fun line() = "ble_service_changed state=$state"
    }
    data class Reply(val type: Int) : ClinkDiagnostic {
        override fun line() = "ble_reply type=$type"
    }
    enum class JoinReason { HEAD_UNIT_SOFT_AP, P2P_AVAILABLE, P2P_UNAVAILABLE, CARPLAY_RESERVED, P2P_FALLBACK, API_UNSUPPORTED }
    data class JoinChoice(val method: WifiJoinPolicy.Method, val reason: JoinReason) : ClinkDiagnostic {
        override fun line() = "wifi_join_method method=${method.name.lowercase()} reason=${reason.name.lowercase()}"
    }
    data class Joined(val peer: String, val local: String) : ClinkDiagnostic {
        init { require(WifiJoinPolicy.endpoints(peer, local) != null) }
        override fun line() = "wifi_join_success peer=$peer local=$local"
    }
    data class State(val state: ClinkState) : ClinkDiagnostic {
        override fun line() = "state=${state.name}"
    }
    data class Failure(val stage: ClinkStage, val code: String, val exceptionClass: String) : ClinkDiagnostic {
        override fun line() = "${stage.name.lowercase()} failed reason=$code exception=$exceptionClass"
    }
}

internal enum class ClinkFailureReason(val code: String) {
    BLE_TIMEOUT("ble_timeout"), BLE_UNAVAILABLE("ble_unavailable"), BLE_SCAN("ble_scan_failed"),
    GATT_CONNECT("gatt_connect_failed"), GATT_SERVICES("gatt_services_failed"),
    BLE_REQUEST_REJECTED("ble_request_rejected"), BLE_CCCD_TIMEOUT("ble_cccd_timeout"),
    BLE_EXCHANGE("ble_exchange_failed"), BLE_EXCHANGE_TIMEOUT("ble_exchange_timeout"),
    P2P_ERROR("wifi_join_p2p_error"), P2P_UNSUPPORTED("wifi_join_p2p_unsupported"),
    P2P_BUSY("wifi_join_p2p_busy"), P2P_UNKNOWN("wifi_join_p2p_unknown"),
    P2P_TIMEOUT("wifi_join_p2p_timeout"), WIFI_UNAVAILABLE("wifi_join_unavailable"),
    WIFI_BIND("wifi_bind_failed"), WIFI_TIMEOUT("wifi_join_timeout"), LINK_LOST("wifi_link_lost");
}

internal class ClinkFailure(val reason: ClinkFailureReason, cause: Throwable? = null) :
    IllegalStateException(reason.code, cause)

/** Match exact protocol codes only. Never parse or copy platform exception messages. */
internal object ClinkFailures {
    private val protocolCodes = setOf("auth_rejected", "auth_pin_proof_failed", "auth_timeout",
        "auth_closed", "auth_internal_error", "auth_signature_failed", "authentication_failed",
        "auth_pin_required", "auth_confirmation_required", "auth_version_unsupported",
        "peer_key_changed", "pin_cancelled", "pairing_confirmation_cancelled", "pin_required",
        "invalid_auth_header", "invalid_auth_signature", "link_lost", "heartbeat_timeout",
        "vehicle_channel_closed")

    fun unwrap(error: Throwable): Throwable {
        var current = error
        repeat(16) {
            if (current !is ExecutionException && current !is CompletionException) return current
            current = current.cause ?: return current
        }
        return current
    }
    fun describe(stage: ClinkStage, error: Throwable): ClinkDiagnostic.Failure {
        val cause = unwrap(error)
        val source = if (cause is ClinkFailure && cause.cause != null) unwrap(cause.cause!!) else cause
        val name = source.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) } ?: "Throwable"
        val code = when {
            cause is ClinkFailure -> cause.reason.code
            (cause is IllegalStateException || cause is IllegalArgumentException || cause is java.io.EOFException) &&
                cause.message in protocolCodes -> cause.message!!
            else -> "unknown:$name"
        }
        return ClinkDiagnostic.Failure(stage, code, name)
    }
    fun p2p(reason: Int): ClinkFailureReason = when (reason) {
        0 -> ClinkFailureReason.P2P_ERROR
        1 -> ClinkFailureReason.P2P_UNSUPPORTED
        2 -> ClinkFailureReason.P2P_BUSY
        else -> ClinkFailureReason.P2P_UNKNOWN
    }
}
