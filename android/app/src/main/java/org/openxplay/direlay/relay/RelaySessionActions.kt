// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
package org.openxplay.direlay.relay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.DiPlaySessionService
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.openxplay.direlay.settings.StartupPermissions
import org.openxplay.direlay.settings.CarlinkConnectionPolicy
import org.openxplay.direlay.storage.MaterialStatus
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface RelayActions {
    val restarting: Boolean
    fun action(name: String): String?
    fun start(manual: Boolean = false): String?
}
/** Shared command path for local buttons and browser actions; Android consent remains local. */
class RelaySessionActions(private val context: Context, private val runtime: RelayRuntime) : RelayActions, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "direlay-session-actions").apply { isDaemon = true } }
    @Volatile override var restarting = false
        private set
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    @Synchronized override fun action(name: String): String? = when (name) {
        "start" -> start(manual = true)
        "stop" -> {
            restarting = false
            generation++
            runtime.autoStartSessions.pause("Stop requested")
            stop(); null
        }
        "restart" -> {
            if (runtime.startRequest.pending || restarting) "error.conflict" else {
                runtime.displayRestarted()
                runtime.autoStartSessions.tracker.restarting()
                restarting = true
                stop()
                awaitStopped(System.nanoTime() + TimeUnit.SECONDS.toNanos(15))
                null
            }
        }
        else -> "error.invalid"
    }
    private fun stop() {
        runtime.input.expectStop()
        context.startService(Intent(context, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STOP))
    }
    private fun awaitStopped(deadline: Long) {
        worker.schedule({
            if (closed || !restarting) return@schedule
            runtime.autoStartSessions.refresh()
            if (!runtime.autoStartSessions.tracker.running) {
                restarting = false
                start()
            } else if (System.nanoTime() < deadline) awaitStopped(deadline)
            else { restarting = false; runtime.status.inputFailed(IllegalStateException("Restart timed out")) }
        }, 250, TimeUnit.MILLISECONDS)
    }
    @Synchronized override fun start(manual: Boolean): String? {
        if (closed || runtime.startRequest.pending) return "error.conflict"
        if (manual) runtime.autoStartSessions.resume("Start requested")
        runtime.autoStartSessions.refresh()
        if (runtime.autoStartSessions.tracker.running) return null
        val store = runtime.settingsStore
        val settings = store.awaitSnapshot()
        if (store.carlinkWifiDirectConflict(settings)) {
            runtime.status.inputFailed(IllegalStateException(CarlinkConnectionPolicy.REASON))
            return CarlinkConnectionPolicy.REASON_KEY
        }
        runtime.refreshIphoneBonds()
        val wireless = store.wireless(settings)
        if (wireless && runtime.iphoneSelection(settings.iphoneAddress).address == null) return "error.conflict"
        val permissions = StartupPermissions.required(Build.VERSION.SDK_INT, wireless,
            settings.wirelessNetwork.choice.mode, AirPlayPersistence.loadLocationReportingEnabled(context), settings.carlinkOutput || settings.bothOutputs)
        if (permissions.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) return "error.conflict"
        if (!wireless && VpnService.prepare(context) != null) return "error.conflict"
        if (settings.mfiTarget == MfiTarget.LOCAL && runtime.identityStore.status() != MaterialStatus.Installed) return "error.conflict"
        if (runCatching { settings.validate(wireless) }.isFailure) return "error.invalid"
        val sharedWifi = if (wireless && settings.wirelessNetwork.choice == org.openxplay.direlay.settings.WirelessNetworkChoice.EXISTING_WIFI)
            runtime.currentSharedWifi(settings) ?: return "error.sharedWifi" else null
        if (!runtime.startRequest.begin()) return "error.conflict"
        val launchGeneration = ++generation
        try {
            runtime.status.clearFailure()
            sharedWifi?.let { AirPlayPersistence.saveExistingWifiCredentials(context, it.ssid, it.passphrase) }
            store.prepareStart()
            runtime.prepareCarPlay(chooseVideoCodec = true)
            runtime.prepareCarPlayScreen(settings)
            runtime.prepareCarPlayFrameRate()
            main.post {
                try {
                    if (!closed && launchGeneration == generation) context.startActivity(Intent(context, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (failure: RuntimeException) { runtime.status.inputFailed(failure) }
                finally { runtime.startRequest.finish() }
            }
        } catch (failure: Exception) {
            runtime.startRequest.finish(); runtime.status.inputFailed(failure); return "error.conflict"
        }
        return null
    }
    override fun close() { closed = true; generation++; restarting = false; worker.shutdownNow() }
}
