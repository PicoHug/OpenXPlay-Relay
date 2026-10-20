// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.clink

import org.openxplay.stream.clink.ble.BleCodec
import org.openxplay.stream.clink.ble.HeadUnitReply

/** One GATT attempt. The Android adapter serializes all events on its handler. */
internal class BleExchange(private val gatt: Gatt, private val phone: ByteArray,
    private val legacy: Boolean = false,
    private val complete: (HeadUnitReply?, Throwable?) -> Unit) {
    interface Gatt {
        fun requestMtu(size: Int): Boolean
        fun discover(): Boolean
        fun subscribeLocally(): Boolean
        fun subscribeCccd(): Boolean
        fun write(payload: ByteArray): Boolean
        /** After a rediscovery: the Carlink service still holds the request characteristic that was written. */
        fun requestUnchanged(): Boolean
    }
    enum class State { CONNECTING, MTU, SERVICES, SUBSCRIBING, REDISCOVERING, WRITING, REPLY, VERIFYING, DONE }
    enum class Reason { FAILED, CCCD_TIMEOUT, REQUEST_REJECTED }
    class Failure(val reason: Reason) : IllegalStateException(reason.name)
    var state = State.CONNECTING
        private set
    private var earlyReply: HeadUnitReply? = null
    private val replyBytes = java.io.ByteArrayOutputStream()
    private var fragments = 0
    // VERIFYING: the state to resume, whether its rediscovery was issued, and a write failure seen meanwhile.
    private var resume = State.WRITING
    private var verifyDiscovering = false
    private var writeFailed = false
    fun connected() {
        if (state != State.CONNECTING) return
        state = State.MTU
        if (!gatt.requestMtu(512)) mtu(false)
    }
    fun mtu(success: Boolean) {
        if (state != State.MTU) return
        // A refused MTU still permits Android's long characteristic write.
        state = State.SERVICES
        if (!gatt.discover()) fail()
    }
    fun services(success: Boolean) {
        if (state == State.VERIFYING) {
            if (!verifyDiscovering) return
            when {
                !success -> fail()
                !gatt.requestUnchanged() -> fail(Reason.REQUEST_REJECTED)
                writeFailed -> fail()
                // The head unit re-published its database: continue with the same request.
                else -> state = resume
            }
            return
        }
        if (state != State.SERVICES) return
        if (!success || !gatt.subscribeLocally()) { fail(); return }
        if (legacy) writeRequest() else {
            state = State.SUBSCRIBING
            // A refused descriptor write means this head unit cannot take the CCCD path either;
            // report it as a subscription failure so the next attempt falls back to legacy.
            if (!gatt.subscribeCccd()) fail(Reason.CCCD_TIMEOUT)
        }
    }
    fun descriptorWritten(success: Boolean) {
        if (state == State.REDISCOVERING) {
            state = State.SERVICES
            if (!gatt.discover()) fail()
            return
        }
        if (state != State.SUBSCRIBING) return
        if (success) writeRequest() else fail(Reason.CCCD_TIMEOUT)
    }
    fun cccdTimeout() {
        if (state == State.SUBSCRIBING || state == State.REDISCOVERING) fail(Reason.CCCD_TIMEOUT)
    }
    fun serviceChanged() {
        if (state == State.DONE || state == State.CONNECTING || state == State.MTU || state == State.VERIFYING) return
        if (state == State.WRITING || state == State.REPLY) {
            // Only a vanished service or request characteristic rejects the request; confirm it first.
            if (earlyReply == null) verify()
            return
        }
        // Drain the descriptor operation before issuing another GATT operation.
        if (state == State.SUBSCRIBING || state == State.REDISCOVERING) {
            state = State.REDISCOVERING
            return
        }
        state = State.SERVICES
        if (!gatt.discover()) fail()
    }
    fun serviceRemoved() {
        when (state) {
            State.VERIFYING -> if (verifyDiscovering) fail(Reason.REQUEST_REJECTED)
            State.WRITING, State.REPLY -> if (earlyReply == null) fail(Reason.REQUEST_REJECTED)
            else -> fail()
        }
    }
    /** The bounded rediscovery did not finish: unconfirmed, so a plain failure rather than a rejection. */
    fun verifyTimeout() {
        if (state == State.VERIFYING) fail()
    }
    private fun verify() {
        resume = state
        state = State.VERIFYING
        verifyDiscovering = false
        // Drain a pending write first: Android drops its callback while services are rediscovered.
        if (resume == State.REPLY) rediscover()
    }
    private fun rediscover() {
        verifyDiscovering = true
        if (!gatt.discover()) fail()
    }
    private fun writeRequest() {
        state = State.WRITING
        if (!gatt.write(phone.copyOf())) fail()
    }
    fun written(success: Boolean) {
        if (state == State.VERIFYING && resume == State.WRITING && !verifyDiscovering) {
            // Judge a failed write after the rediscovery: a vanished service makes it a rejection.
            if (success) resume = State.REPLY else writeFailed = true
            rediscover()
            return
        }
        if (state != State.WRITING) return
        if (!success) { fail(); return }
        state = State.REPLY
        earlyReply?.let { finish(it) }
    }
    fun indication(bytes: ByteArray) {
        val active = if (state == State.VERIFYING) resume else state
        if (active != State.WRITING && active != State.REPLY) return
        try {
            require(++fragments <= 128 && replyBytes.size() + bytes.size <= 4096)
            replyBytes.write(bytes)
            val accumulated = replyBytes.toByteArray()
            if (!completeJson(accumulated)) return
            val reply = BleCodec.headUnitReply(accumulated)
            if (state == State.VERIFYING) {
                // A complete reply shows the head unit accepted the request.
                if (writeFailed) { fail(); return }
                state = resume
            }
            if (state == State.WRITING) earlyReply = reply else finish(reply)
        } catch (_: Exception) { fail() }
    }
    // Frame before UTF-8 decoding: indications can split JSON strings and multibyte characters.
    private fun completeJson(bytes: ByteArray): Boolean {
        val nesting = java.util.ArrayDeque<Int>()
        var quoted = false
        var escape = false
        var started = false
        var finished = false
        for (byte in bytes) {
            val char = byte.toInt() and 255
            val space = char == 32 || char in 9..13
            if (finished) { require(space); continue }
            if (!started) {
                if (space) continue
                require(char == 123)
                started = true
                nesting.push(char)
            } else if (quoted) {
                if (escape) escape = false
                else if (char == 92) escape = true
                else if (char == 34) quoted = false
            } else when (char) {
                34 -> quoted = true
                123, 91 -> { nesting.push(char); require(nesting.size <= 16) }
                125, 93 -> {
                    require(!nesting.isEmpty() && nesting.pop() == if (char == 125) 123 else 91)
                    finished = nesting.isEmpty()
                }
            }
        }
        return finished
    }
    fun fail(reason: Reason = Reason.FAILED) {
        if (state == State.DONE) return
        state = State.DONE
        complete(null, Failure(reason))
    }
    private fun finish(reply: HeadUnitReply) {
        state = State.DONE
        complete(reply, null)
    }
}
