// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-21
package org.openxplay.direlay.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsbHostControlTest {
    @Test fun `the kernel data role decides whether the phone reads the iphone`() {
        // A phone that is a USB device charges the iPhone and the iPhone sends it nothing, which
        // is the state that has to be recognised so the app can say so (2026-10-21). The node
        // reads "[host] device" when the phone drives the bus and "[device] host" when it is the
        // one being driven, so the first word is the phone's own role.
        assertEquals(UsbHostControl.Role.HOST, UsbHostControl.parseRole("[host] device"))
        assertEquals(UsbHostControl.Role.DEVICE, UsbHostControl.parseRole("[device] host"))
        assertEquals(UsbHostControl.Role.HOST, UsbHostControl.parseRole("[host]"))
        assertEquals(UsbHostControl.Role.DEVICE, UsbHostControl.parseRole("[device]"))
    }

    @Test fun `an unreadable role is unknown rather than a failure`() {
        // The node is not readable on every build, and a phone that does not say is not a phone
        // that failed.
        assertEquals(UsbHostControl.Role.UNKNOWN, UsbHostControl.parseRole(""))
        assertEquals(UsbHostControl.Role.UNKNOWN, UsbHostControl.parseRole("unknown"))
        assertEquals(UsbHostControl.Role.UNKNOWN, UsbHostControl.readDataRole() ?: UsbHostControl.Role.UNKNOWN)
    }

    @Test fun `the role is the phone's own side of the cable`() {
        // A C to C cable is detected from either end, so the same cable can put the phone on
        // either side. The word that comes first is the phone's role.
        assertTrue(UsbHostControl.parseRole("[host] device") != UsbHostControl.parseRole("[device] host"))
    }
}
