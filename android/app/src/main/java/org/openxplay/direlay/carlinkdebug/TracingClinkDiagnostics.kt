// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18

package org.openxplay.direlay.carlinkdebug

import org.openxplay.direlay.clink.*
import org.openxplay.stream.clink.trace.*

class TracingClinkDiagnostics(private val delegate: ClinkDiagnostics, private val trace: ClinkTrace,
    private val session: () -> Long = { 0 }) : ClinkDiagnostics {
    override fun traceLink(stage: String, fields: Map<String, String>) {
        if (trace === ClinkTrace.NONE) return
        try { trace.record(ClinkTraceEvent.Link(System.nanoTime(), session(), stage, fields)) } catch (_: Exception) { }
    }
    override fun handoff(ssid: String, passphraseLength: Int) {
        if (trace !== ClinkTrace.NONE) emit(ClinkDiagnostic.Handoff(ssid, passphraseLength))
    }
    override fun emit(event: ClinkDiagnostic) {
        delegate.emit(event)
        if (trace === ClinkTrace.NONE) return
        val stage = when (event) {
            is ClinkStage -> event.code
            is ClinkDiagnostic.Failure -> event.stage.code
            else -> event.line().substringBefore(' ').substringBefore('=')
        }
        val fields = when (event) {
            is ClinkDiagnostic.Handoff -> mapOf("ssid" to event.ssid, "passphraseLength" to event.passphraseLength.toString())
            else -> mapOf("detail" to event.line())
        }
        try { trace.record(ClinkTraceEvent.Link(System.nanoTime(), session(), stage, fields)) } catch (_: Exception) { }
    }
}
