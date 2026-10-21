// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-21
package org.openxplay.direlay.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openxplay.direlay.R
import org.openxplay.direlay.bridge.UsbHostControl

/**
 * The phone's USB role and the OTG switch that decides it.
 *
 * A phone that is a USB device charges the iPhone it is plugged into, and the iPhone sends it
 * nothing, so CarPlay over the cable never starts. The OTG switch in the system settings makes
 * the phone the host instead. It is a system setting, so an ordinary app cannot write it: it
 * needs MANAGE_USB or WRITE_SECURE_SETTINGS, which only a platform-signed app or a root shell has
 * (2026-10-21).
 *
 * On a rooted phone the switch is read and written here, so the user can turn OTG on while the
 * cable is in use and turn it off again afterwards, which leaves the phone's charging behaviour
 * the way it was. Without root the switch still shows the role and opens the system page.
 */
@Composable fun UsbHostPanel() {
    val context = LocalContext.current
    val control = remember(context) { UsbHostControl(context.applicationContext) }
    var state by remember { mutableStateOf<UsbHostControl.State?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Boolean?>(null) }

    suspend fun refresh() {
        state = withContext(Dispatchers.IO) { control.state() }
    }
    LaunchedEffect(Unit) { refresh() }

    val current = state
    // The switch shows the system setting when it can be read. Without root the role is the
    // witness: the phone is the host exactly when the switch is on.
    val otgOn = current?.otgOn ?: (current?.role == UsbHostControl.Role.HOST)
    val hasSwitch = current?.hasSwitch == true
    SmallHeading(stringResource(R.string.usb_otg_heading))
    if (hasSwitch) {
        Toggle(stringResource(R.string.usb_otg_toggle), otgOn) { want -> pending = want }
    } else {
        Text(stringResource(R.string.usb_otg_no_switch), style = MaterialTheme.typography.bodySmall)
    }
    Text(
        stringResource(
            R.string.usb_otg_role,
            when (current?.role) {
                UsbHostControl.Role.HOST -> stringResource(R.string.usb_otg_role_host)
                UsbHostControl.Role.DEVICE -> stringResource(R.string.usb_otg_role_device)
                else -> stringResource(R.string.usb_otg_role_unknown)
            }
        ),
        style = MaterialTheme.typography.bodySmall
    )
    Text(
        if (current != null && current.attached.isNotEmpty())
            stringResource(R.string.usb_otg_devices, current.attached.joinToString(", "))
        else stringResource(R.string.usb_otg_no_devices),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
    if (current?.role == UsbHostControl.Role.DEVICE) {
        Text(
            stringResource(if (hasSwitch) R.string.usb_otg_charging_hint else R.string.usb_otg_replug_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    }
    // The switch is acted on once: on a rooted phone the system setting is written, otherwise the
    // system page is opened, because that is the only place the switch can be changed.
    LaunchedEffect(pending) {
        val want = pending ?: return@LaunchedEffect
        pending = null
        message = null
        if (current?.rooted == true) {
            val result = withContext(Dispatchers.IO) { control.setOtg(want) }
            message = context.getString(
                when (result.code) {
                    UsbHostControl.Code.SET -> R.string.usb_otg_set
                    UsbHostControl.Code.NO_ROOT -> R.string.usb_otg_root_unavailable
                    else -> R.string.usb_otg_root_failed
                }
            )
        } else {
            message = context.getString(
                if (control.openOtgSettings()) R.string.usb_otg_open_system_page else R.string.usb_otg_no_settings_page
            )
        }
        refresh()
    }
}
