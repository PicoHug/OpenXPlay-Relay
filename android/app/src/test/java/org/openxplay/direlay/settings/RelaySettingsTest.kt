// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.settings

import android.app.Application
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, application = Application::class)
class RelaySettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val store get() = RelaySettingsStore(context)

    @Test fun viewAreasDefaultOnIgnoreExperimentalKeyAndPersist() {
        val preferences = context.getSharedPreferences("direlay", 0)
        for (old in listOf(false, true)) {
            preferences.edit().putBoolean("viewAreas", old).commit()
            assertTrue(store.load().viewAreas)
        }
        val original = store.load()
        store.save(original.copy(viewAreas = false)); assertFalse(store.load().viewAreas)
        preferences.edit().putBoolean("viewAreas", true).commit()
        assertFalse(store.load().viewAreas)
        store.save(original); assertTrue(store.load().viewAreas)
    }
    @Test fun carSelectionPersistsAndNormalizesOnlyOtherTexts() {
        assertEquals(CarSelection(), store.load().car)
        val original = store.load()
        for (car in listOf(CarSelection("xpeng", model = "g6-2023"), CarSelection("xpeng", model = "other", modelText = " Custom "),
            CarSelection("other", " My brand\n", "other", " My car\t "), CarSelection())) {
            store.save(original.copy(car = car))
            assertEquals(original.copy(car = car.normalized()), store.load())
        }
        store.save(original.copy(car = CarSelection("other", "x".repeat(60), modelText = "y".repeat(60))))
        assertEquals(CarSelection("other", "x".repeat(40), "other", "y".repeat(40)), store.load().car)
        assertEquals(CarSelection(), CarSelection("unknown", "ignored", "other", "ignored").normalized())
    }

    @Before fun clear() {
        for (name in listOf("direlay", "diplay", "xcertplay_airplay")) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    @Test fun pictureSettingsPersistAndTheWebPortPublishesTheSameValues() {
        RelaySettingsStore(context).use { store ->
            val original = store.awaitSnapshot()
            assertEquals(CarPlayResolution.AUTO, original.carplayResolution)
            assertEquals(CarPlayScreenWidth.MM200, original.carplayScreenWidth)
            val seen = java.util.concurrent.CopyOnWriteArrayList<org.openxplay.stream.web.DisplaySettings>()
            val listener = store.listen { seen.add(it) }
            for (resolution in CarPlayResolution.entries) for (size in CarPlayScreenWidth.entries) {
                val value = original.copy(carplayResolution = resolution, carplayScreenWidth = size)
                store.save(value)
                assertEquals(value, store.load())
                assertEquals(value.displaySettings(), store.current())
                val profile = DisplayProfile(1920, 960, 240)
                store.prepareDisplay(profile, size)
                val expected = com.shilapi.xcertplay.airplay.AirPlayDisplaySettings.sanitizeWidthPhysicalMm(physicalWidth(profile, size))
                assertEquals(expected, AirPlayPersistence.loadWidthPhysicalMm(context))
            }
            store.set(org.openxplay.stream.web.DisplaySettings("720p", 125), org.openxplay.stream.core.Origin("web", "test"))
            assertEquals(CarPlayResolution.P720, store.load().carplayResolution)
            assertEquals(CarPlayScreenWidth.MM125, store.load().carplayScreenWidth)
            assertEquals(store.current(), seen.last())
            listener.close()
            val count = seen.size
            store.save(original)
            assertEquals(count, seen.size)
        }
    }

    @Test fun videoCodecPersistsByNameAndPageChangesPublishToAppListeners() {
        RelaySettingsStore(context).use { store ->
            assertEquals(VideoCodecSetting.AUTO, store.awaitSnapshot().videoCodec)
            val seen = java.util.concurrent.CopyOnWriteArrayList<org.openxplay.stream.web.DisplaySettings>()
            store.listen { seen.add(it) }.use {
                for (codec in VideoCodecSetting.entries) {
                    store.save(store.load().copy(videoCodec = codec))
                    assertEquals(codec.name, context.getSharedPreferences("direlay", 0).getString("videoCodec", null))
                    assertEquals(codec, store.load().videoCodec)
                    assertEquals(codec.wire, store.current().videoCodec)
                }
                store.set(store.current().copy(videoCodec = "h265"), org.openxplay.stream.core.Origin("web", "test"))
                assertEquals(VideoCodecSetting.H265, store.snapshot.value!!.videoCodec)
                assertEquals("h265", seen.last().videoCodec)
            }
        }
        context.getSharedPreferences("direlay", 0).edit().putString("videoCodec", "unknown").commit()
        assertEquals(VideoCodecSetting.AUTO, store.load().videoCodec)
    }

    @Test fun screenWidthUsesNewNumericKeyAndIgnoresOldPreference() {
        val preferences = context.getSharedPreferences("direlay", 0)
        preferences.edit().putString("carplayIconSize", "ignored").commit()
        RelaySettingsStore(context).use { store ->
            assertEquals(CarPlayScreenWidth.MM200, store.load().carplayScreenWidth)
            for (preset in CarPlayScreenWidth.entries) {
                store.save(store.load().copy(carplayScreenWidth = preset))
                assertEquals(preset.mm, preferences.getInt("carplayScreenWidth", 0))
                assertEquals(preset, store.load().carplayScreenWidth)
            }
            preferences.edit().putInt("carplayScreenWidth", 150).commit()
            assertEquals(CarPlayScreenWidth.MM200, store.load().carplayScreenWidth)
        }
    }
    @Test fun developerWidthOverrideWinsForPreparedAndFixedDisplays() {
        RelaySettingsStore(context).use { store ->
            val profile = DisplayProfile(2400, 1200, 300)
            store.physicalWidthOverrideMm = 225
            for (preset in CarPlayScreenWidth.entries) {
                store.prepareDisplay(profile, preset)
                assertEquals(225, AirPlayPersistence.loadWidthPhysicalMm(context))
                store.save(store.load().copy(displayMode = DisplayMode.FIXED, display = profile, carplayScreenWidth = preset))
                assertEquals(225, AirPlayPersistence.loadWidthPhysicalMm(context))
            }
            store.physicalWidthOverrideMm = 0
            store.prepareDisplay(profile, CarPlayScreenWidth.MM300)
            assertEquals(375, AirPlayPersistence.loadWidthPhysicalMm(context))
        }
    }

    @Test fun carlinkHevcDefaultsAutomaticPreservesStoredNamesAndRejectsUnknownNames() {
        RelaySettingsStore(context).use { store ->
            assertEquals(org.openxplay.stream.clink.ClinkHevcMode.STREAM_ONLY, store.load().carlinkHevc)
            for (mode in org.openxplay.stream.clink.ClinkHevcMode.entries) {
                // Existing installations persisted the enum name, independent of option order.
                context.getSharedPreferences("direlay", 0).edit().putString("carlinkHevc", mode.name).commit()
                assertEquals(mode, store.load().carlinkHevc)
                store.save(store.load().copy(carlinkHevc = mode))
                assertEquals(mode, store.load().carlinkHevc)
            }
            context.getSharedPreferences("direlay", 0).edit().putString("carlinkHevc", "unknown").commit()
            assertEquals(org.openxplay.stream.clink.ClinkHevcMode.STREAM_ONLY, store.load().carlinkHevc)
        }
    }
    @Test fun carlinkTraceDefaultsOffAndPersists() {
        val store = RelaySettingsStore(context)
        val original = store.load()
        assertFalse(original.carlinkTrace)
        store.save(original.copy(carlinkTrace = true))
        assertTrue(store.load().carlinkTrace)
        store.save(original)
        assertFalse(store.load().carlinkTrace)
    }

    @Test fun iphoneChoiceDefaultsToAutomaticAndCanBeClearedWithoutChangingOtherSettings() {
        val store = RelaySettingsStore(context)
        val original = store.load()
        assertNull(original.iphoneAddress)
        val chosen = original.copy(iphoneAddress = "00:11:22:33:A9:30")
        store.save(chosen)
        assertEquals(chosen, RelaySettingsStore(context).load())
        store.save(chosen.copy(iphoneAddress = null))
        assertEquals(original, RelaySettingsStore(context).load())
    }

    @Test fun guidedSetupStaysOpenAfterTheGuidesOwnSavesButExistingInstallsCountAsSetUp() {
        val fresh = RelaySettingsStore(context)
        fresh.save(fresh.load().copy(carlinkOutput = true))
        assertFalse(RelaySettingsStore(context).setupCompleted)
        fresh.setupCompleted = true
        assertTrue(RelaySettingsStore(context).setupCompleted)

        clear()
        context.getSharedPreferences("direlay", 0).edit().putBoolean("carlinkOutput", false).commit()
        val existing = RelaySettingsStore(context)
        existing.load()
        assertTrue(existing.setupCompleted)
    }

    @Test fun legacyDisplayPreferencesMigrateOnceAndExplicitRelayScreenSurvives() {
        val preferences = context.getSharedPreferences("direlay", 0)
        assertEquals(DisplayMode.AUTOMATIC, store.load().displayMode)
        preferences.edit().remove("displayMode").putBoolean("customDisplay", false).commit()
        assertEquals(DisplayMode.AUTOMATIC, store.load().displayMode)
        for (profile in DisplayProfile.presets + DisplayProfile(1440, 540, 200)) {
            preferences.edit().remove("displayMode").putBoolean("customDisplay", true)
                .putInt("displayWidth", profile.width).putInt("displayHeight", profile.height)
                .putInt("displayDensity", profile.density).commit()
            val migrated = store.load()
            assertEquals(DisplayMode.FIXED, migrated.displayMode)
            assertEquals(profile, migrated.display)
            assertEquals("FIXED", preferences.getString("displayMode", null))
        }
        store.save(store.load().copy(displayMode = DisplayMode.RELAY_SCREEN, display = null))
        assertEquals(DisplayMode.RELAY_SCREEN, store.load().displayMode)
        store.save(store.load().copy(displayMode = DisplayMode.AUTOMATIC))
        assertEquals(DisplayMode.AUTOMATIC, store.load().displayMode)
        preferences.edit().remove("displayMode").putBoolean("customDisplay", true)
            .putInt("displayWidth", 801).commit()
        assertEquals(DisplayMode.AUTOMATIC, store.load().displayMode)
        assertNull(store.load().display)
    }

    @Test fun vehicleInformationIsOptInAndPersistsIndependentlyOfOutput() {
        val store = RelaySettingsStore(context)
        assertFalse(store.load().vehicleInformation)
        store.save(store.load().copy(carlinkOutput = true, vehicleInformation = true))
        assertTrue(RelaySettingsStore(context).load().vehicleInformation)
        store.save(store.load().copy(vehicleInformation = false))
        assertFalse(store.load().vehicleInformation)
        assertTrue(store.load().carlinkOutput)
    }
    @Test fun newInstallAutoConnectDefaultsOnAndExplicitOptOutSurvivesReload() {
        assertTrue(store.load().autoStartWhenReady)
        assertTrue(context.getSharedPreferences("diplay", 0).getBoolean("auto_connect", false))
        store.save(store.load().copy(autoStartWhenReady = false))
        assertFalse(RelaySettingsStore(context).load().autoStartWhenReady)
    }

    @Test fun developerModeAndBothOutputsPersistIndependentlyOfTheChosenOutput() {
        assertFalse(store.developerMode)
        store.developerMode = true
        for (carlink in listOf(false, true)) {
            store.save(store.load().copy(carlinkOutput = carlink, bothOutputs = true))
            assertTrue(RelaySettingsStore(context).load().bothOutputs)
            assertEquals(carlink, store.load().carlinkOutput)
            assertTrue(RelaySettingsStore(context).developerMode)
        }
        store.developerMode = false
        assertFalse(RelaySettingsStore(context).developerMode)
        assertTrue(store.load().bothOutputs)
    }

    @Test fun freshIdentityDefaultsAreSavedThroughAirPlayPersistence() {
        store.load()
        assertEquals("OpenXPlay", AirPlayPersistence.loadManufacturer(context))
        assertEquals("OpenXPlay Relay", AirPlayPersistence.loadModel(context))
        assertEquals("OpenXPlay", AirPlayPersistence.loadOemLabel(context))
        store.load()
        assertEquals("OpenXPlay", AirPlayPersistence.loadManufacturer(context))
    }

    @Test fun storedIdentityValuesArePreservedIndependently() {
        AirPlayPersistence.saveManufacturer(context, "My manufacturer")
        store.load()
        assertEquals("My manufacturer", AirPlayPersistence.loadManufacturer(context))
        assertEquals("OpenXPlay Relay", AirPlayPersistence.loadModel(context))
        context.getSharedPreferences("xcertplay_airplay", 0).edit().remove("manufacturer").commit()
        AirPlayPersistence.saveModel(context, "My model")
        store.load()
        assertEquals("OpenXPlay", AirPlayPersistence.loadManufacturer(context))
        assertEquals("My model", AirPlayPersistence.loadModel(context))
        AirPlayPersistence.saveManufacturer(context, "DiPlay")
        store.load()
        assertEquals("DiPlay", AirPlayPersistence.loadManufacturer(context))
        assertEquals("My model", AirPlayPersistence.loadModel(context))
    }

    @Test fun audioDefaultsAndBothMicrophoneSourcesPersist() {
        assertFalse(store.load().playCarPlayAudio)
        assertEquals(MicrophoneSource.HEAD_UNIT, store.load().microphoneSource)
        for (source in MicrophoneSource.entries) for (play in listOf(false, true)) {
            val changed = store.load().copy(playCarPlayAudio = play, microphoneSource = source)
            store.save(changed)
            assertEquals(changed, RelaySettingsStore(context).load())
        }
        context.getSharedPreferences("direlay", 0).edit().putString("microphoneSource", "future").commit()
        assertEquals(MicrophoneSource.HEAD_UNIT, store.load().microphoneSource)
    }

    @Test fun sharedValuesAreWrittenThroughAirPlayPersistenceAndReadBack() {
        for (target in MfiTarget.entries) {
            val settings = store.load().copy(
                mfiTarget = target, i2cPath = " /dev/i2c-3 ",
                remoteServer = " https://mfi.example.test ", remoteToken = "private-token",
                fps = 55, autoStartOnBoot = true,
            )
            store.save(settings)
            assertEquals(target, AirPlayPersistence.loadMfiTarget(context))
            assertEquals("/dev/i2c-3", AirPlayPersistence.loadMfiI2cPath(context))
            assertEquals("https://mfi.example.test", AirPlayPersistence.loadRemoteMfiServer(context))
            assertEquals("private-token", AirPlayPersistence.loadRemoteMfiToken(context))
            assertEquals(55, AirPlayPersistence.loadFps(context))
            assertTrue(AirPlayPersistence.loadAutoStartOnBoot(context))
            assertEquals(settings.copy(i2cPath = "/dev/i2c-3", remoteServer = "https://mfi.example.test"), store.load())
        }
    }

    @Test fun valuesChangedByDiPlayRemainVisibleInRelay() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.I2C)
        AirPlayPersistence.saveMfiI2cPath(context, "/dev/i2c-7")
        AirPlayPersistence.saveRemoteMfiServer(context, "http://mfi.example.test")
        AirPlayPersistence.saveRemoteMfiToken(context, "changed")
        AirPlayPersistence.saveFps(context, 42)
        AirPlayPersistence.saveAutoStartOnBoot(context, true)
        val loaded = store.load()
        assertEquals(MfiTarget.I2C, loaded.mfiTarget)
        assertEquals("/dev/i2c-7", loaded.i2cPath)
        assertEquals("http://mfi.example.test", loaded.remoteServer)
        assertEquals("changed", loaded.remoteToken)
        assertEquals(40, loaded.fps)
        assertTrue(loaded.autoStartOnBoot)
    }

    @Test fun defaultModesUseDiPlayKeysAndLastUsedRetainsBothTransports() {
        for (mode in ConnectionMode.entries) {
            for (lastUsed in listOf(false, true)) {
                AirPlayPersistence.saveWirelessEnabled(context, lastUsed)
                store.save(store.load().copy(connectionMode = mode,
                    wirelessNetwork = WirelessNetworkSettings(manualSsid = "Car hotspot")))
                assertEquals(mode.key, context.getSharedPreferences("diplay", 0).getString("default_connection_mode", null))
                assertEquals(mode, store.load().connectionMode)
                val expected = mode.wireless(lastUsed)
                assertEquals(expected, store.wireless())
                assertEquals(expected, AirPlayPersistence.loadWirelessEnabled(context))
                // A fixed default must still win if DiPlay subsequently records another transport.
                AirPlayPersistence.saveWirelessEnabled(context, !lastUsed)
                store.prepareStart()
                assertEquals(mode.wireless(!lastUsed), AirPlayPersistence.loadWirelessEnabled(context))
            }
        }
        context.getSharedPreferences("diplay", 0).edit().putString("default_connection_mode", "future").commit()
        assertEquals(ConnectionMode.LAST_USED, store.load().connectionMode)
    }

    @Test fun headUnitProfilesPersistExactPixelsAndDpiAndMapSupportedPhysicalSize() {
        for (profile in DisplayProfile.presets + DisplayProfile(1440, 540, 200)) {
            AirPlayPersistence.saveMaximumDetectedDisplay(context, 3000, 2000)
            store.save(store.load().copy(display = profile, displayMode = DisplayMode.FIXED))
            assertEquals(profile, store.load().display)
            assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(context))
            assertEquals(AirPlayPhysicalSizeBasis.WIDTH, AirPlayPersistence.loadPhysicalSizeBasis(context))
            assertEquals(0 to 0, AirPlayPersistence.loadMaximumDetectedDisplay(context))
            // The default 200 mm screen width, widened only for canvases wider than 1920 px.
            val expected = (kotlin.math.ceil(200 * maxOf(1.0, profile.width / 1920.0) / 25).toInt() * 25).coerceIn(100, 400)
            assertEquals(expected, AirPlayPersistence.loadWidthPhysicalMm(context))
        }
        store.save(store.load().copy(display = null, displayMode = DisplayMode.AUTOMATIC))
        assertNull(store.load().display)
    }

    @Test fun displayModeAndEffectiveCanvasResetDetectionWithoutAffectingUnrelatedSaves() {
        val fixed = store.load().copy(display = CarDisplayFit.default, displayMode = DisplayMode.FIXED)
        store.save(fixed)
        AirPlayPersistence.saveMaximumDetectedDisplay(context, 1920, 720)
        store.save(fixed.copy(autoStartWhenReady = !fixed.autoStartWhenReady))
        assertEquals(1920 to 720, AirPlayPersistence.loadMaximumDetectedDisplay(context))
        store.save(store.load().copy(displayMode = DisplayMode.AUTOMATIC, display = null))
        assertEquals(0 to 0, AirPlayPersistence.loadMaximumDetectedDisplay(context))
        AirPlayPersistence.saveMaximumDetectedDisplay(context, 3000, 2000)
        store.prepareDisplay(DisplayProfile(1920, 720, 240))
        assertEquals(0 to 0, AirPlayPersistence.loadMaximumDetectedDisplay(context))
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(context))
        assertEquals(DisplayMode.AUTOMATIC, store.load().displayMode)
        assertNull(store.load().display)
    }

    @Test fun relayOptionsAndAutoConnectReuseTheirExistingStores() {
        store.save(store.load().copy(localPreview = true, webPort = 9443, autoStartWhenReady = true))
        val relay = context.getSharedPreferences("direlay", 0)
        assertTrue(relay.getBoolean("localPreview", false))
        assertEquals(9443, relay.getInt("webPort", 0))
        assertTrue(context.getSharedPreferences("diplay", 0).getBoolean("auto_connect", false))
        assertTrue(store.load().autoStartWhenReady)
        assertTrue(store.load().localPreview)
        assertEquals(9443, store.load().webPort)
        store.save(store.load().copy(webPort = null, autoStartWhenReady = false, localPreview = false, autoStartOnBoot = false))
        assertNull(store.load().webPort)
        assertFalse(store.load().autoStartWhenReady)
        assertFalse(store.load().localPreview)
        assertFalse(AirPlayPersistence.loadAutoStartOnBoot(context))
    }

    @Test fun invalidInputsCannotPartiallyOverwriteSettings() {
        val original = store.load()
        val invalid = listOf(
            original.copy(displayMode = DisplayMode.FIXED, display = null),
            original.copy(webPort = 80), original.copy(webPort = 65536), original.copy(fps = 31),
            original.copy(display = DisplayProfile(801, 480, 160)),
            original.copy(display = DisplayProfile(1280, 720, 0)),
            original.copy(mfiTarget = MfiTarget.I2C, i2cPath = ""),
            original.copy(mfiTarget = MfiTarget.REMOTE, remoteServer = "file:///tmp/key"),
            original.copy(remoteToken = "bad\u0000token"),
        )
        for (settings in invalid) {
            assertTrue(runCatching { store.save(settings.copy(autoStartOnBoot = true)) }.isFailure)
            assertEquals(original, store.load())
        }
    }

    @Test fun startLeavesVideoCodecChoiceToRuntime() {
        AirPlayPersistence.saveHevcEnabled(context, true)
        AirPlayPersistence.saveFps(context, 50)
        store.prepareStart()
        assertTrue(AirPlayPersistence.loadHevcEnabled(context))
        assertEquals(50, AirPlayPersistence.loadFps(context))
    }

    @Test fun cachedSnapshotIsStableOnReadsAndPublishesSavesAndExternalChanges() {
        val observed = RelaySettingsStore(context)
        try {
            // Production initializes on the settings worker; reads below only inspect memory.
            val first = observed.awaitSnapshot()
            repeat(100) { assertSame(first, observed.snapshot.value) }
            val saved = first.copy(carlinkDeviceName = "My car", webPort = 9443, displayMode = DisplayMode.RELAY_SCREEN)
            observed.save(saved)
            assertEquals(saved, observed.snapshot.value)
            assertEquals(saved, observed.load())
            context.getSharedPreferences("direlay", 0).edit().putBoolean("vehicleInformation", true).commit()
            context.getSharedPreferences("diplay", 0).edit().putBoolean("auto_connect", false).commit()
            AirPlayPersistence.saveFps(context, 55)
            shadowOf(Looper.getMainLooper()).idle()
            val external = runBlocking {
                withTimeout(5_000) { observed.snapshot.filterNotNull().first {
                    it.vehicleInformation && !it.autoStartWhenReady && it.fps == 55
                } }
            }
            assertEquals(saved.copy(vehicleInformation = true, autoStartWhenReady = false, fps = 55), external)
            repeat(100) { assertSame(external, observed.snapshot.value) }
        } finally { observed.close() }
    }

    @Test fun storedBrowserPairingPreferenceIsIgnored() {
        val before = store.load()
        context.getSharedPreferences("direlay", android.content.Context.MODE_PRIVATE).edit().putBoolean("requirePairingCode", true).commit()
        assertEquals(before, RelaySettingsStore(context).load())
    }
}
