// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
package org.openxplay.direlay.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openxplay.direlay.R
import org.openxplay.direlay.relay.RelayRuntime

fun createCarPlayHostOverlay(activity: ComponentActivity, runtime: RelayRuntime): ComposeView? =
    hostOverlayUnlessPreview(runtime.settingsStore.snapshot.value?.localPreview ?: runtime.localPreview) {
        ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RelayTheme { RuntimeHostOverlay(activity, runtime) } }
        }
    }

private fun RelayRuntime.overlaySnapshot(): HostOverlaySnapshot? {
    val settings = settingsStore.snapshot.value ?: return null
    val selection = iphoneSelection(settings.iphoneAddress)
    val name = iphoneBonds.firstOrNull { it.address.equals(selection.address, ignoreCase = true) }?.name
    val urls = receiverUrls.filterNot { it.contains("://localhost:") }
        .filter { url -> carIpv4.any { url.contains("://$it:") } }
    return HostOverlaySnapshot(HomeSnapshot(settings, settingsStore.wireless(settings),
        permissionsReady = true, identityReady = true, phase = status.session?.phase, started = true,
        failure = status.failure != null || (settings.carlinkOutput && carlinkFailed) ||
            ((!settings.carlinkOutput || settings.bothOutputs) && serverReason != null),
        iphoneSelection = selection, webReason = serverReason, webListening = serverState.startsWith("TLS enabled"),
        carNetworkAvailable = carIpv4.isNotEmpty()), name, urls, stopped = autoStartGate.paused)
}

@Composable private fun RuntimeHostOverlay(activity: ComponentActivity, runtime: RelayRuntime) {
    val snapshot by produceState<HostOverlaySnapshot?>(null, activity, runtime) {
        while (true) {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                value = withContext(Dispatchers.IO) { runtime.overlaySnapshot() }
            }
            delay(500)
        }
    }
    val scope = rememberCoroutineScope()
    var stopping by remember { mutableStateOf(false) }
    fun back() {
        // Use the host's home path, including touch release and its launcher routing to OpenXPlay Relay.
        activity.onBackPressedDispatcher.onBackPressed()
    }
    if (snapshot == null) {
        RelayPage { Heading(stringResource(R.string.host_connecting)) }
    }
    snapshot?.let { value ->
        // A saved preview change may arrive while the host is still alive.
        if (!value.home.settings.localPreview) HostOverlayScreen(presentHostOverlay(value), stopping) { action ->
            when (action) {
                HostOverlayAction.BACK -> back()
                HostOverlayAction.STOP -> if (!stopping) {
                    stopping = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { runtime.sessionActions.action("stop") }
                            back()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            runtime.status.inputFailed(failure)
                        } finally { stopping = false }
                    }
                }
            }
        }
    }
}

@Composable internal fun HostOverlayScreen(value: HostOverlayPresentation, stopping: Boolean,
    onAction: (HostOverlayAction) -> Unit) {
    val motion = rememberRelayMotion()
    RelayPage {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val description = stringResource(value.headline)
            when (value.tone) {
                StatusTone.PROGRESS -> if (motion) CircularProgressIndicator(Modifier.size(48.dp))
                    else Symbol(R.drawable.symbol_sync, description, Modifier.size(48.dp))
                StatusTone.RUNNING -> CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                    Symbol(R.drawable.symbol_check, description, Modifier.size(48.dp))
                }
                StatusTone.ERROR -> Symbol(R.drawable.symbol_error, description, Modifier.size(48.dp))
                else -> Symbol(R.drawable.symbol_stop, description, Modifier.size(48.dp))
            }
            Heading(description)
            value.iphoneName?.let { Text(stringResource(R.string.ui_iphone_name, it), style = MaterialTheme.typography.titleMedium) }
            RelayCard {
                value.outputs.forEach { output ->
                    Text(stringResource(output), style = MaterialTheme.typography.titleMedium)
                    if (output == R.string.ui_browser) {
                        value.receiverUrls.forEach { Text(it) }
                        value.browserHint?.let { Hint(stringResource(it)) }
                    }
                }
            }
            value.hint?.let { Hint(stringResource(it, *value.hintArguments.toTypedArray())) }
            value.buttons.forEach { button ->
                OutlinedButton(onClick = { onAction(button.action) }, enabled = !stopping,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Symbol(if (button.action == HostOverlayAction.BACK) R.drawable.symbol_back else R.drawable.symbol_stop,
                        stringResource(button.label), Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(button.label), Modifier.weight(1f))
                }
            }
        }
    }
}
