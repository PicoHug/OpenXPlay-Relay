// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.settings

import android.content.Context
import org.openxplay.stream.clink.ClinkHevcMode
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

// DiPlay's default-mode API is internal; keep its existing preference contract here.
enum class ConnectionMode(val key: String) {
    USB("usb"), WIRELESS("wireless"), LAST_USED("last_used");
    fun wireless(lastUsed: Boolean): Boolean = when (this) {
        USB -> false
        WIRELESS -> true
        LAST_USED -> lastUsed
    }
}

data class DisplayProfile(val width: Int, val height: Int, val density: Int) {
    fun validate() {
        require(width in 320..3840 && height in 240..2160 && width % 2 == 0 && height % 2 == 0) {
            "Use even dimensions: width 320–3840 px and height 240–2160 px."
        }
        require(density in 120..640) { "Density must be 120–640 dpi." }
    }
    companion object {
        val presets = listOf(
            DisplayProfile(800, 480, 160), DisplayProfile(1024, 600, 160),
            DisplayProfile(1280, 720, 160), DisplayProfile(1920, 720, 240),
            DisplayProfile(1920, 1080, 240),
        )
    }
}

enum class VideoCodecSetting(val wire: String) { AUTO("auto"), H265("h265"), H264("h264") }

enum class MicrophoneSource { HEAD_UNIT, THIS_DEVICE }

data class RelaySettings(
    val connectionMode: ConnectionMode,
    val mfiTarget: MfiTarget,
    val i2cPath: String,
    val remoteServer: String,
    val remoteToken: String,
    val display: DisplayProfile?,
    val fps: Int,
    val autoStartOnBoot: Boolean,
    val autoStartWhenReady: Boolean,
    val localPreview: Boolean,
    val webPort: Int?,
    val carlinkOutput: Boolean = false,
    val carlinkDeviceName: String = "OpenXPlay",
    val carlinkDeviceModel: String = "Android relay",
    val wirelessNetwork: WirelessNetworkSettings = WirelessNetworkSettings(),
    val playCarPlayAudio: Boolean = false,
    val microphoneSource: MicrophoneSource = MicrophoneSource.HEAD_UNIT,
    val bothOutputs: Boolean = false,
    val vehicleInformation: Boolean = false,
    val carlinkAutoReconnect: Boolean = true,
    val displayMode: DisplayMode = if (display == null) DisplayMode.AUTOMATIC else DisplayMode.FIXED,
    val iphoneAddress: String? = null,
    val plugins: Boolean = false,
    val pluginsEnabled: List<String> = emptyList(),
    val carlinkTrace: Boolean = false,
    val carlinkHevc: ClinkHevcMode = ClinkHevcMode.STREAM_ONLY,
    val carplayResolution: CarPlayResolution = CarPlayResolution.AUTO,
    val carplayScreenWidth: CarPlayScreenWidth = CarPlayScreenWidth.MM200,
    val viewAreas: Boolean = true,
    val car: CarSelection = CarSelection(),
    val videoCodec: VideoCodecSetting = VideoCodecSetting.AUTO,
) {
    fun validate(wireless: Boolean = connectionMode == ConnectionMode.WIRELESS, allowEmptySharedWifi: Boolean = false) {
        if (wireless) wirelessNetwork.validate(allowEmptySharedWifi, allowIncompleteHotspot = allowEmptySharedWifi && CarlinkConnectionPolicy.enabled(this))
        for (value in listOf(carlinkDeviceName, carlinkDeviceModel)) {
            require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 128 &&
                value.none { Character.isISOControl(it) }) { "Carlink name and model must contain 1–128 UTF-8 bytes without control characters." }
        }
        require(org.openxplay.direlay.plugins.RelayPlugins.validIds(pluginsEnabled)) { "error.plugins" }
        display?.validate()
        require(displayMode != DisplayMode.FIXED || display != null) { "Choose a fixed display profile." }
        require(fps in 30..60 && fps % 5 == 0) { "FPS must be 30–60 in steps of 5." }
        require(webPort == null || webPort in 1024..65535) { "Web port must be 1024–65535, or empty for automatic." }
        require('\u0000' !in i2cPath && '\u0000' !in remoteServer && '\u0000' !in remoteToken) {
            "MFi settings must not contain null characters."
        }
        if (mfiTarget == MfiTarget.I2C) require(i2cPath.isNotBlank()) { "Enter the Linux I2C device path." }
        if (mfiTarget == MfiTarget.REMOTE) {
            val uri = java.net.URI(remoteServer.trim())
            require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()) {
                "Enter an http:// or https:// remote MFi server address."
            }
        }
    }
}

