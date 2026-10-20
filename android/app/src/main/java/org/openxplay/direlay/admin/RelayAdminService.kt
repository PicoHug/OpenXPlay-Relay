// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
package org.openxplay.direlay.admin

import android.content.Context
import android.util.Log
import org.openxplay.direlay.relay.RelayRuntime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns independent admin HTTP and discovery lifetimes, never the CarPlay session lifetime. */
class RelayAdminService(private val context: Context, private val runtime: RelayRuntime,
    actions: AdminActions, private val advertiser: HostAdvertiser = JmDnsHostAdvertiser(context)) : AutoCloseable {
    private val preferences = context.getSharedPreferences("direlay-admin", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "direlay-admin").apply { isDaemon = true } }
    private val auth = AdminAuth(PrivateAdminAuthStore(context))
    private val backend: RelayAdmin = AndroidRelayAdmin(context, runtime, actions) { advertisedHost }
    private val server = AdminServer(AdminRouter(backend, auth, AdminAssets { path ->
        try { context.assets.open("admin/$path").use { it.readBytes() } } catch (_: java.io.IOException) { null }
    }))
    @Volatile var enabled: Boolean = preferences.getBoolean("enabled", true)
        private set
    @Volatile var advertisedHost: String? = null
        private set
    @Volatile private var closed = false
    private var listening = false
    private var certificateHost: String? = null
    private var certificateFingerprint: String? = null
    private val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    val urls: List<String> get() = listOf("https://${advertisedHost ?: "openxplay-android.local"}:6975/") +
        runtime.carIpv4.map { "https://$it:6975/" }
    init { worker.scheduleWithFixedDelay(::tick, 0, 5, TimeUnit.SECONDS) }
    fun setEnabled(value: Boolean) {
        enabled = value
        worker.execute {
            check(preferences.edit().putBoolean("enabled", value).commit())
            tick()
        }
    }
    /** Called on an IO worker; return only after the password and sessions are persisted. */
    fun resetPassword() { auth.reset() }
    fun restartTls() { if (!closed) worker.execute { server.close(); listening = false; tick() } }
    private fun tick() {
        if (closed || !enabled) { stop(); return }
        try {
            val ip = runtime.carIpv4.sorted().firstOrNull()
            if (ip == null) { advertiser.close(); advertisedHost = null }
            else {
                advertiser.advertise(ip, runtime.settingsStore.awaitSnapshot().webPort ?: 8443, version)
                advertisedHost = advertiser.host
            }
        } catch (failure: Exception) { advertisedHost = advertiser.host; Log.w("OpenXPlay Relay", "Admin discovery unavailable", failure) }
        try {
            val host = advertisedHost
            if (host != certificateHost || runtime.tlsStore.fingerprint() != certificateFingerprint) { server.close(); listening = false }
            if (!listening) {
                server.start(runtime.tlsStore.webContext(runtime.carIpv4, listOfNotNull(host).toSet()))
                certificateHost = host; certificateFingerprint = runtime.tlsStore.fingerprint(); listening = true
            }
        } catch (failure: Exception) { Log.w("OpenXPlay Relay", "Admin HTTPS unavailable", failure) }
    }
    private fun stop() {
        server.close(); listening = false
        advertiser.close(); advertisedHost = null; certificateHost = null
    }
    override fun close() {
        closed = true
        // Closing JmDNS may send goodbye packets and wait; keep it off the caller's thread.
        worker.execute(::stop)
        worker.shutdown()
    }
}
