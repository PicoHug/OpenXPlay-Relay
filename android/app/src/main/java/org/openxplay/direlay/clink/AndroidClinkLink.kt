// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14
package org.openxplay.direlay.clink

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.*
import android.os.*
import org.openxplay.stream.clink.*
import org.openxplay.stream.clink.ble.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Blocking lifecycle API; Android callbacks and owned resource cleanup run on the main handler. */
@SuppressLint("MissingPermission")
class AndroidClinkLink(context: Context, private val deviceId: String,
    private val deviceName: String, private val deviceModel: String,
    private val diagnostics: ClinkDiagnostics = ClinkDiagnostics.NONE,
    private val p2pReservation: P2pReservation = P2pReservation { false }) : ClinkLink {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val p2p = context.getSystemService(WifiP2pManager::class.java)
    companion object {
        private val reconnect = LinkReconnect()
        internal val retryBackoff = LinkBackoff()
    }
    private val peers = BlePeerPolicy(object : BlePeerPolicy.Store {
        private val preferences = this@AndroidClinkLink.context.getSharedPreferences("clinkPeers", Context.MODE_PRIVATE)
        override fun read(key: String): Int? = preferences.getInt(key, 0).takeIf { it != 0 }
        override fun write(key: String, type: Int) {
            if (!preferences.edit().putInt(key, type).commit()) cleanupFailure(IllegalStateException("peer_store_failed"))
        }
    })
    // Retrying inside one handoff is a GATT hiccup, not a failed session: cap it at 4 s. Sharing the
    // session backoff here made a single failure cost 15 s on top of the session-level 15 s (2026-10-19).
    private val backoff = LinkBackoff(max = 4_000)
    private fun retry(run: Run) {
        val delay = backoff.next()
        diagnostics.emit(ClinkDiagnostic.Retry(delay))
        val done = CompletableFuture<Unit>()
        handler.postDelayed({ done.complete(Unit) }, delay)
        await(run, done, 16)
    }
    private fun awaitCleanup() {
        reconnect.awaitReleased(diagnostics)
    }
    private class Run(val lost: (Throwable?) -> Unit) {
        @Volatile var cancelled = false
        @Volatile var ready = false
        var lossReported = false
        var failure: Throwable? = null
        var peerKey: String? = null
        var peerChoice: BlePeerPolicy.Choice? = null
        var scan: ScanCallback? = null
        var scanner: BluetoothLeScanner? = null
        var gatt: BluetoothGatt? = null
        var closingGatt: BluetoothGatt? = null
        var bleClosed = CompletableFuture.completedFuture(Unit)
        var cccdTimer: Runnable? = null
        var verifyTimer: Runnable? = null
        var serviceMonitor: Runnable? = null
        var channel: WifiP2pManager.Channel? = null
        var headUnitClientGroup = false
        var expectedSsid: String? = null
        var receiver: BroadcastReceiver? = null
        var networkCallback: ConnectivityManager.NetworkCallback? = null
        var monitor: Runnable? = null
        var bound: Network? = null
        var previousBinding: Network? = null
        val waits = java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<*>>()
    }
    @Volatile private var current: Run? = null
    override fun connect(onLinkLost: (Throwable?) -> Unit): ClinkAddresses {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Connect must run on a worker" }
        val run = Run(onLinkLost)
        synchronized(this) { check(current == null); current = run }
        diagnostics.emit(ClinkStage.LINK_START)
        try {
            awaitCleanup()
            check(!run.cancelled)
            check(Build.VERSION.SDK_INT >= 29) { "Carlink Wi-Fi join requires Android 10 or later" }
            var handoff: Pair<String, HeadUnitReply>? = null
            for (attempt in 0..2) {
                try {
                    // Fresh scan on every retry: the app may change its random BLE address.
                    val device = diagnosed(ClinkStage.BLE_SCAN, ClinkFailureReason.BLE_TIMEOUT) { scan(run) }
                    handoff = device.second to diagnosed(ClinkStage.BLE_EXCHANGE,
                        ClinkFailureReason.BLE_EXCHANGE_TIMEOUT) { exchange(run, device.first, device.second) }
                    break
                } catch (failure: Exception) {
                    main { closeBle(run) }
                    awaitCleanup()
                    if (run.cancelled || attempt == 2) throw failure
                    retry(run)
                }
            }
            val (carId, reply) = checkNotNull(handoff)
            diagnostics.handoff(reply.ssid, reply.psk.length)
            // No PIN/UI pause here; return immediately after DHCP so clink can start AUTH.
            val endpoints = diagnosed(ClinkStage.WIFI_JOIN, ClinkFailureReason.WIFI_TIMEOUT) { join(run, reply) }
            diagnostics.emit(ClinkDiagnostic.Joined(endpoints.first, endpoints.second))
            main {
                check(!run.cancelled) { "Link cancelled" }
                run.failure?.let { throw it }
                run.ready = true
                run.peerKey?.let { peers.succeeded(it, checkNotNull(run.peerChoice)) }
            }
            return ClinkAddresses(endpoints.first, endpoints.second,
                reply.port.takeIf { it != 0 } ?: 57209, carId)
        } catch (failure: Exception) {
            disconnectRun(run)
            throw failure
        }
    }
    private fun <T> diagnosed(stage: ClinkStage, timeout: ClinkFailureReason, action: () -> T): T {
        try { return action() }
        catch (error: Exception) {
            val cause = ClinkFailures.unwrap(error)
            val failure = if (cause is java.util.concurrent.TimeoutException) ClinkFailure(timeout, cause) else cause
            diagnostics.emit(ClinkFailures.describe(stage, failure))
            throw failure
        }
    }
    private fun <T> await(run: Run, future: CompletableFuture<T>, seconds: Long): T {
        run.waits.add(future)
        try {
            check(!run.cancelled) { "Link cancelled" }
            return future.get(seconds, TimeUnit.SECONDS)
        } finally { run.waits.remove(future) }
    }
    private fun scan(run: Run): Pair<BluetoothDevice, String> {
        val result = CompletableFuture<Pair<BluetoothDevice, String>>()
        main {
            check(!run.cancelled)
            val scanner = bluetooth?.adapter?.bluetoothLeScanner ?: throw ClinkFailure(ClinkFailureReason.BLE_UNAVAILABLE)
            val callback = object : ScanCallback() {
                override fun onScanResult(type: Int, scan: ScanResult) {
                    if (run.cancelled || result.isDone) return
                    val record = scan.scanRecord ?: return
                    try {
                        val identity = record.getServiceData(ParcelUuid(UUID.fromString(BleCodec.IDENTITY_SERVICE))) ?: return
                        val name = record.getServiceData(ParcelUuid(UUID.fromString(BleCodec.NAME_SERVICE))) ?: byteArrayOf()
                        val advertisement = BleCodec.advertisement(identity, name)
                        diagnostics.emit(ClinkStage.BLE_PEER)
                        result.complete(scan.device to advertisement.carId)
                    } catch (_: IllegalArgumentException) { /* Ignore malformed advertisements. */ }
                }
                override fun onScanFailed(errorCode: Int) {
                    result.completeExceptionally(ClinkFailure(ClinkFailureReason.BLE_SCAN))
                }
            }
            run.scanner = scanner; run.scan = callback
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(
                ParcelUuid(UUID.fromString(BleCodec.ADVERTISEMENT_SERVICE))).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            diagnostics.emit(ClinkStage.BLE_SCAN)
        }
        try { return await(run, result, 8) }
        finally { main { stopScan(run) } }
    }
    private fun exchange(run: Run, device: BluetoothDevice, carId: String): HeadUnitReply {
        val key = peers.key(carId, device.address)
        val choice = peers.choice(key)
        val result = CompletableFuture<HeadUnitReply>()
        val phone = BleCodec.phoneRequest(PhoneRequest(deviceId, deviceName, deviceModel, 1, phoneMac(), choice.requestType))
        main {
            check(!run.cancelled)
            var phoneCharacteristic: BluetoothGattCharacteristic? = null
            var replyCharacteristic: BluetoothGattCharacteristic? = null
            lateinit var machine: BleExchange
            var pendingDescriptor: BluetoothGattDescriptor? = null
            fun cancelCccdTimer() {
                run.cccdTimer?.let(handler::removeCallbacks)
                run.cccdTimer = null
                pendingDescriptor = null
            }
            fun cancelVerifyTimer() {
                run.verifyTimer?.let(handler::removeCallbacks)
                run.verifyTimer = null
            }
            fun serviceChanged() {
                diagnostics.emit(ClinkDiagnostic.ServiceChanged(machine.state.name.lowercase()))
                val verifying = machine.state == BleExchange.State.VERIFYING
                machine.serviceChanged()
                if (verifying || machine.state != BleExchange.State.VERIFYING) return
                // Bound the post-request rediscovery; an unconfirmed change fails without switching the type.
                cancelVerifyTimer()
                val gatt = run.gatt
                val timer = Runnable {
                    run.verifyTimer = null
                    if (run.gatt === gatt && !run.cancelled) machine.verifyTimeout()
                }
                run.verifyTimer = timer
                handler.postDelayed(timer, 2000)
            }
            machine = BleExchange(object : BleExchange.Gatt {
                override fun requestMtu(size: Int) = run.gatt?.requestMtu(size) == true
                override fun discover() = run.gatt?.discoverServices() == true
                override fun subscribeLocally(): Boolean {
                    val service = run.gatt?.getService(UUID.fromString(BleCodec.GATT_SERVICE)) ?: return false
                    phoneCharacteristic = service.getCharacteristic(UUID.fromString(BleCodec.PHONE_CHARACTERISTIC))
                    replyCharacteristic = service.getCharacteristic(UUID.fromString(BleCodec.HEAD_UNIT_CHARACTERISTIC))
                    if (phoneCharacteristic == null || replyCharacteristic == null) return false
                    return run.gatt?.setCharacteristicNotification(replyCharacteristic, true) == true
                }
                override fun subscribeCccd(): Boolean {
                    val characteristic = replyCharacteristic ?: return false
                    val descriptor = characteristic.getDescriptor(UUID.fromString(BleCodec.CCCD))
                    val gatt = run.gatt ?: return false
                    val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    val accepted = descriptor != null && if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                    } else {
                        descriptor.value = value
                        gatt.writeDescriptor(descriptor)
                    }
                    if (accepted) {
                        pendingDescriptor = descriptor
                        val timer = Runnable {
                            run.cccdTimer = null
                            pendingDescriptor = null
                            if (run.gatt === gatt && !run.cancelled && (machine.state == BleExchange.State.SUBSCRIBING || machine.state == BleExchange.State.REDISCOVERING)) {
                                diagnostics.emit(ClinkDiagnostic.Cccd(ClinkDiagnostic.CccdResult.TIMEOUT))
                                machine.cccdTimeout()
                            }
                        }
                        run.cccdTimer = timer
                        handler.postDelayed(timer, 2000)
                    } else diagnostics.emit(ClinkDiagnostic.Cccd(ClinkDiagnostic.CccdResult.FAILED))
                    return accepted
                }
                override fun write(payload: ByteArray): Boolean {
                    val characteristic = phoneCharacteristic ?: return false
                    val gatt = run.gatt ?: return false
                    return if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeCharacteristic(characteristic, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                    } else {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        characteristic.value = payload
                        gatt.writeCharacteristic(characteristic)
                    }
                }
                override fun requestUnchanged(): Boolean {
                    val request = run.gatt?.getService(UUID.fromString(BleCodec.GATT_SERVICE))
                        ?.getCharacteristic(UUID.fromString(BleCodec.PHONE_CHARACTERISTIC)) ?: return false
                    // The request went to the old handle; a moved characteristic did not receive it.
                    return request.instanceId == phoneCharacteristic?.instanceId
                }
            }, phone, choice.legacy) { reply, failure ->
                cancelCccdTimer()
                cancelVerifyTimer()
                if (reply != null) {
                    run.peerKey = key
                    run.peerChoice = choice
                    diagnostics.emit(ClinkDiagnostic.Reply(reply.type))
                    diagnostics.emit(ClinkDiagnostic.Exchange(reply.freq))
                    result.complete(reply)
                } else {
                    val reason = when ((failure as? BleExchange.Failure)?.reason) {
                        BleExchange.Reason.CCCD_TIMEOUT -> {
                            peers.cccdTimedOut(key, choice)
                            // A pending descriptor blocks the GATT queue; the retry must use a fresh connection.
                            ClinkFailureReason.BLE_CCCD_TIMEOUT
                        }
                        BleExchange.Reason.REQUEST_REJECTED -> {
                            peers.rejected(key, choice)
                            ClinkFailureReason.BLE_REQUEST_REJECTED
                        }
                        else -> ClinkFailureReason.BLE_EXCHANGE
                    }
                    result.completeExceptionally(ClinkFailure(reason, failure))
                }
            }
            val callback = object : BluetoothGattCallback() {
                private fun event(gatt: BluetoothGatt, action: () -> Unit) {
                    handler.post {
                        if (!run.cancelled && run.gatt === gatt) try { action() }
                        catch (error: Exception) { result.completeExceptionally(error) }
                    }
                }
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, state: Int) {
                    handler.post {
                        if (state == BluetoothProfile.STATE_DISCONNECTED && run.closingGatt === gatt) finishBleClose(run)
                    }
                    event(gatt) {
                        if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                            result.completeExceptionally(ClinkFailure(ClinkFailureReason.GATT_CONNECT))
                            machine.fail()
                        } else if (state == BluetoothProfile.STATE_CONNECTED) {
                            diagnostics.emit(ClinkStage.GATT_CONNECTED)
                            machine.connected()
                            // When another app's GATT keeps the LE link up, Android reuses it and may never
                            // report the MTU; continue without it (mtu() ignores a late or duplicate call).
                            handler.postDelayed({ event(gatt) { machine.mtu(false) } }, 1500)
                        }
                    }
                }
                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = event(gatt) {
                    machine.mtu(status == BluetoothGatt.GATT_SUCCESS)
                }
                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = event(gatt) {
                    if (status == BluetoothGatt.GATT_SUCCESS) diagnostics.emit(ClinkStage.GATT_SERVICES)
                    else result.completeExceptionally(ClinkFailure(ClinkFailureReason.GATT_SERVICES))
                    if (status == BluetoothGatt.GATT_SUCCESS && gatt.getService(UUID.fromString(BleCodec.GATT_SERVICE)) == null)
                        machine.serviceRemoved()
                    else machine.services(status == BluetoothGatt.GATT_SUCCESS)
                }
                override fun onServiceChanged(gatt: BluetoothGatt) = event(gatt) { serviceChanged() }
                override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = event(gatt) {
                    if (descriptor === pendingDescriptor && (machine.state == BleExchange.State.SUBSCRIBING || machine.state == BleExchange.State.REDISCOVERING)) {
                        cancelCccdTimer()
                        val success = status == BluetoothGatt.GATT_SUCCESS
                        diagnostics.emit(ClinkDiagnostic.Cccd(if (success) ClinkDiagnostic.CccdResult.SUCCESS else ClinkDiagnostic.CccdResult.FAILED))
                        machine.descriptorWritten(success)
                    }
                }
                override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = event(gatt) {
                    if (characteristic.uuid == UUID.fromString(BleCodec.PHONE_CHARACTERISTIC)) machine.written(status == BluetoothGatt.GATT_SUCCESS)
                }
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    val bytes = value.copyOf()
                    event(gatt) {
                        if (characteristic.uuid == UUID.fromString("00002a05-0000-1000-8000-00805f9b34fb")) serviceChanged()
                        else if (characteristic.uuid == UUID.fromString(BleCodec.HEAD_UNIT_CHARACTERISTIC)) machine.indication(bytes)
                    }
                }
                @Deprecated("Android legacy callback")
                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                    onCharacteristicChanged(gatt, characteristic, characteristic.value?.copyOf() ?: byteArrayOf())
                }
            }
            diagnostics.emit(ClinkDiagnostic.BleRequest(choice.requestType, choice.legacy))
            diagnostics.emit(ClinkStage.GATT_CONNECT)
            run.gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (run.gatt == null) throw ClinkFailure(ClinkFailureReason.GATT_CONNECT)
            val activeGatt = run.gatt
            val monitor = object : Runnable {
                override fun run() {
                    if (run.cancelled || result.isDone || run.gatt !== activeGatt) return
                    // Not while VERIFYING: discoverServices empties Android's service cache until it completes.
                    if ((machine.state == BleExchange.State.WRITING || machine.state == BleExchange.State.REPLY ||
                            machine.state == BleExchange.State.SUBSCRIBING) &&
                        activeGatt?.getService(UUID.fromString(BleCodec.GATT_SERVICE)) == null) {
                        diagnostics.emit(ClinkDiagnostic.ServiceChanged(machine.state.name.lowercase()))
                        machine.serviceRemoved()
                    }
                    if (!result.isDone) handler.postDelayed(this, 200)
                }
            }
            run.serviceMonitor = monitor
            handler.postDelayed(monitor, 200)
        }
        try { return await(run, result, 15) }
        finally {
            main { closeBle(run) }
            awaitCleanup()
        }
    }
    private fun join(run: Run, reply: HeadUnitReply): Pair<String, String> {
        val reserved = p2pReservation.reservedByCarPlay()
        var method = WifiJoinPolicy.first(Build.VERSION.SDK_INT, p2p != null && !reserved, reply.type)
        diagnostics.emit(ClinkDiagnostic.JoinChoice(method, when {
            method == WifiJoinPolicy.Method.UNSUPPORTED -> ClinkDiagnostic.JoinReason.API_UNSUPPORTED
            reply.type == 1002 -> ClinkDiagnostic.JoinReason.HEAD_UNIT_SOFT_AP
            reserved -> ClinkDiagnostic.JoinReason.CARPLAY_RESERVED
            p2p == null -> ClinkDiagnostic.JoinReason.P2P_UNAVAILABLE
            else -> ClinkDiagnostic.JoinReason.P2P_AVAILABLE
        }))
        if (method == WifiJoinPolicy.Method.P2P_CREDENTIALS) {
            try { return diagnosed(ClinkStage.WIFI_JOIN, ClinkFailureReason.P2P_TIMEOUT) { joinP2p(run, reply) } }
            catch (failure: Exception) {
                main { leaveP2p(run) }
                if (run.cancelled) throw failure
                method = WifiJoinPolicy.fallback(Build.VERSION.SDK_INT, method)
                diagnostics.emit(ClinkDiagnostic.JoinChoice(method, ClinkDiagnostic.JoinReason.P2P_FALLBACK))
            }
        }
        if (method != WifiJoinPolicy.Method.NETWORK_SPECIFIER) throw ClinkFailure(ClinkFailureReason.WIFI_UNAVAILABLE)
        return joinSpecifier(run, reply)
    }
    private fun joinP2p(run: Run, reply: HeadUnitReply): Pair<String, String> {
        val result = CompletableFuture<Pair<String, String>>()
        main {
            check(!run.cancelled)
            val manager = checkNotNull(p2p)
            val channel = manager.initialize(context, handler.looper) { if (run.channel != null) lost(run) }
            run.channel = channel
            run.expectedSsid = reply.ssid
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (run.ready && intent.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION &&
                        intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_DISABLED) lost(run)
                }
            }
            run.receiver = receiver
            val filter = IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
            var expected: Pair<String, String>? = null
            val monitor = object : Runnable {
                override fun run() {
                    if (run.cancelled || run.channel !== channel) return
                    manager.requestConnectionInfo(channel) { info ->
                        if (run.cancelled || run.channel !== channel) return@requestConnectionInfo
                        manager.requestGroupInfo(channel) { group ->
                            if (run.cancelled || run.channel !== channel) return@requestGroupInfo
                            val local = group?.`interface`?.let { iface ->
                                runCatching { NetworkInterface.getByName(iface)?.inetAddresses?.toList()
                                    ?.filterIsInstance<Inet4Address>()?.firstOrNull()?.hostAddress }.getOrNull()
                            }
                            run.headUnitClientGroup = WifiJoinPolicy.mayRemoveGroup(
                                info.groupFormed, info.isGroupOwner, group?.networkName, reply.ssid)
                            val endpoints = if (run.headUnitClientGroup)
                                WifiJoinPolicy.endpoints(info.groupOwnerAddress?.hostAddress, local) else null
                            if (endpoints != null && !result.isDone) { expected = endpoints; result.complete(endpoints) }
                            else if (expected != null && endpoints != expected) lost(run)
                            if (!run.cancelled) handler.postDelayed(this, 1000)
                        }
                    }
                }
            }
            run.monitor = monitor
            val config = WifiP2pConfig.Builder().setNetworkName(reply.ssid).setPassphrase(reply.psk)
                .enablePersistentMode(false).build()
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { handler.post(monitor) }
                override fun onFailure(reason: Int) { result.completeExceptionally(ClinkFailure(ClinkFailures.p2p(reason))) }
            })
        }
        return await(run, result, 7)
    }
    private fun joinSpecifier(run: Run, reply: HeadUnitReply): Pair<String, String> {
        val result = CompletableFuture<Pair<String, String>>()
        main {
            check(!run.cancelled)
            val manager = checkNotNull(connectivity)
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var selected: Network? = null
                private var expected: Pair<String, String>? = null
                override fun onAvailable(network: Network) { selected = network }
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    if (run.cancelled || run.networkCallback !== this || selected != network) return
                    val local = properties.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
                    // DHCP server is exposed directly starting with API 30; API 29 uses WifiManager's DHCP lease.
                    val peer = if (Build.VERSION.SDK_INT >= 30) properties.dhcpServerAddress?.hostAddress else {
                        val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
                        val lease = wifi?.dhcpInfo
                        fun address(value: Int): String = (0..3).joinToString(".") {
                            ((value ushr (it * 8)) and 255).toString()
                        }
                        // Reject a lease from another Wi-Fi interface when concurrent networking is active.
                        if (lease == null || lease.serverAddress == 0 || address(lease.ipAddress) != local) null
                        else address(lease.serverAddress)
                    }
                    val endpoints = WifiJoinPolicy.endpoints(peer, local)
                    if (expected != null && endpoints != expected) { lost(run); return }
                    if (endpoints == null) return
                    if (!result.isDone) {
                        run.previousBinding = manager.boundNetworkForProcess
                        if (!manager.bindProcessToNetwork(network)) {
                            result.completeExceptionally(ClinkFailure(ClinkFailureReason.WIFI_BIND)); return
                        }
                        run.bound = network
                        expected = endpoints
                        result.complete(endpoints)
                    }
                }
                override fun onUnavailable() { result.completeExceptionally(ClinkFailure(ClinkFailureReason.WIFI_UNAVAILABLE)) }
                override fun onLost(network: Network) {
                    if (selected == network) {
                        result.completeExceptionally(ClinkFailure(ClinkFailureReason.LINK_LOST))
                        lost(run)
                    }
                }
            }
            run.networkCallback = callback
            val specifier = WifiNetworkSpecifier.Builder().setSsid(reply.ssid).setWpa2Passphrase(reply.psk).build()
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier).build()
            manager.requestNetwork(request, callback, handler, 12000)
        }
        return await(run, result, 13)
    }
    private fun lost(run: Run) {
        if (run.cancelled) return
        val failure = ClinkFailure(ClinkFailureReason.LINK_LOST)
        if (run.failure == null) diagnostics.emit(ClinkFailures.describe(ClinkStage.LINK_LOST, failure))
        run.failure = failure
        if (run.ready && !run.lossReported) {
            run.lossReported = true
            run.lost(failure)
        }
    }
    override fun disconnect() {
        current?.let(::disconnectRun)
    }
    private fun disconnectRun(expected: Run) {
        val released = CompletableFuture<Unit>()
        val run = synchronized(this) {
            val active = current ?: return
            if (active !== expected) return
            // Publish the barrier before allowing this instance to connect again. The GATT close has
            // its own barrier entry, so forcing this one need not wait for it.
            reconnect.releasing(released) {
                main { settleRelease(released, listOf({ finishBleClose(active) }, { releaseNetwork(active) }), ::cleanupFailure) }
            }
            current = null
            active
        }
        diagnostics.emit(ClinkStage.LINK_STOP)
        run.cancelled = true
        run.waits.forEach { it.cancel(false) }
        // A failed step must neither skip the others nor leave the barrier waiting on [released].
        main {
            settleRelease(released, listOf({ closeBle(run) }, { leaveP2p(run) }, { releaseNetwork(run) }),
                ::cleanupFailure) { run.bleClosed }
        }
    }
    private fun releaseNetwork(run: Run) {
        run.networkCallback?.let {
            // unregisterNetworkCallback releases the request synchronously. Android does not send
            // onLost for an unregistered callback, so waiting for it would stall every reconnect.
            diagnostics.emit(ClinkStage.NETWORK_RELEASE)
            connectivity?.unregisterNetworkCallback(it)
            run.networkCallback = null
            diagnostics.emit(ClinkStage.NETWORK_RELEASED)
        }
        if (run.bound != null && connectivity?.boundNetworkForProcess == run.bound)
            check(connectivity.bindProcessToNetwork(run.previousBinding)) { "Network unbind failed" }
        run.bound = null
    }
    private fun leaveP2p(run: Run) {
        run.monitor?.let(handler::removeCallbacks); run.monitor = null
        run.receiver?.let { runCatching { context.unregisterReceiver(it) }.onFailure(::cleanupFailure) }; run.receiver = null
        run.channel?.let { channel ->
            runCatching { p2p?.cancelConnect(channel, null) }.onFailure(::cleanupFailure)
            if (run.headUnitClientGroup && !p2pReservation.reservedByCarPlay()) removeObservedGroup(run, channel)
            else channel.close()
            run.headUnitClientGroup = false
        }
        run.channel = null
    }
    private fun removeObservedGroup(run: Run, channel: WifiP2pManager.Channel) {
        // Revalidate at cleanup: another controller may have replaced the observed group.
        fun attempt(action: () -> Unit) {
            try { action() }
            catch (error: Exception) {
                diagnostics.emit(ClinkFailures.describe(ClinkStage.LINK_STOP, error))
                channel.close()
            }
        }
        attempt {
            p2p?.requestConnectionInfo(channel) { info ->
                attempt {
                    p2p?.requestGroupInfo(channel) { group ->
                        val matches = WifiJoinPolicy.mayRemoveGroup(info.groupFormed,
                            info.isGroupOwner, group?.networkName, run.expectedSsid ?: "")
                        if (matches && !p2pReservation.reservedByCarPlay()) attempt {
                            p2p?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() { channel.close() }
                                override fun onFailure(reason: Int) {
                                    diagnostics.emit(ClinkFailures.describe(ClinkStage.LINK_STOP,
                                        ClinkFailure(ClinkFailures.p2p(reason))))
                                    channel.close()
                                }
                            })
                        } else channel.close()
                    }
                }
            }
        }
    }
    private fun cleanupFailure(error: Throwable) {
        diagnostics.emit(ClinkFailures.describe(ClinkStage.LINK_STOP, error))
    }
    private fun stopScan(run: Run) {
        run.scan?.let { callback -> runCatching { run.scanner?.stopScan(callback) }.onFailure(::cleanupFailure) }
        run.scan = null; run.scanner = null
    }
    private fun closeBle(run: Run) {
        run.cccdTimer?.let(handler::removeCallbacks); run.cccdTimer = null
        run.verifyTimer?.let(handler::removeCallbacks); run.verifyTimer = null
        run.serviceMonitor?.let(handler::removeCallbacks); run.serviceMonitor = null
        stopScan(run)
        val gatt = run.gatt ?: return
        run.gatt = null
        run.closingGatt = gatt
        run.bleClosed = CompletableFuture()
        reconnect.releasing(run.bleClosed) { main { finishBleClose(run) } }
        diagnostics.emit(ClinkStage.GATT_DISCONNECT)
        runCatching { gatt.disconnect() }.onFailure { cleanupFailure(it); finishBleClose(run) }
        handler.postDelayed({ if (run.closingGatt === gatt) {
            diagnostics.emit(ClinkStage.CLEANUP_FORCE)
            finishBleClose(run)
        } }, 5_000)
    }
    private fun finishBleClose(run: Run) {
        val gatt = run.closingGatt ?: return
        try {
            gatt.close()
            run.closingGatt = null
            run.bleClosed.complete(Unit)
            diagnostics.emit(ClinkStage.GATT_CLOSED)
        } catch (failure: Exception) {
            cleanupFailure(failure)
            run.bleClosed.completeExceptionally(failure)
        }
    }
    private fun phoneMac(): String {
        // Android hides hardware MACs from ordinary apps. Use an explicit locally administered identity.
        val preferences = context.getSharedPreferences("direlay", Context.MODE_PRIVATE)
        preferences.getString("clinkPhoneMac", null)?.let { return it }
        val bytes = ByteArray(6).also { java.security.SecureRandom().nextBytes(it) }
        bytes[0] = ((bytes[0].toInt() or 2) and 254).toByte()
        val mac = bytes.joinToString(":") { "%02x".format(it.toInt() and 255) }
        check(preferences.edit().putString("clinkPhoneMac", mac).commit())
        return mac
    }
    private fun main(action: () -> Unit) {
        if (Looper.myLooper() == handler.looper) { action(); return }
        val result = CompletableFuture<Unit>()
        handler.post { try { action(); result.complete(Unit) } catch (failure: Exception) { result.completeExceptionally(failure) } }
        result.get(5, TimeUnit.SECONDS)
    }
}
