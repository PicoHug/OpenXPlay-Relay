// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-19
package org.openxplay.direlay.relay

enum class WebStartFailure { PORT_BUSY, OTHER }
data class WebStartReason(val failure: WebStartFailure, val port: Int)

internal fun webStartReason(failure: Throwable, port: Int): WebStartReason = WebStartReason(
    if (generateSequence(failure) { it.cause }.any { it is java.net.BindException }) WebStartFailure.PORT_BUSY
    else WebStartFailure.OTHER, port)

internal fun webStartRetryDelay(attempt: Int): Long = when (attempt) {
    0 -> 200L
    1 -> 500L
    2 -> 1000L
    3 -> 2000L
    else -> 5000L
}
