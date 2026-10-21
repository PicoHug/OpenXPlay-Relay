// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-21
package org.openxplay.direlay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * The phone's USB role, with the switch that changes it.
 *
 * A phone that is a USB device charges the iPhone it is plugged into, and the iPhone sends it
 * nothing. The OTG switch in the system settings makes the phone the host instead. It is a system
 * setting, so this panel reports the role, opens the page that owns the switch, and offers a root
 * shell on a rooted phone (2026-10-21).
 */
@Composable fun UsbHostPanel() {
    val context = LocalContext.current
    val control = remember(context) { UsbHostControl(context.applicationContext) }
    var state by remember { mutableStateOf<UsbHostControl.State?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        state = withContext(Dispatchers.IO) { control.state() }
    }
    LaunchedEffect(Unit) { refresh() }

    val current = state
    SmallHeading(stringResource(R.string.usb_otg_heading))
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
        Text(stringResource(R.string.usb_otg_charging_hint), style = MaterialTheme.typography.bodySmall)
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
    }
    Column {
        TextButton(onClick = {
            if (!control.openOtgSettings()) message = context.getString(R.string.usb_otg_no_settings_page)
        }) { Text(stringResource(R.string.usb_otg_open_settings)) }
        if (current?.rooted == true) {
            TextButton(enabled = !busy, onClick = {
                busy = true
                message = null
            }) { Text(stringResource(R.string.usb_otg_enable_root)) }
        }
    }
    // A root attempt and a settings trip both end with a refresh, so the panel shows the result.
    LaunchedEffect(busy, message) {
        if (busy) {
            val result = withContext(Dispatchers.IO) { control.enableWithRoot() }
            message = context.getString(
                when (result.code) {
                    UsbHostControl.Code.ENABLED -> R.string.usb_otg_root_enabled
                    UsbHostControl.Code.ALREADY -> R.string.usb_otg_root_already
                    UsbHostControl.Code.NO_ROOT -> R.string.usb_otg_root_unavailable
                    UsbHostControl.Code.FAILED -> R.string.usb_otg_root_failed
                }
            )
            busy = false
            refresh()
        }
    }
}
