// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.ui

import android.os.Bundle
import androidx.compose.runtime.saveable.Saver
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.openxplay.direlay.settings.*

/** Rotation retains unsaved choices without publishing them into the runtime's preferences. */
fun relaySettingsSaver(base: RelaySettings) = Saver<RelaySettings, Bundle>(save = { value ->
    Bundle().apply {
        putStringArrayList("text", arrayListOf(value.connectionMode.name, value.mfiTarget.name,
            value.i2cPath, value.remoteServer, value.remoteToken, value.carlinkDeviceName, value.carlinkDeviceModel,
            value.microphoneSource.name, value.wirelessNetwork.choice.name, value.wirelessNetwork.manualSsid,
            value.wirelessNetwork.manualPassphrase, value.wirelessNetwork.band.name, value.wirelessNetwork.security.name,
            SharedWifiNetworks.encode(value.wirelessNetwork.sharedWifiNetworks)))
        putBooleanArray("flags", booleanArrayOf(value.autoStartOnBoot, value.autoStartWhenReady,
            value.localPreview, value.carlinkOutput, value.playCarPlayAudio, value.bothOutputs, value.vehicleInformation))
        putIntArray("numbers", intArrayOf(value.fps, value.webPort ?: 0, value.wirelessNetwork.channel))
        putBoolean("carlinkAutoReconnect", value.carlinkAutoReconnect)
        putBoolean("carlinkTrace", value.carlinkTrace)
        putString("carlinkHevc", value.carlinkHevc.name)
        putBoolean("carPlayViewAreas", value.viewAreas)
        putStringArrayList("car", arrayListOf(value.car.brand, value.car.brandText, value.car.model, value.car.modelText))
        putString("iphoneAddress", value.iphoneAddress)
        putString("displayMode", value.displayMode.name)
        putString("videoCodec", value.videoCodec.name)
        putString("carplayResolution", value.carplayResolution.name)
        putInt("carplayScreenWidth", value.carplayScreenWidth.mm)
        value.display?.let { putIntArray("display", intArrayOf(it.width, it.height, it.density)) }
    }
}, restore = { bundle ->
    val text = requireNotNull(bundle.getStringArrayList("text"))
    val flags = requireNotNull(bundle.getBooleanArray("flags"))
    val numbers = requireNotNull(bundle.getIntArray("numbers"))
    base.copy(connectionMode = ConnectionMode.valueOf(text[0]), mfiTarget = MfiTarget.valueOf(text[1]),
        i2cPath = text[2], remoteServer = text[3], remoteToken = text[4], carlinkDeviceName = text[5], carlinkDeviceModel = text[6],
        microphoneSource = MicrophoneSource.valueOf(text[7]),
        autoStartOnBoot = flags[0], autoStartWhenReady = flags[1], localPreview = flags[2], carlinkOutput = flags[3],
        playCarPlayAudio = flags[4], bothOutputs = flags[5], vehicleInformation = flags.getOrElse(6) { false }, carlinkAutoReconnect = bundle.getBoolean("carlinkAutoReconnect", flags.getOrElse(8) { true }), fps = numbers[0], webPort = numbers[1].takeIf { it != 0 },
        videoCodec = VideoCodecSetting.entries.firstOrNull { it.name == bundle.getString("videoCodec") } ?: base.videoCodec,
        carplayResolution = CarPlayResolution.entries.firstOrNull { it.name == bundle.getString("carplayResolution") } ?: base.carplayResolution,
        carplayScreenWidth = CarPlayScreenWidth.entries.firstOrNull { it.mm == bundle.getInt("carplayScreenWidth", base.carplayScreenWidth.mm) } ?: base.carplayScreenWidth,
        carlinkTrace = bundle.getBoolean("carlinkTrace", false),
        carlinkHevc = org.openxplay.stream.clink.ClinkHevcMode.entries.firstOrNull { it.name == bundle.getString("carlinkHevc") } ?: base.carlinkHevc,
        viewAreas = bundle.getBoolean("carPlayViewAreas", base.viewAreas),
        car = bundle.getStringArrayList("car")?.takeIf { it.size == 4 }?.let { CarSelection(it[0], it[1], it[2], it[3]) } ?: base.car,
        iphoneAddress = bundle.getString("iphoneAddress"),
        displayMode = migratedDisplayMode(bundle.getString("displayMode"), bundle.getIntArray("display")?.let { DisplayProfile(it[0], it[1], it[2]) }),
        display = bundle.getIntArray("display")?.let { DisplayProfile(it[0], it[1], it[2]) },
        wirelessNetwork = WirelessNetworkSettings(WirelessNetworkChoice.fromSavedName(text[8]), text[9], text[10],
            ManualHotspotBand.valueOf(text[11]), numbers[2], ManualHotspotSecurity.valueOf(text[12]), if (text.size > 14) SharedWifiNetworks.migrate(null, text[13], text[14]) else SharedWifiNetworks.decode(text[13])))
})
