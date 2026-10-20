// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.ui

import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.Assert.*
import org.junit.Test
import org.openxplay.direlay.bluetooth.IphoneSelection
import org.openxplay.direlay.bluetooth.BondedDevice
import org.openxplay.direlay.R
import org.openxplay.direlay.relay.WebStartReason
import org.openxplay.direlay.relay.WebStartFailure
import org.openxplay.direlay.settings.*
import org.openxplay.stream.core.SessionPhase

class HomePresentationTest {
    private val settings = RelaySettings(ConnectionMode.WIRELESS, MfiTarget.LOCAL, "", "", "", null,
        30, false, true, false, null, wirelessNetwork = WirelessNetworkSettings(choice = WirelessNetworkChoice.WIFI_DIRECT))
    private val resolved = IphoneSelection.OnlyCandidate(BondedDevice("00:11:22:33:A9:30", "Phone", true, true, false))
    private val ready = HomeSnapshot(settings, wireless = true, permissionsReady = true, identityReady = true, iphoneSelection = resolved)

    @Test fun webStartFailuresExplainPortConflictsAndAutomaticRetries() {
        for (both in listOf(false, true)) {
            val busy = presentHome(ready.copy(phase = SessionPhase.ACTIVE,
                settings = settings.copy(carlinkOutput = both, bothOutputs = both),
                webReason = WebStartReason(WebStartFailure.PORT_BUSY, 9443)))
            assertEquals(R.string.ui_error, busy.headline)
            assertEquals(StatusTone.ERROR, busy.tone)
            assertEquals(R.string.ui_web_port_busy, busy.subline)
            assertEquals(listOf("9443"), busy.sublineArguments)
        }
        val other = presentHome(ready.copy(webReason = WebStartReason(WebStartFailure.OTHER, 8443)))
        assertEquals(R.string.ui_error, other.headline)
        assertEquals(R.string.ui_web_start_failed, other.subline)
        assertTrue(other.sublineArguments.isEmpty())
        val disabled = presentHome(ready.copy(settings = settings.copy(carlinkOutput = true),
            webReason = WebStartReason(WebStartFailure.PORT_BUSY, 8443)))
        assertNotEquals(StatusTone.ERROR, disabled.tone)
    }

    @Test fun listeningWithoutCarFacingNetworkExplainsWifiOrHotspot() {
        val missing = ready.copy(phase = SessionPhase.ACTIVE, webListening = true, carNetworkAvailable = false)
        assertEquals(R.string.ui_head_unit_network, presentHome(missing).subline)
        assertEquals(R.string.ui_showing_browser, presentHome(missing.copy(carNetworkAvailable = true)).subline)
        assertEquals(R.string.ui_showing_carlink, presentHome(missing.copy(settings = settings.copy(carlinkOutput = true))).subline)
    }

    @Test fun allSixStatusCardsAndReconnectMapToPlainLanguage() {
        val cases = listOf(
            ready.copy(identityReady = false) to (R.string.ui_setup_needed to StatusTone.ATTENTION),
            ready to (R.string.ui_ready to StatusTone.IDLE),
            ready.copy(started = true) to (R.string.ui_waiting to StatusTone.PROGRESS),
            ready.copy(phase = SessionPhase.CONNECTING) to (R.string.ui_connecting to StatusTone.PROGRESS),
            ready.copy(phase = SessionPhase.RECONNECTING) to (R.string.ui_connecting to StatusTone.PROGRESS),
            ready.copy(phase = SessionPhase.ACTIVE) to (R.string.ui_running to StatusTone.RUNNING),
            ready.copy(failure = true) to (R.string.ui_error to StatusTone.ERROR),
        )
        cases.forEach { (snapshot, expected) ->
            val result = presentHome(snapshot)
            assertEquals(expected.first, result.headline)
            assertEquals(expected.second, result.tone)
        }
        assertEquals(R.string.ui_showing_browser, presentHome(ready.copy(phase = SessionPhase.ACTIVE)).subline)
        assertEquals(R.string.ui_showing_carlink, presentHome(ready.copy(phase = SessionPhase.ACTIVE,
            settings = settings.copy(carlinkOutput = true))).subline)
        assertEquals(R.string.ui_showing_both, presentHome(ready.copy(phase = SessionPhase.ACTIVE,
            settings = settings.copy(bothOutputs = true))).subline)
    }

