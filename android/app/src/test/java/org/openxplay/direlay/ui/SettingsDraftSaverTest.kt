// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
package org.openxplay.direlay.ui

import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.saveable.SaverScope
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.openxplay.direlay.settings.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class SettingsDraftSaverTest {
    private val base = RelaySettings(ConnectionMode.WIRELESS, MfiTarget.LOCAL, "", "", "", null,
        30, false, true, false, null)
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
    private fun bundle(value: RelaySettings): Bundle = with(relaySettingsSaver(base)) { requireNotNull(scope.save(value)) }
    @Test fun everyVideoCodecDraftSurvivesRotationAndOldDraftsKeepTheBase() {
        for (codec in VideoCodecSetting.entries) {
            val value = base.copy(videoCodec = codec)
            val saved = bundle(value)
            assertEquals(codec.name, saved.getString("videoCodec"))
            assertEquals(value, relaySettingsSaver(base).restore(saved))
        }
        val old = bundle(base); old.remove("videoCodec")
        val remembered = base.copy(videoCodec = VideoCodecSetting.H265)
        assertEquals(remembered, relaySettingsSaver(remembered).restore(old))
    }
    @Test fun everyScreenWidthDraftUsesTheNewNumericKey() {
        for (width in CarPlayScreenWidth.entries) {
            val value = base.copy(carplayScreenWidth = width)
            val saved = bundle(value)
            assertEquals(width.mm, saved.getInt("carplayScreenWidth"))
            assertEquals(value, relaySettingsSaver(base).restore(saved))
        }
        val old = bundle(base)
        old.remove("carplayScreenWidth"); old.putString("carplayIconSize", "ignored")
        assertEquals(base, relaySettingsSaver(base).restore(old))
    }
    @Test fun carAndViewAreasDraftRoundTripAndOldExperimentalKeyIsIgnored() {
        for (areas in listOf(false, true)) for (car in listOf(CarSelection(), CarSelection("xpeng", model = "g6-2023"),
            CarSelection("other", "My brand ", "other", "My model "))) {
            val value = base.copy(viewAreas = areas, car = car)
            assertEquals(value, relaySettingsSaver(base).restore(bundle(value)))
        }
        val old = bundle(base)
        old.remove("carPlayViewAreas"); old.remove("car"); old.putBoolean("viewAreas", false)
        assertEquals(base, relaySettingsSaver(base).restore(old))
    }
    @Test fun everyNetworkChoiceAndPhoneSelectionRoundTripWithCredentials() {
        for (choice in WirelessNetworkChoice.entries) for (selected in listOf(false, true)) {
            val value = base.copy(carlinkAutoReconnect = selected, iphoneAddress = if (selected) "00:11:22:33:A9:30" else null, wirelessNetwork = WirelessNetworkSettings(
                choice = choice, manualSsid = "Phone", manualPassphrase = "12345678",
                sharedWifiNetworks = listOf(SharedWifiNetwork("Router", "abcdefgh"), SharedWifiNetwork("Car", ""))))
            assertEquals(value, relaySettingsSaver(base).restore(bundle(value)))
        }
    }
    @Test fun oldCarHotspotNameAndShortBundlesMigrate() {
        val bundle = bundle(base)
        val text = requireNotNull(bundle.getStringArrayList("text"))
        text[8] = "CAR_HOTSPOT"
        bundle.putStringArrayList("text", text)
        bundle.putBooleanArray("flags", requireNotNull(bundle.getBooleanArray("flags")).copyOf(7))
        val restored = requireNotNull(relaySettingsSaver(base).restore(bundle))
        assertEquals(WirelessNetworkChoice.PHONE_HOTSPOT, restored.wirelessNetwork.choice)
        assertTrue(restored.carlinkAutoReconnect)
    }
    @Test fun oldDraftFlagsKeepCarlinkReconnectAndIgnoreRemovedSlot() {
        for (removed in listOf(false, true)) for (reconnect in listOf(false, true)) {
            val bundle = bundle(base)
            bundle.remove("carlinkAutoReconnect")
            bundle.putBooleanArray("flags", requireNotNull(bundle.getBooleanArray("flags")) + booleanArrayOf(removed, reconnect))
            assertEquals(base.copy(carlinkAutoReconnect = reconnect), relaySettingsSaver(base).restore(bundle))
        }
    }
    @Test fun oldSingleNetworkDraftMigrates() {
        val bundle = bundle(base)
        val text = requireNotNull(bundle.getStringArrayList("text"))
        text[13] = "Legacy"; text.add("legacy-password")
        bundle.putStringArrayList("text", text)
        assertEquals(listOf(SharedWifiNetwork("Legacy", "legacy-password")),
            requireNotNull(relaySettingsSaver(base).restore(bundle)).wirelessNetwork.sharedWifiNetworks)
    }
}