fun RelaySettings.displaySettings() = org.openxplay.stream.web.DisplaySettings(carplayResolution.wire, carplayScreenWidth.mm, videoCodec.wire)

/** Maps relay controls to DiPlay's persistence; only relay-specific values live in direlay. */
class RelaySettingsStore(private val context: Context) : AutoCloseable, org.openxplay.stream.web.DisplaySettingsPort {
    private val displayListeners = java.util.concurrent.CopyOnWriteArrayList<(org.openxplay.stream.web.DisplaySettings) -> Unit>()
    override fun current() = cached.value?.displaySettings() ?: org.openxplay.stream.web.DisplaySettings()
    @Synchronized override fun set(settings: org.openxplay.stream.web.DisplaySettings, origin: org.openxplay.stream.core.Origin) {
        save((cached.value ?: load()).copy(
            videoCodec = VideoCodecSetting.entries.first { it.wire == settings.videoCodec },
            carplayResolution = CarPlayResolution.entries.first { it.wire == settings.resolution },
            carplayScreenWidth = CarPlayScreenWidth.entries.first { it.mm == settings.screenWidth }))
    }
    override fun listen(callback: (org.openxplay.stream.web.DisplaySettings) -> Unit): AutoCloseable {
        displayListeners.add(callback)
        return AutoCloseable { displayListeners.remove(callback) }
    }
    private fun publish(value: RelaySettings) {
        val previous = cached.value?.displaySettings()
        cached.value = value
        if (previous != value.displaySettings()) displayListeners.forEach { it(value.displaySettings()) }
    }
    private val relay get() = context.getSharedPreferences("direlay", Context.MODE_PRIVATE)
    private val diplay get() = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)

    private val cached = kotlinx.coroutines.flow.MutableStateFlow<RelaySettings?>(null)
    val snapshot: kotlinx.coroutines.flow.StateFlow<RelaySettings?> = cached
    private val refreshWorker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "direlay-settings").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private val refreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private var initialRead: java.util.concurrent.Future<*>? = null
    // Keep a strong reference: SharedPreferences holds listeners weakly.
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!closed && refreshQueued.compareAndSet(false, true)) try { refreshWorker.execute {
            refreshQueued.set(false)
            synchronized(this) { if (!closed) publish(load()) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { refreshQueued.set(false) }
    }
    @Synchronized fun startObserving() {
        check(!closed)
        if (initialRead != null) return
        listOf(relay, diplay, context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE))
            .forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        initialRead = refreshWorker.submit { synchronized(this) { publish(load()) } }
    }
    /** Only workers may wait for the first disk-backed preference snapshot. */
    fun awaitSnapshot(): RelaySettings {
        startObserving()
        initialRead!!.get()
        return checkNotNull(cached.value)
    }

    override fun close() {
        closed = true
        listOf(relay, diplay, context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE))
            .forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        displayListeners.clear()
        refreshWorker.shutdownNow()
    }

    fun load(): RelaySettings {
        // Check each private preference key independently; keep every stored user value.
        if (!diplay.contains("auto_connect")) diplay.edit().putBoolean("auto_connect", true).apply()
        val identity = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        AccessoryIdentityDefaults.apply(context, relay)
        // Settings saved before the guided setup existed mean the relay is already set up. Decided once, before
        // the guide's own saves could look the same.
        if (!relay.contains("setupCompleted")) relay.edit().putBoolean("setupCompleted", relay.contains("carlinkOutput")).apply()
        // Migrate an existing choice once, before installing our safe default into DiPlay's store.
        // Keep the user-choice marker unset until a choice is actually saved.
        if (!relay.getBoolean("wirelessNetworkInitialized", false)) {
            val stored = identity.contains("wireless_hotspot_mode")
            val edit = relay.edit().putBoolean("wirelessNetworkInitialized", true)
            if (stored) edit.putBoolean("wirelessNetworkChosen", true)
            edit.apply()
        }
        if (!relay.getBoolean("wirelessNetworkChosen", false) &&
            identity.getString("wireless_hotspot_mode", null) != WirelessHotspotMode.WIFI_P2P.name)
            AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        val profile = if (relay.getBoolean("customDisplay", false)) {
            DisplayProfile(relay.getInt("displayWidth", 1280), relay.getInt("displayHeight", 720),
                relay.getInt("displayDensity", 160)).takeIf { runCatching { it.validate() }.isSuccess }
        } else null
        val mode = migratedDisplayMode(relay.getString("displayMode", null), profile)
        if (relay.getString("displayMode", null) != mode.name) relay.edit().putString("displayMode", mode.name).apply()
        return RelaySettings(
            ConnectionMode.entries.firstOrNull { it.key == diplay.getString("default_connection_mode", null) }
                ?: ConnectionMode.LAST_USED,
            AirPlayPersistence.loadMfiTarget(context), AirPlayPersistence.loadMfiI2cPath(context),
            AirPlayPersistence.loadRemoteMfiServer(context), AirPlayPersistence.loadRemoteMfiToken(context),
            profile, AirPlayPersistence.loadFps(context), AirPlayPersistence.loadAutoStartOnBoot(context),
            diplay.getBoolean("auto_connect", true), relay.getBoolean("localPreview", false),
            relay.getInt("webPort", 0).takeIf { it in 1024..65535 },
            relay.getBoolean("carlinkOutput", false),
            relay.getString("carlinkDeviceName", "OpenXPlay") ?: "OpenXPlay",
            relay.getString("carlinkDeviceModel", "Android relay") ?: "Android relay",
            WirelessNetworkSettings.load(context),
            relay.getBoolean("playCarPlayAudio", false),
            MicrophoneSource.entries.firstOrNull { it.name == relay.getString("microphoneSource", null) }
                ?: MicrophoneSource.HEAD_UNIT,
            relay.getBoolean("bothOutputs", false),
            relay.getBoolean("vehicleInformation", false),
            carlinkAutoReconnect = relay.getBoolean("carlinkAutoReconnect", true),
            mode,
            relay.getString("iphoneAddress", null),
            carlinkTrace = relay.getBoolean("carlinkTrace", false),
            carlinkHevc = ClinkHevcMode.entries.firstOrNull { it.name == relay.getString("carlinkHevc", null) } ?: ClinkHevcMode.STREAM_ONLY,
            viewAreas = relay.getBoolean("carPlayViewAreas", true),
            car = CarSelection(relay.getString("carBrand", "").orEmpty(), relay.getString("carBrandText", "").orEmpty(),
                relay.getString("carModel", "").orEmpty(), relay.getString("carModelText", "").orEmpty()).normalized(),
            videoCodec = VideoCodecSetting.entries.firstOrNull { it.name == relay.getString("videoCodec", null) } ?: VideoCodecSetting.AUTO,
            carplayResolution = CarPlayResolution.entries.firstOrNull { it.name == relay.getString("carplayResolution", null) } ?: CarPlayResolution.AUTO,
            carplayScreenWidth = CarPlayScreenWidth.entries.firstOrNull { it.mm == relay.getInt("carplayScreenWidth", 200) } ?: CarPlayScreenWidth.MM200,
            plugins = relay.getBoolean("plugins", false),
            pluginsEnabled = runCatching {
                val ids = org.json.JSONArray(relay.getString("pluginsEnabled", "[]"))
                (0 until ids.length()).map { ids.getString(it) }.also { require(org.openxplay.direlay.plugins.RelayPlugins.validIds(it)) }
            }.getOrDefault(emptyList()),
        )
    }

    @Synchronized fun save(value: RelaySettings) {
        val settings = value.copy(car = value.car.normalized())
        val current = if (initialRead == null) load() else cached.value ?: load()
        // Validate wireless fields when they change or wireless is newly selected; untouched defaults
        // must not block unrelated settings.
        val wirelessChanged = settings.wirelessNetwork != current.wirelessNetwork ||
            settings.connectionMode != current.connectionMode
        settings.validate(wireless(settings) && wirelessChanged, allowEmptySharedWifi = true)
        val previousDisplay = current.display
        diplay.edit().putString("default_connection_mode", settings.connectionMode.key)
            .putBoolean("auto_connect", settings.autoStartWhenReady).apply()
        // LAST_USED must retain the previous transport rather than silently choosing a new one.
        if (settings.connectionMode != ConnectionMode.LAST_USED) {
            AirPlayPersistence.saveWirelessEnabled(context, settings.connectionMode == ConnectionMode.WIRELESS)
        }
        settings.wirelessNetwork.save(context)
        AirPlayPersistence.saveMfiTarget(context, settings.mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(context, settings.i2cPath)
        AirPlayPersistence.saveRemoteMfiServer(context, settings.remoteServer.trim())
        AirPlayPersistence.saveRemoteMfiToken(context, settings.remoteToken)
        if (settings.display != previousDisplay || settings.displayMode != current.displayMode) {
            AirPlayPersistence.saveMaximumDetectedDisplay(context, 0, 0)
        }
        if (settings.displayMode == DisplayMode.FIXED) settings.display?.let { applyDisplayProfile(it, settings.carplayScreenWidth) }
        AirPlayPersistence.saveFps(context, settings.fps)
        AirPlayPersistence.saveAutoStartOnBoot(context, settings.autoStartOnBoot)
        val edit = relay.edit().putBoolean("localPreview", settings.localPreview)
            .putBoolean("playCarPlayAudio", settings.playCarPlayAudio)
            .putString("microphoneSource", settings.microphoneSource.name)
            .putBoolean("carlinkOutput", settings.carlinkOutput)
            .putBoolean("bothOutputs", settings.bothOutputs)
            .putBoolean("vehicleInformation", settings.vehicleInformation)
            .putBoolean("carlinkAutoReconnect", settings.carlinkAutoReconnect)
            .putBoolean("carlinkTrace", settings.carlinkTrace)
            .putString("carlinkHevc", settings.carlinkHevc.name)
            .putBoolean("carPlayViewAreas", settings.viewAreas)
            .putString("carBrand", settings.car.brand).putString("carBrandText", settings.car.brandText)
            .putString("carModel", settings.car.model).putString("carModelText", settings.car.modelText)
            .putString("videoCodec", settings.videoCodec.name)
            .putString("carplayResolution", settings.carplayResolution.name)
            .putInt("carplayScreenWidth", settings.carplayScreenWidth.mm)
            .putBoolean("plugins", settings.plugins)
            .putString("pluginsEnabled", org.json.JSONArray(settings.pluginsEnabled).toString())
            .putString("iphoneAddress", settings.iphoneAddress)
            .putString("displayMode", settings.displayMode.name)
            .putString("carlinkDeviceName", settings.carlinkDeviceName)
            .putString("carlinkDeviceModel", settings.carlinkDeviceModel)
            .putInt("webPort", settings.webPort ?: 0).putBoolean("customDisplay", settings.display != null)
        settings.display?.let {
            edit.putInt("displayWidth", it.width).putInt("displayHeight", it.height).putInt("displayDensity", it.density)
        }
        edit.apply()
        publish(load())
    }

    fun prepareDisplay(profile: DisplayProfile?, screenWidth: CarPlayScreenWidth = CarPlayScreenWidth.MM200) {
        profile?.validate()
        AirPlayPersistence.saveMaximumDetectedDisplay(context, 0, 0)
        profile?.let { applyDisplayProfile(it, screenWidth) }
    }

    private fun applyDisplayProfile(profile: DisplayProfile, screenWidth: CarPlayScreenWidth) {
        AirPlayPersistence.saveDisplayScalePercent(context, 100)
        // DiPlay reports physical millimeters, with its existing 100–400 mm / 25 mm steps.
        AirPlayPersistence.saveWidthPhysicalMm(context, physicalWidthOverrideMm.takeIf { it > 0 } ?: physicalWidth(profile, screenWidth))
        AirPlayPersistence.savePhysicalSizeBasis(context, com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis.WIDTH)
    }

    var developerMode: Boolean
        get() = relay.getBoolean("developerMode", false)
        set(value) { relay.edit().putBoolean("developerMode", value).apply() }

    /** Developer calibration: the physical width in mm declared instead of the selected preset, or 0 to use the preset. */
    var physicalWidthOverrideMm: Int
        get() = relay.getInt("devPhysicalWidthMm", 0)
        set(value) { relay.edit().putInt("devPhysicalWidthMm", value).apply() }

    /** Whether the guided setup has been finished; [load] seeds it once for installs that predate the guide. */
    var setupCompleted: Boolean
        get() = relay.getBoolean("setupCompleted", false)
        set(value) { relay.edit().putBoolean("setupCompleted", value).apply() }

    fun wireless(settings: RelaySettings = cached.value ?: load()): Boolean =
        settings.connectionMode.wireless(AirPlayPersistence.loadWirelessEnabled(context))

    /** Whether CarPlay's next start would put it on Wi-Fi Direct beside Carlink. Start validation and the
     *  Carlink block both use it, so DiPlay's stale wireless flag cannot block a USB user. */
    fun carlinkWifiDirectConflict(settings: RelaySettings = cached.value ?: load()): Boolean =
        CarlinkConnectionPolicy.conflicts(CarlinkConnectionPolicy.enabled(settings), wireless(settings),
            AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.WIFI_P2P)

    fun prepareStart() {
        AirPlayPersistence.saveWirelessEnabled(context, wireless())
    }
}
