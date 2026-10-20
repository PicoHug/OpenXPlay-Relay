// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay

import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.openxplay.direlay.settings.StartupPermissions
import org.openxplay.direlay.admin.PairingVisibilityPolicy
import org.openxplay.direlay.admin.PairingWindow
import android.app.AlertDialog
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import org.openxplay.direlay.storage.MaterialStatus
import org.openxplay.direlay.identity.IdentityFiles
import org.openxplay.direlay.identity.IdentityKind
import org.openxplay.direlay.identity.IdentityFileException
import org.openxplay.direlay.identity.IdentityFileProblem
import org.openxplay.direlay.tls.TlsStore
import java.util.concurrent.Executors
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.CarPlayHostActivity
import org.openxplay.stream.core.SessionPhase

class RelayActivity : androidx.activity.ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val identityImport by lazy { androidx.lifecycle.ViewModelProvider(this)[IdentityImportState::class.java] }
    private var identityDialog: AlertDialog? = null
    private val imports = Executors.newSingleThreadExecutor()
    private val runtime get() = (application as DiRelayApplication).runtime
    private val settingsStore get() = runtime.settingsStore
    private var cachedWifi: String? = null
    private var cachedDeviceName: String? = null
    private var onWifi = false
    private var networkRefreshPending = false
    private var lastNetworkRefresh = Long.MIN_VALUE / 2
    private var identityReady = false
    private var materialChecked = false
    private var resumed = false
    private val starting get() = runtime.startRequest.pending
    private var requestingPermissions = false
    /** A pairing permission request or "make visible" dialog is on screen; its answer is still to come. */
    private var pairingInFlight = false
    private var bootAutoStart = false
    private var started = false
    /** The launch choice or the guided setup is on screen; CarPlay then starts only from their buttons. */
    private var guideOpen = false
    private val restartPending get() = runtime.sessionActions.restarting
    private val importOnly get() = intent.getBooleanExtra(EXTRA_IMPORT_IDENTITY, false) ||
        intent.getBooleanExtra(EXTRA_IMPORT_TLS, false)
    private var content by androidx.compose.runtime.mutableStateOf<org.openxplay.direlay.ui.HomeContent?>(null)
    private val refreshStatus = object : Runnable {
        override fun run() {
            refreshNetwork()
            updateReadiness()
            runPairingPrompt()
            showIdentityFollowUp()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (identityImport.uri == null) {
            identityImport.uri = savedInstanceState?.getString("identityUri")?.let(Uri::parse)
            identityImport.kind = savedInstanceState?.getString("identityKind")?.let(IdentityKind::valueOf) ?: IdentityKind.UNKNOWN
            identityImport.awaitingPicker = savedInstanceState?.getBoolean("identityPicker") ?: false
        }
        bootAutoStart = savedInstanceState?.getBoolean("bootAutoStart")
            ?: intent.getBooleanExtra(EXTRA_AUTO_START, false)
        requestingPermissions = savedInstanceState?.getBoolean("requestingPermissions") ?: false
        pairingInFlight = savedInstanceState?.getBoolean("pairingInFlight") ?: false
        // A fresh open with a saved setup asks whether to continue with it; a running relay skips the question.
        val offerLaunchChoice = savedInstanceState == null && runtime.status.session?.phase !in
            setOf(SessionPhase.CONNECTING, SessionPhase.ACTIVE, SessionPhase.RECONNECTING)
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            org.openxplay.direlay.ui.RelayTheme {
                content?.let { value -> org.openxplay.direlay.ui.RelayApp(value, settingsStore, runtime,
                    onStartStop = {
                        android.util.Log.i("OpenXPlay Relay", "Start/stop tapped while running=${value.presentation.running}")
                        bootAutoStart = false
                        started = false
                        work { runtime.sessionActions.action(if (value.presentation.running) "stop" else "start") }
                        updateReadiness()
                    },
                    onRestart = {
                        bootAutoStart = false
                        started = false
                        work { runtime.sessionActions.action("restart") }
                    },
                    onAutomatic = { enabled ->
                        imports.execute {
                            settingsStore.save(settingsStore.awaitSnapshot().copy(autoStartWhenReady = enabled))
                            handler.post { runtime.reloadSettings(); updateReadiness() }
                        }
                    },
                    onSetup = { action -> when (action) {
                        org.openxplay.direlay.ui.SetupAction.PERMISSIONS -> explainPermissions()
                        org.openxplay.direlay.ui.SetupAction.IDENTITY -> openDocument(IDENTITY_KEY, R.string.direlay_choose_key)
                        org.openxplay.direlay.ui.SetupAction.IPHONE -> openPairing()
                        org.openxplay.direlay.ui.SetupAction.SHARED_WIFI, org.openxplay.direlay.ui.SetupAction.SETTINGS, org.openxplay.direlay.ui.SetupAction.CHOOSE_IPHONE -> Unit
                    } },
                    onCopy = { text -> getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.ui_car_screen), text)) },
                    onReconnect = { if (missingPermissions().isNotEmpty()) explainPermissions() else runtime.reconnectCarlink() },
                    onNetwork = { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                    importMaterial = { identity -> openDocument(if (identity) IDENTITY_KEY else TLS_FILE, if (identity) R.string.direlay_choose_key else R.string.direlay_choose_tls) },
                    removeMaterial = { identity -> work { if (identity) runtime.identityStore.remove() else runtime.tlsStore.remove() } },
                    openSetup = !settingsStore.setupCompleted && !bootAutoStart && !importOnly,
                    openLaunch = offerLaunchChoice && !bootAutoStart && !importOnly,
                    onLater = {
                        if (settingsStore.snapshot.value?.autoStartWhenReady == true) runtime.autoStartSessions.pause("not now at launch")
                    },
                    onGuideVisible = { visible -> guideOpen = visible; if (!visible) updateReadiness() },
                ) }
            }
        }
        if (savedInstanceState == null) {
            when {
                intent.getBooleanExtra(EXTRA_IMPORT_IDENTITY, false) -> {
                    bootAutoStart = false
                    openDocument(IDENTITY_KEY, R.string.direlay_choose_key)
                }
                intent.getBooleanExtra(EXTRA_IMPORT_TLS, false) -> {
                    bootAutoStart = false
                    openDocument(TLS_FILE, R.string.direlay_choose_tls)
                }
            }
        }
    }

    private data class NetworkSnapshot(val wifi: String?, val deviceName: String?, val onWifi: Boolean)

    private fun refreshNetwork() {
        if (networkRefreshPending) return
        networkRefreshPending = true
        // Reading the Wi-Fi name counts as a location access on some systems: refresh it every 5 s,
        // while the session service check stays on every status tick.
        val now = android.os.SystemClock.elapsedRealtime()
        val networkDue = now - lastNetworkRefresh >= 5_000L
        if (networkDue) lastNetworkRefresh = now
        imports.execute {
            val network = if (!networkDue) null else {
                val wifi = org.openxplay.direlay.settings.currentWifiSsid(this)
                runtime.refreshIphoneBonds()
                val name = runtime.bondedDevices.localName()
                val connected = runCatching {
                    val manager = getSystemService(android.net.ConnectivityManager::class.java)
                    manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
                }.getOrDefault(false)
                NetworkSnapshot(wifi, name, connected)
            }
            runtime.autoStartSessions.refresh()
            val running = runtime.autoStartSessions.tracker.running
            handler.post {
                networkRefreshPending = false
                network?.let { cachedWifi = it.wifi; cachedDeviceName = it.deviceName; onWifi = it.onWifi }
                if (runtime.autoStartSessions.tracker.observed && !starting) started = running
                updateReadiness()
            }
        }
    }

    private fun missingPermissions(): List<String> {
        val settings = settingsStore.snapshot.value ?: return emptyList()
        return StartupPermissions.required(
            Build.VERSION.SDK_INT, settingsStore.wireless(settings), AirPlayPersistence.loadWirelessHotspotMode(this),
            AirPlayPersistence.loadLocationReportingEnabled(this), settings.carlinkOutput || settings.bothOutputs,
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun vpnConsent(): Intent? {
        val settings = settingsStore.snapshot.value ?: return null
        return if (settingsStore.wireless(settings)) null else VpnService.prepare(this)
    }

    private fun updateReadiness() {
        if (isDestroyed) return
        val settings = settingsStore.snapshot.value ?: return
        val wireless = settingsStore.wireless(settings)
        val missing = missingPermissions()
        val needsVpn = vpnConsent() != null
        val valid = runCatching { settings.validate(wireless) }.isSuccess &&
            (!(settings.carlinkOutput || settings.bothOutputs) || Build.VERSION.SDK_INT >= 29)
        val readyIdentity = settings.mfiTarget != MfiTarget.LOCAL || identityReady
        val phase = runtime.status.session?.phase
        val selection = runtime.iphoneSelection()
        val presentation = org.openxplay.direlay.ui.presentHome(org.openxplay.direlay.ui.HomeSnapshot(
            settings, wireless, missing.isEmpty() && !needsVpn, readyIdentity, !materialChecked, valid,
            phase, started || starting, runtime.status.failure != null ||
                (settings.carlinkOutput && runtime.carlinkFailed) ||
                ((!settings.carlinkOutput || settings.bothOutputs) && runtime.serverReason != null), selection, autoStartPaused = runtime.autoStartGate.paused,
            webReason = runtime.serverReason, webListening = runtime.serverState.startsWith("TLS enabled"),
            carNetworkAvailable = runtime.carIpv4.isNotEmpty(), currentWifi = cachedWifi, sharedWifiReady = !wireless || settings.wirelessNetwork.choice != org.openxplay.direlay.settings.WirelessNetworkChoice.EXISTING_WIFI || runtime.currentSharedWifi(settings, cachedWifi) != null))
        val carAddresses = runtime.carIpv4
        val urls = runtime.receiverUrls.filterNot { it.contains("://localhost:") }
            .filter { url -> carAddresses.any { url.contains("://$it:") } }
        val ip = carAddresses.joinToString("\n")
            .ifEmpty { getString(R.string.ui_no_network) }
        // Without location access Android hides the network name, but the connection itself is known.
        val wifi = cachedWifi ?: getString(if (onWifi) R.string.ui_wifi_connected else R.string.ui_wifi_not_connected)
        val deviceName = cachedDeviceName ?: settings.carlinkDeviceName
        content = org.openxplay.direlay.ui.HomeContent(presentation, urls,
            ip, wifi, deviceName, settings.autoStartWhenReady, settings.carlinkOutput, settings.bothOutputs,
            when {
                runtime.carlinkPins.pending != null -> R.string.ui_waiting_code
                runtime.carlinkState in setOf("ACTIVE", "CONNECTED", "STREAMING", "PLAYING") -> R.string.ui_connected
                runtime.carlinkState == "Requires Android 10+" -> R.string.ui_carlink_unsupported
                runtime.carlinkFailed || runtime.carlinkState == "ENDED" -> R.string.ui_carlink_failed
                else -> R.string.ui_searching
            }, runtime.carlinkPins.pending.takeIf { resumed }, starting || requestingPermissions, onWifi = onWifi, vehicle = runtime.vehicleView,
            pinRejected = runtime.pinRejected, pairingSuccess = runtime.pairingSuccess,
            displayMismatch = runtime.displayMismatch(presentation.running, settings), currentWifi = cachedWifi,
            iphoneName = runtime.iphoneBonds.firstOrNull { it.address.equals(selection.address, ignoreCase = true) }?.name)
        if (!importOnly && !guideOpen && presentation.canStart && resumed && !requestingPermissions && !networkRefreshPending && !restartPending && !starting && !presentation.running && runtime.status.failure == null && !runtime.autoStartGate.paused &&
            runtime.autoStartSessions.tracker.observed && !runtime.autoStartSessions.tracker.running &&
            phase !in setOf(SessionPhase.CONNECTING, SessionPhase.ACTIVE, SessionPhase.RECONNECTING) &&
            (settings.autoStartWhenReady || bootAutoStart)) startCarPlay()
    }

    private fun permissionName(permission: String): String = getString(when (permission) {
        Manifest.permission.RECORD_AUDIO -> R.string.direlay_permission_microphone
        Manifest.permission.POST_NOTIFICATIONS -> R.string.direlay_permission_notifications
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT -> R.string.direlay_permission_bluetooth
        Manifest.permission.NEARBY_WIFI_DEVICES -> R.string.direlay_permission_wifi
        Manifest.permission.ACCESS_FINE_LOCATION -> R.string.direlay_permission_precise_location
        Manifest.permission.ACCESS_COARSE_LOCATION -> R.string.direlay_permission_location
        else -> R.string.direlay_permission_network
    })

    private fun explainPermissions() {
        val missing = missingPermissions()
        AlertDialog.Builder(this).setTitle(R.string.direlay_permissions_title)
            .setMessage(getString(R.string.direlay_permissions_explanation) + "\n\n" +
                missing.joinToString("\n") { permissionName(it) } +
                if (vpnConsent() != null) "\n" + getString(R.string.direlay_vpn_required) else "")
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.direlay_android_permissions) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setPositiveButton(R.string.direlay_continue) { _, _ ->
                requestingPermissions = true
                updateReadiness()
                if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), PERMISSIONS)
                else requestVpnConsent()
            }.show()
    }

    private fun requestVpnConsent() {
        val consent = vpnConsent()
        if (missingPermissions().isEmpty() && consent != null) startActivityForResult(consent, VPN_CONSENT)
        else { requestingPermissions = false; updateReadiness() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS) {
            runtime.reloadSettings()
            requestVpnConsent()
        }
        if (requestCode == PAIRING_PERMISSION) {
            pairingInFlight = false
            // An interrupted request answers with empty arrays; treat it as declined.
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            showPairing(runtime.pairing.permissionResult(granted))
        }
    }

    /** The app's Pair action opens the same pairing window as the admin page; Bluetooth settings stay the fallback. */
    private fun openPairing() {
        val opened = runtime.pairing.open().error == null
        if (!opened || (!runPairingPrompt() && !pairingInFlight)) openBluetoothSettings()
    }

    /** Shows the prompt the pairing window holds for this screen, asked from here or the admin page. */
    private fun runPairingPrompt(): Boolean {
        if (!resumed || requestingPermissions || pairingInFlight || importOnly) return false
        showPairing(runtime.pairing.take() ?: return false)
        return true
    }

    private fun showPairing(prompt: PairingWindow.Prompt) {
        when (prompt) {
            PairingWindow.Prompt.PERMISSION -> {
                // Asked only now, never at startup: the relay works without Nearby devices.
                val missing = PairingVisibilityPolicy.missing(Build.VERSION.SDK_INT) {
                    checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) showPairing(runtime.pairing.permissionResult(true))
                else { pairingInFlight = true; requestPermissions(missing.toTypedArray(), PAIRING_PERMISSION) }
            }
            PairingWindow.Prompt.DIALOG -> try {
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                    .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, PairingVisibilityPolicy.dialogSeconds()), PAIRING_DIALOG)
                pairingInFlight = true
            } catch (_: ActivityNotFoundException) { showPairing(runtime.pairing.dialogFailed(permission = false)) }
            catch (_: SecurityException) { showPairing(runtime.pairing.dialogFailed(permission = true)) }
            PairingWindow.Prompt.SETTINGS -> if (!openBluetoothSettings()) runtime.pairing.settingsFailed()
        }
    }

    private fun openBluetoothSettings(): Boolean =
        try { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); true } catch (_: ActivityNotFoundException) { false }

    private fun startCarPlay() {
        if (starting || !resumed) return
        work {
            val error = runtime.sessionActions.start()
            handler.post {
                started = error == null
                updateReadiness()
            }
        }
    }

    private fun openDocument(request: Int, title: Int) {
        if ((request == IDENTITY_KEY || request == IDENTITY_CERTIFICATE) &&
            (identityImport.busy || identityImport.awaitingPicker)) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_TITLE, getString(title))
            if (request == IDENTITY_KEY || request == IDENTITY_CERTIFICATE) {
                if (request == IDENTITY_KEY) identityImport.uri = null
                identityImport.awaitingPicker = true
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }, request)
    }

    private fun read(uri: Uri, limit: Int): ByteArray =
        (contentResolver.openInputStream(uri) ?: error(getString(R.string.direlay_file_unreadable))).use { input ->
            val buffer = ByteArray(limit + 1)
            try {
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                require(size <= limit) { getString(R.string.direlay_file_too_large, limit) }
                buffer.copyOf(size)
            } finally { buffer.fill(0) }
        }

    private fun importIdentity(uris: List<Uri>) {
        val state = identityImport
        val pending = state.uri
        state.awaitingPicker = false
        state.busy = true
        // Only application-owned objects and the retained state are used by the worker after rotation.
        val resolver = applicationContext.contentResolver
        val store = runtime.identityStore
        imports.execute {
            val selected = mutableListOf<IdentityFiles>()
            try {
                if (uris.isEmpty() || uris.size + (if (pending != null) 1 else 0) > 2)
                    throw IdentityFileException(IdentityFileProblem.SELECTION)
                for (uri in listOfNotNull(pending) + uris) {
                    selected.add(IdentityFiles.read(resolver.openInputStream(uri) ?: error("Unreadable identity file")))
                }
                val missing = IdentityFiles.select(selected, followUp = pending != null, store::importFrom)
                if (missing != null) {
                    state.uri = uris.single()
                    state.kind = missing
                } else {
                    state.uri = null
                    state.completed = true
                }
            } catch (failure: Exception) {
                state.uri = null
                state.problem = (failure as? IdentityFileException)?.problem ?: IdentityFileProblem.INVALID_PAIR
            } finally { selected.forEach { it.close() }; state.busy = false }
        }
    }

    private fun showIdentityFollowUp() {
        val state = identityImport
        if (state.busy || identityDialog != null || !resumed) return
        if (state.completed) {
            state.completed = false
            refreshMaterialStatus()
            if (importOnly) finish()
            return
        }
        val problem = state.problem
        if (problem != null) {
            state.problem = null
            val message = when (problem) {
                IdentityFileProblem.SELECTION -> R.string.identity_error_selection
                IdentityFileProblem.SAME_KIND -> R.string.identity_error_same_kind
                IdentityFileProblem.INVALID_PAIR -> R.string.identity_error_pair
                IdentityFileProblem.EMPTY_ARCHIVE -> R.string.identity_error_missing
                IdentityFileProblem.DUPLICATE_KEY -> R.string.identity_error_duplicate_key
                IdentityFileProblem.DUPLICATE_CERTIFICATE -> R.string.identity_error_duplicate_certificate
                IdentityFileProblem.ENCRYPTED -> R.string.identity_error_encrypted
                IdentityFileProblem.TOO_LARGE -> R.string.identity_error_size
                IdentityFileProblem.ARCHIVE_LIMIT -> R.string.identity_error_limit
                IdentityFileProblem.BAD_ARCHIVE -> R.string.identity_error_zip
            }
            identityDialog = AlertDialog.Builder(this).setTitle(R.string.direlay_import_error).setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> if (importOnly) finish() }
                .setOnCancelListener { if (importOnly) finish() }.create()
        } else if (state.uri != null && !state.awaitingPicker) {
            val message = when (state.kind) {
                IdentityKind.KEY -> R.string.identity_follow_key
                IdentityKind.CERTIFICATE -> R.string.identity_follow_certificate
                IdentityKind.UNKNOWN -> R.string.identity_follow_unknown
            }
            fun cancel() { state.uri = null; if (importOnly) finish() }
            identityDialog = AlertDialog.Builder(this).setTitle(R.string.direlay_identity).setMessage(message)
                .setPositiveButton(R.string.identity_choose_other) { _, _ -> openDocument(IDENTITY_CERTIFICATE, R.string.identity_choose_other) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancel() }
                .setOnCancelListener { cancel() }.create()
        }
        identityDialog?.apply { setOnDismissListener { identityDialog = null }; show() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PAIRING_DIALOG) {
            pairingInFlight = false
            runtime.pairing.dialogResult(PairingVisibilityPolicy.visibleSeconds(resultCode, PairingVisibilityPolicy.dialogSeconds()))
            return
        }
        if (requestCode == VPN_CONSENT) {
            requestingPermissions = false
            updateReadiness()
            return
        }
        if (resultCode != RESULT_OK) { identityImport.uri = null; identityImport.awaitingPicker = false; if (importOnly) finish(); return }
        if (requestCode == IDENTITY_KEY || requestCode == IDENTITY_CERTIFICATE) {
            val uris = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
                ?: listOfNotNull(data?.data)
            importIdentity(uris)
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            TLS_FILE -> {
                val password = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setHint(R.string.direlay_password)
                    filters = arrayOf(android.text.InputFilter.LengthFilter(TlsStore.MAX_PASSWORD_CHARS))
                }
                AlertDialog.Builder(this).setTitle(R.string.direlay_tls_password).setView(password)
                    .setOnCancelListener { password.text.clear(); if (importOnly) finish() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> password.text.clear(); if (importOnly) finish() }
                    .setPositiveButton(R.string.direlay_import) { _, _ ->
                        val secret = CharArray(password.length()) { password.text[it] }
                        password.text.clear()
                        work(finishAfter = importOnly) {
                            var bytes: ByteArray? = null
                            try {
                                bytes = read(uri, TlsStore.MAX_FILE_BYTES)
                                runtime.tlsStore.importFrom(bytes, secret)
                            } finally { secret.fill('\u0000'); bytes?.fill(0) }
                        }
                    }.show()
            }
        }
    }

    private fun work(finishAfter: Boolean = false, startingSession: Boolean = false, action: () -> Unit) {
        imports.execute {
            try { action(); if (finishAfter) handler.post { if (!isDestroyed) finish() } }
            catch (failure: Exception) {
                handler.post {
                    val wasStarting = startingSession
                    if (startingSession) { runtime.status.inputFailed(failure); runtime.startRequest.finish() }
                    updateReadiness()
                    if (!isDestroyed) AlertDialog.Builder(this)
                        .setTitle(R.string.direlay_import_error)
                        .setMessage(getString(if (wasStarting) R.string.ui_start_failed else R.string.ui_material_failed))
                        .setPositiveButton(android.R.string.ok, null).show()
                }
            }
            updateMaterialStatus()
        }
    }

    private fun refreshMaterialStatus() {
        try { imports.execute { updateMaterialStatus() } }
        catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    private fun updateMaterialStatus() {
        val identityValue = runtime.identityStore.status()
        handler.post {
            if (!isDestroyed) {
                identityReady = identityValue == MaterialStatus.Installed
                materialChecked = true
                updateReadiness()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        identityImport.uri?.let { outState.putString("identityUri", it.toString()) }
        outState.putString("identityKind", identityImport.kind.name)
        outState.putBoolean("identityPicker", identityImport.awaitingPicker)
        outState.putBoolean("bootAutoStart", bootAutoStart)
        outState.putBoolean("requestingPermissions", requestingPermissions)
        outState.putBoolean("pairingInFlight", pairingInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        imports.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_IMPORT_IDENTITY = "org.openxplay.relay.IMPORT_IDENTITY"
        const val EXTRA_IMPORT_TLS = "org.openxplay.relay.IMPORT_TLS"
        const val EXTRA_AUTO_START = "org.openxplay.relay.AUTO_START"
        private const val PERMISSIONS = 103
        private const val VPN_CONSENT = 104
        private const val PAIRING_PERMISSION = 105
        private const val PAIRING_DIALOG = 106
        private const val IDENTITY_KEY = 100
        private const val IDENTITY_CERTIFICATE = 101
        private const val TLS_FILE = 102
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        runtime.reloadSettings()
        // Coming back from system Wi-Fi or Bluetooth settings: show the new network at once.
        lastNetworkRefresh = Long.MIN_VALUE / 2
        materialChecked = false
        refreshNetwork()
        updateReadiness()
        handler.post(refreshStatus)
        refreshMaterialStatus()
    }

    override fun onPause() {
        resumed = false
        content = content?.copy(pending = null)
        handler.removeCallbacks(refreshStatus)
        super.onPause()
    }
}

/** Retains URI references and worker outcomes, never secret bytes, across activity recreation. */
class IdentityImportState : androidx.lifecycle.ViewModel() {
    @Volatile var uri: Uri? = null
    @Volatile var kind = IdentityKind.UNKNOWN
    @Volatile var awaitingPicker = false
    @Volatile var busy = false
    @Volatile var completed = false
    @Volatile var problem: IdentityFileProblem? = null
}
