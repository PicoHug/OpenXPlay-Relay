// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-21
package org.openxplay.direlay.bridge

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.provider.Settings
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The phone's USB role, and the system switch that decides it.
 *
 * A phone plugged into an iPhone is a USB device by default: it charges the iPhone and the iPhone
 * sends it nothing. To read an iPhone over USB the phone has to be the USB host instead, and the
 * OTG switch in the system settings is what turns that on. Xiaomi keeps that switch under
 * Settings, More settings, OTG, and turns it off again after ten minutes of inactivity, which is
 * why a cable that worked a moment ago starts charging the phone instead.
 *
 * The switch belongs to the system, so an ordinary app cannot write it: it needs
 * MANAGE_USB or WRITE_SECURE_SETTINGS, which only a platform-signed app or a root shell has. This
 * therefore reports the role, opens the page that owns the switch, and tries a root shell when the
 * phone is rooted (2026-10-21).
 */
internal class UsbHostControl(private val context: Context) {
    /** Which side of the cable the phone is on. */
    enum class Role { HOST, DEVICE, UNKNOWN }

    data class State(
        val role: Role,
        val attached: List<String>,
        val rooted: Boolean,
    ) {
        /** The phone is a host and a device answered, so a cable is live. */
        val hostReady: Boolean get() = role == Role.HOST && attached.isNotEmpty()
    }

    /** The Type-C data role the kernel reports, read from the port's sysfs node. */
    fun role(): Role = readDataRole()

    /** The USB devices the phone currently sees, named by vendor and product. */
    fun attachedDevices(): List<String> {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values.map { device: UsbDevice ->
            val hex = "%04x:%04x".format(device.vendorId, device.productId)
            when (device.vendorId) {
                0x05ac -> "$hex (Apple)"
                0x1a86 -> "$hex (CH341)"
                else -> hex
            }
        }
    }

    fun state(): State = State(role(), attachedDevices(), rooted())

    /**
     * Open the page that owns the OTG switch.
     *
     * The activity differs between Xiaomi releases and the setting has moved more than once, so
     * the known candidates are tried in turn and the general settings page is the fallback.
     */
    fun openOtgSettings(): Boolean {
        val candidates = listOf(
            "com.android.settings/.Settings$OtgSettingsActivity",
            "com.miui.securitycenter/com.miui.permcenter.permissions.PermissionsEditorActivity",
            Settings.ACTION_SETTINGS,
        )
        for (action in candidates) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        return false
    }

    /** Whether a root shell is available on this phone. */
    fun rooted(): Boolean = rootShell() != null

    /**
     * Ask a root shell to put the phone in host mode.
     *
     * The OTG switch is a system setting, and its name has moved between Xiaomi releases. With a
     * root shell the real names can be read instead of guessed: the settings namespaces are
     * listed and every key that mentions OTG is turned on. The role is read back afterwards,
     * because the command succeeding and the phone becoming a host are two different things.
     */
    fun enableWithRoot(): Result {
        val shell = rootShell() ?: return Result(Code.NO_ROOT, role())
        val before = role()
        if (before == Role.HOST) return Result(Code.ALREADY, before)
        val keys = otgSettingKeys(shell)
        for (key in keys) {
            runCatching { ProcessBuilderCompat.run(shell, "settings put global $key 1") }
            runCatching { ProcessBuilderCompat.run(shell, "settings put secure $key 1") }
            runCatching { ProcessBuilderCompat.run(shell, "settings put system $key 1") }
            if (role() == Role.HOST) return Result(Code.ENABLED, Role.HOST)
        }
        // The key names are not always in the settings namespaces; some releases keep the switch
        // in a system property instead.
        for (command in PROPERTY_COMMANDS) {
            runCatching { ProcessBuilderCompat.run(shell, command) }
            if (role() == Role.HOST) return Result(Code.ENABLED, Role.HOST)
        }
        return Result(Code.FAILED, role())
    }

    /**
     * The OTG related setting keys the system actually has.
     *
     * Listing them is what makes this work across Xiaomi releases: a key that does not exist on
     * this phone is simply not in the list.
     */
    private fun otgSettingKeys(shell: String): List<String> {
        val keys = linkedSetOf<String>()
        for (namespace in listOf("global", "secure", "system")) {
            val output = ProcessBuilderCompat.output(shell, "settings list $namespace") ?: continue
            for (line in output.lineSequence()) {
                val name = line.substringBefore('=').trim()
                if (name.isNotEmpty() && name.contains("otg", ignoreCase = true)) keys.add(name)
            }
        }
        return keys.toList()
    }

    enum class Code { ENABLED, ALREADY, NO_ROOT, FAILED }

    data class Result(val code: Code, val role: Role)

    companion object {
        /** The kernel's view of the port's data role. */
        private val ROLE_NODES = listOf(
            "/sys/class/typec/port0/data_role",
            "/sys/class/typec/port1/data_role",
            "/sys/class/extcon/usb/data_role",
        )

        /**
         * System properties that hold the OTG switch on releases that do not keep it in the
         * settings namespaces.
         */
        private val PROPERTY_COMMANDS = listOf(
            "setprop persist.sys.usb.otg 1",
            "setprop sys.usb.otg 1",
            "svc usb setFunctions none",
        )

        /** The class name Xiaomi uses for the OTG page on some releases. */
        private const val OtgSettingsActivity = "UsbSettingsActivity"

        /**
         * The role the kernel reports for the port.
         *
         * The node reads "[host] device" when the phone drives the bus and "[device] host" when
         * it is the one being driven, so the first word is the phone's own role.
         */
        fun parseRole(value: String): Role {
            val text = value.trim()
            return when {
                text.startsWith("[host]", ignoreCase = true) -> Role.HOST
                text.startsWith("[device]", ignoreCase = true) -> Role.DEVICE
                text.contains("host", ignoreCase = true) -> Role.HOST
                text.contains("device", ignoreCase = true) -> Role.DEVICE
                else -> Role.UNKNOWN
            }
        }

        fun readDataRole(): Role {
            for (node in ROLE_NODES) {
                val file = File(node)
                if (!file.canRead()) continue
                val value = runCatching { file.readText() }.getOrNull() ?: continue
                val role = parseRole(value)
                if (role != Role.UNKNOWN) return role
            }
            return Role.UNKNOWN
        }

        private fun rootShell(): String? {
            for (path in listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")) {
                if (File(path).canExecute()) return path
            }
            return null
        }
    }
}

/** Runs one command through a root shell and waits for it. */
internal object ProcessBuilderCompat {
    fun run(shell: String, command: String): Int {
        val process = ProcessBuilder(shell, "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            return -1
        }
        return process.exitValue()
    }

    /** Runs one command through a root shell and returns what it printed. */
    fun output(shell: String, command: String): String? {
        val process = ProcessBuilder(shell, "-c", command).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        return runCatching { process.inputStream.bufferedReader().readText() }.getOrNull()
    }
}
