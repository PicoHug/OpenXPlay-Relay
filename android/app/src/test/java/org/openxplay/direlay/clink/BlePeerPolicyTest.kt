// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-16
package org.openxplay.direlay.clink

import org.junit.Assert.*
import org.junit.Test

class BlePeerPolicyTest {
    @Test fun defaultsToDualModeAndCccdWithIndependentPeers() {
        val policy = BlePeerPolicy()
        assertEquals(BlePeerPolicy.Choice(1002, false), policy.choice("first"))
        policy.cccdTimedOut("first", policy.choice("first"))
        policy.rejected("first", policy.choice("first"))
        assertEquals(BlePeerPolicy.Choice(1001, true), policy.choice("first"))
        assertEquals(BlePeerPolicy.Choice(1002, false), policy.choice("second"))
        policy.rejected("first", policy.choice("first"))
        assertEquals(BlePeerPolicy.Choice(1002, true), policy.choice("first"))
    }
    @Test fun remembersSuccessAcrossAddressesButOnlyWithinSession() {
        val policy = BlePeerPolicy()
        val key = policy.key("car-id", "old-address")
        assertEquals(key, policy.key("car-id", "new-address"))
        val choice = BlePeerPolicy.Choice(1001, true)
        policy.succeeded(key, choice)
        assertEquals(choice, policy.choice(policy.key("car-id", "new-address")))
        assertNotEquals(policy.key("", "one"), policy.key("", "two"))
        assertNotEquals(policy.key("one", "one"), policy.key("", "one"))
        assertEquals(BlePeerPolicy.Choice(), BlePeerPolicy().choice(key))
    }
    private class Store : BlePeerPolicy.Store {
        val values = mutableMapOf<String, Int>()
        override fun read(key: String): Int? = values[key]
        override fun write(key: String, type: Int) { values[key] = type }
    }
    @Test fun successfulTypesPersistPerHashedPeerAcrossPolicyRecreation() {
        val store = Store()
        val first = BlePeerPolicy(store)
        val key = first.key("Personal car name", "address")
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertFalse(key.contains("Personal"))
        first.rejected(key, first.choice(key))
        // A rejected choice must not overwrite the last successful type on disk.
        assertTrue(store.values.isEmpty())
        first.succeeded(key, first.choice(key))
        val next = BlePeerPolicy(store)
        assertEquals(1001, next.choice(next.key("Personal car name", "rotated")).requestType)
        assertEquals(1002, next.choice(next.key("Another car", "address")).requestType)
        next.rejected(key, next.choice(key))
        assertEquals(1002, next.choice(key).requestType)
        assertEquals(1001, BlePeerPolicy(store).choice(key).requestType)
        next.succeeded(key, next.choice(key))
        assertEquals(1002, BlePeerPolicy(store).choice(key).requestType)
    }
    @Test fun subscriptionTimeoutKeepsTheTypeAndIsRememberedAcrossPolicies() {
        val store = Store()
        val policy = BlePeerPolicy(store)
        policy.succeeded("car", BlePeerPolicy.Choice(1001))
        policy.cccdTimedOut("car", policy.choice("car"))
        assertEquals(1001, policy.choice("car").requestType)
        // The workaround must survive: a head unit without a working CCCD path would otherwise pay
        // the subscription timeout plus a reconnect on every later session.
        assertEquals(BlePeerPolicy.Choice(1001, true), BlePeerPolicy(store).choice("car"))
        store.values["bad"] = 99
        assertEquals(1002, BlePeerPolicy(store).choice("bad").requestType)
    }

}