    @Test fun modeReflectsAllChosenModesRatherThanSessionDiagnostics() {
        for (network in WirelessNetworkChoice.entries) for (carlink in listOf(false, true)) for (wireless in listOf(false, true)) {
            val result = presentHome(ready.copy(wireless = wireless, settings = settings.copy(carlinkOutput = carlink,
                wirelessNetwork = settings.wirelessNetwork.copy(choice = network))))
            // USB CarPlay uses no wireless network, so it shows no network row.
            assertEquals(listOfNotNull(if (carlink) R.string.ui_carlink else R.string.ui_browser,
                if (wireless) R.string.direlay_wireless else R.string.ui_usb, setupNetworkLabel(network).takeIf { wireless }),
                result.mode.map { it.value })
        }
    }

    @Test fun checklistOnlyContainsMissingStepsAndEachHasAnAction() {
        assertTrue(presentHome(ready).checklist.isEmpty())
        val missing = presentHome(ready.copy(permissionsReady = false, identityReady = false,
            iphoneSelection = IphoneSelection.NoIphone, validSettings = false))
        assertEquals(listOf(SetupAction.PERMISSIONS, SetupAction.IDENTITY, SetupAction.SETTINGS, SetupAction.IPHONE),
            missing.checklist.map { it.action })
        assertTrue(missing.checklist.all { it.actionLabel != 0 })
        assertFalse(missing.canStart)
        assertFalse(presentHome(ready.copy(checking = true)).canStart)
        assertFalse(presentHome(ready.copy(iphoneSelection = IphoneSelection.NoIphone)).canStart)
        assertEquals(R.string.ui_setup_needed, presentHome(ready.copy(iphoneSelection = IphoneSelection.NoIphone)).headline)
        assertTrue(presentHome(ready.copy(wireless = false, iphoneSelection = IphoneSelection.NoIphone)).checklist.isEmpty())
    }

    @Test fun unresolvedPhoneSelectionExplainsPairingOrOpensPickerAndBlocksStart() {
        val device = BondedDevice("00:11:22:33:A9:30", "Phone", true, true, false)
        val cases = listOf(
            IphoneSelection.NoIphone to (R.string.iphone_pair to SetupAction.IPHONE),
            IphoneSelection.SelectedUnpaired(device.address) to (R.string.iphone_unpaired to SetupAction.CHOOSE_IPHONE),
            IphoneSelection.MultipleConnected(listOf(device)) to (R.string.iphone_multiple to SetupAction.CHOOSE_IPHONE),
            IphoneSelection.MultipleCandidates(listOf(device)) to (R.string.iphone_multiple to SetupAction.CHOOSE_IPHONE),
        )
        cases.forEach { (selection, expected) ->
            val result = presentHome(ready.copy(iphoneSelection = selection))
            assertFalse(result.canStart)
            assertEquals(expected.first, result.checklist.single().text)
            assertEquals(expected.second, result.checklist.single().action)
        }
        assertTrue(presentHome(ready.copy(wireless = false, iphoneSelection = IphoneSelection.NoIphone)).canStart)
    }

    @Test fun endedSessionIsReadyAndActiveSessionOffersStopEvenIfPermissionWasRevoked() {
        assertFalse(presentHome(ready.copy(phase = SessionPhase.ENDED)).running)
        assertTrue(presentHome(ready.copy(phase = SessionPhase.ACTIVE, permissionsReady = false)).running)
    }

    @Test fun pausedAutomaticExplainsHowToResumeOnlyWhileIdle() {
        val paused = ready.copy(autoStartPaused = true)
        assertEquals(R.string.ui_automatic_paused, presentHome(paused).subline)
        assertEquals(R.string.ui_start_hint, presentHome(paused.copy(settings = settings.copy(autoStartWhenReady = false))).subline)
        assertEquals(R.string.ui_showing_browser, presentHome(paused.copy(phase = SessionPhase.ACTIVE)).subline)
        assertNotEquals(R.string.ui_automatic_paused, presentHome(paused.copy(started = true)).subline)
    }
    @Test fun sharedWifiSetupBlocksStartAndExplainsUnreadableNames() {
        val missing = presentHome(ready.copy(sharedWifiReady = false, currentWifi = "Unsaved"))
        assertFalse(missing.canStart)
        assertTrue(missing.checklist.contains(SetupItem(R.string.shared_wifi_not_saved, R.string.shared_wifi_save_current, SetupAction.SHARED_WIFI)))
        val unreadable = presentHome(ready.copy(sharedWifiReady = false))
        assertFalse(unreadable.canStart)
        assertTrue(unreadable.checklist.contains(SetupItem(R.string.shared_wifi_unreadable, R.string.shared_wifi_use, SetupAction.SHARED_WIFI)))
        assertTrue(presentHome(ready.copy(sharedWifiReady = true)).canStart)
    }
}
