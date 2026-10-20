// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-16
package org.openxplay.direlay.clink

/** Request type and the subscription workaround per head unit, both surviving restarts. */
internal class BlePeerPolicy(private val store: Store = object : Store {
    override fun read(key: String): Int? = null
    override fun write(key: String, type: Int) {}
}) {
    interface Store {
        fun read(key: String): Int?
        fun write(key: String, type: Int)
    }
    data class Choice(val requestType: Int = 1002, val legacy: Boolean = false)
    private val peers = mutableMapOf<String, Choice>()
    fun key(carId: String, address: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((if (carId.isNotBlank()) "car:$carId" else "address:$address").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    @Synchronized fun choice(key: String): Choice = peers[key] ?: decode(store.read(key)) ?: Choice(1002)
    @Synchronized fun cccdTimedOut(key: String, choice: Choice) {
        // Persist the workaround: a head unit that rejects the descriptor write otherwise pays a 2 s
        // timeout plus a full reconnect on every later session (2026-10-19).
        val next = choice.copy(legacy = true)
        peers[key] = next
        store.write(key, encode(next))
    }
    @Synchronized fun rejected(key: String, choice: Choice) {
        peers[key] = choice.copy(requestType = if (choice.requestType == 1002) 1001 else 1002)
    }
    @Synchronized fun succeeded(key: String, choice: Choice) {
        peers[key] = choice
        store.write(key, encode(choice))
    }
    private companion object {
        const val LEGACY = 1000
        fun encode(choice: Choice): Int = choice.requestType + if (choice.legacy) LEGACY else 0
        fun decode(store: Int?): Choice? {
            val value = store ?: return null
            return when (value) {
                1001, 1002 -> Choice(value)
                LEGACY + 1001, LEGACY + 1002 -> Choice(value - LEGACY, legacy = true)
                else -> null
            }
        }
    }
}
