// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-15
package org.openxplay.direlay.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openxplay.direlay.R
import org.openxplay.direlay.clink.DialogPinProvider
import org.openxplay.direlay.clink.PinSheetState
import org.openxplay.direlay.vehicle.*

data class HomeContent(val presentation: HomePresentation, val addresses: List<String>,
    val ip: String, val wifi: String, val deviceName: String, val automatic: Boolean,
    val carlink: Boolean, val both: Boolean, val carlinkState: Int, val pending: DialogPinProvider.Request?,
    val busy: Boolean = false, val onWifi: Boolean = false, val vehicle: VehicleView = VehicleView(),
    val pinRejected: Int = 0, val pairingSuccess: Int = 0, val displayMismatch: Boolean = false, val currentWifi: String? = null, val iphoneName: String? = null)

@Composable fun MotionCard(visible: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val ms = if (LocalRelayMotion.current) 220 else 0
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = visible
    AnimatedVisibility(visibility, enter = fadeIn(tween(ms)) + expandVertically(tween(ms)),
        exit = fadeOut(tween(ms)) + shrinkVertically(tween(ms))) { RelayCard(content) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun HomeScreen(value: HomeContent, onSettings: (String) -> Unit, onStartStop: () -> Unit,
    onAutomatic: (Boolean) -> Unit, onSetup: (SetupAction) -> Unit, onCopy: (String) -> Unit,
    onReconnect: () -> Unit, onNetwork: () -> Unit, onVehicle: () -> Unit,
    vehicleModifier: Modifier = Modifier, vehicleContentModifier: Modifier = Modifier, onRestart: () -> Unit = {},
    setupCompleted: Boolean = true, onGuide: (String?) -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val window = homeWindow(maxWidth.value.toInt(), maxHeight.value.toInt())
    val motion = LocalRelayMotion.current
    val status: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusHero(value)
            MotionCard(value.displayMismatch && value.presentation.running) {
                Text(stringResource(R.string.display_changed))
                TextButton(onClick = onRestart, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.display_reconnect))
                }
            }
        }
    }
    val actions: @Composable () -> Unit = { PrimaryActions(value, onStartStop, onAutomatic) }
    Scaffold(
        topBar = { TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.size(40.dp))
                RelayBrand(Modifier.weight(1f))
            }
        }, actions = { IconButton(onClick = { onSettings("output") }) {
            Symbol(R.drawable.symbol_settings, stringResource(R.string.direlay_settings))
        } }) },
        bottomBar = {
            if (!window.inlineActions) Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (window.layout != WindowLayout.COMPACT) Spacer(Modifier.weight(1f))
                    Box(if (window.layout != WindowLayout.COMPACT) Modifier.weight(1f) else Modifier.fillMaxWidth()) { actions() }
                }
            }
        }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // Use this window's available width, including split-screen and freeform resizing.
            val layout = window.layout
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (layout == WindowLayout.COMPACT) {
                    item("hero") { Column(Modifier.animateItem(placementSpec = if (motion) spring() else snap())) { status() } }
                    if (window.inlineActions) item("actions") { actions() }
                    item("setup") { if (setupCompleted) Checklist(value, onSetup) else GuideCard(value, onGuide) }
                    item("browser") { BrowserCard(value, onCopy) }
                    item("carlink") { CarlinkCard(value, onReconnect) }
                    item("mode") { Column(Modifier.animateItem(placementSpec = if (motion) spring() else snap())) { ModeCard(value, onGuide) } }
                    item("network") { Column(Modifier.animateItem(placementSpec = if (motion) spring() else snap())) { NetworkCard(value, onNetwork) } }
                    item("vehicle") { VehicleTile(value, onVehicle, vehicleModifier, vehicleContentModifier) }
                } else item("panes") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (layout == WindowLayout.EXPANDED) 24.dp else 16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            status(); if (setupCompleted) Checklist(value, onSetup) else GuideCard(value, onGuide)
                            BrowserCard(value, onCopy); CarlinkCard(value, onReconnect)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (window.inlineActions) actions()
                            ModeCard(value, onGuide); NetworkCard(value, onNetwork)
                            VehicleTile(value, onVehicle, vehicleModifier, vehicleContentModifier)
                        }
                    }
                }
            }
        }
    }
}
}

@Composable private fun PrimaryActions(value: HomeContent, onStartStop: () -> Unit, onAutomatic: (Boolean) -> Unit) {
    val state = value.presentation
    val haptic = LocalHapticFeedback.current
    val motion = LocalRelayMotion.current
    val transition = updateTransition(state.running, label = "relay action")
    val actionColor by transition.animateColor(transitionSpec = { if (motion) spring() else snap() }, label = "color") {
        if (it) Color(0xFFFFB547) else RelayGreen
    }
    val actionWidth by transition.animateFloat(transitionSpec = { if (motion) spring(dampingRatio = .8f) else snap() }, label = "width") {
        if (it) .9f else 1f
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onStartStop() },
            enabled = !value.busy && (state.canStart || state.running),
            colors = ButtonDefaults.buttonColors(containerColor = actionColor),
            modifier = Modifier.fillMaxWidth(actionWidth).heightIn(min = 56.dp)) {
            AnimatedContent(state.running, transitionSpec = {
                fadeIn(tween(if (motion) 180 else 0)) togetherWith fadeOut(tween(if (motion) 150 else 0))
            }, label = "action") { running ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Symbol(if (running) R.drawable.symbol_stop else R.drawable.symbol_play, null, Modifier.size(24.dp))
                    Text(stringResource(if (running) R.string.ui_stop else R.string.ui_start))
                }
            }
        }
        Toggle(stringResource(R.string.ui_automatic), value.automatic, onAutomatic)
    }
}

@Composable private fun StatusHero(value: HomeContent) {
    val state = value.presentation
    val motion = LocalRelayMotion.current
    val ms = if (motion) 220 else 0
    val color by animateColorAsState(when (state.tone) {
        StatusTone.RUNNING -> RelayGreen
        StatusTone.PROGRESS -> Color(0xFF5CA8FF)
        StatusTone.ATTENTION -> Color(0xFFFFB547)
        StatusTone.ERROR -> Color(0xFFFF5C5C)
        StatusTone.IDLE -> RelayGreen
    }, tween(ms), label = "status color")
    var pulse by remember { mutableFloatStateOf(1f) }
    val connecting = state.headline == R.string.ui_connecting
    LaunchedEffect(connecting, motion) { pulse = 1f }
    val scale = if (connecting && motion) {
        val animation = rememberInfiniteTransition(label = "connecting")
        val amount by animation.animateFloat(1f, 1.1f,
            infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "pulse")
        amount
    } else pulse
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = true
    AnimatedVisibility(visibility, enter = fadeIn(tween(ms)) + expandVertically(tween(ms)),
        exit = fadeOut(tween(ms)) + shrinkVertically(tween(ms))) {
    Card(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, color.copy(alpha = .65f))) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(Modifier.size(64.dp).scale(scale), shape = CircleShape,
                color = color.copy(alpha = .12f), border = BorderStroke(2.dp, color)) {
                Box(contentAlignment = Alignment.Center) {
                    Symbol(when (state.headline) {
                        R.string.ui_running -> R.drawable.symbol_car
                        R.string.ui_connecting -> R.drawable.symbol_sync
                        R.string.ui_waiting -> R.drawable.symbol_phone
                        R.string.ui_ready -> R.drawable.symbol_check
                        R.string.ui_setup_needed -> R.drawable.symbol_settings
                        else -> R.drawable.symbol_error
                    }, stringResource(state.headline), Modifier.size(32.dp))
                }
            }
            AnimatedContent(Triple(state.headline, state.subline, state.sublineArguments), transitionSpec = {
                (fadeIn(tween(ms)) + slideInVertically(tween(ms)) { it / 8 }) togetherWith
                    (fadeOut(tween(ms)) + slideOutVertically(tween(ms)) { -it / 8 })
            }, label = "status") { (headline, subline, arguments) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Heading(stringResource(headline))
                    value.iphoneName?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.ui_iphone_name, it)) }
                    Hint(if (subline == R.string.ui_iphone_pairing) stringResource(subline, value.deviceName) else stringResource(subline, *arguments.toTypedArray()))
                }
            }
        }
    }
    }
}

@Composable private fun Checklist(value: HomeContent, onSetup: (SetupAction) -> Unit) {
    MotionCard(value.presentation.checklist.isNotEmpty()) {
        Heading(stringResource(R.string.ui_setup))
        // Keep identity keys stable as permission and pairing rows disappear.
        SetupAction.entries.forEach { action ->
            val item = value.presentation.checklist.firstOrNull { it.action == action }
            var lastItem by remember(action) { mutableStateOf(item) }
            if (item != null) lastItem = item
            val ms = if (LocalRelayMotion.current) 220 else 0
            AnimatedVisibility(item != null, enter = expandVertically(tween(ms)) + fadeIn(tween(ms)),
                exit = shrinkVertically(tween(ms)) + fadeOut(tween(ms))) {
                lastItem?.let {
                    Column {
                        Text(stringResource(it.text))
                        if (it.action == SetupAction.IDENTITY) Hint(stringResource(R.string.direlay_identity_hint))
                        if (it.text == R.string.iphone_pair) Hint(stringResource(R.string.ui_iphone_pairing, value.deviceName))
                        TextButton(onClick = { onSetup(it.action) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(it.actionLabel)) }
                    }
                }
            }
        }
    }
}

@Composable private fun BrowserCard(value: HomeContent, onCopy: (String) -> Unit) {
    MotionCard(!value.carlink || value.both) {
        Heading(stringResource(R.string.ui_car_screen))
        Text(stringResource(R.string.ui_browser_instruction))
        if (value.addresses.isEmpty()) Hint(stringResource(R.string.ui_address_waiting))
        value.addresses.forEach { address ->
            // The scheme stays in the large address: typed without it, browsers try http:// and fail.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FittingAddress(address.removeSuffix("/"), Modifier.weight(1f))
                IconButton(onClick = { onCopy(address) }) { Symbol(R.drawable.symbol_copy, stringResource(R.string.ui_copy)) }
            }
        }
        if (value.addresses.isNotEmpty()) Hint(stringResource(R.string.ui_browser_https_hint))
        Hint(stringResource(R.string.ui_tls_hint))
    }
}
@Composable private fun FittingAddress(value: String, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        var size by remember(value, maxWidth, density.fontScale) { mutableFloatStateOf(32f) }
        Text(value, fontSize = size.sp, maxLines = 1, softWrap = false, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
            onTextLayout = {
                if (it.hasVisualOverflow && size > 6f) {
                    val measured = (it.getLineRight(0) - it.getLineLeft(0)).coerceAtLeast(1f)
                    size = (size * (it.size.width / measured) * .98f).coerceIn(6f, (size - .5f).coerceAtLeast(6f))
                }
            })
    }
}
@Composable private fun CarlinkCard(value: HomeContent, reconnect: () -> Unit) {
    MotionCard(value.carlink || value.both) {
        Heading(stringResource(R.string.ui_carlink)); Text(stringResource(R.string.ui_carlink_instruction))
        Hint(stringResource(value.carlinkState))
        TextButton(onClick = reconnect, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.direlay_carlink_reconnect)) }
    }
}
/** Shown until the guided setup is finished, in place of the checklist it covers. */
@Composable private fun GuideCard(value: HomeContent, guide: (String?) -> Unit) {
    MotionCard(!value.presentation.running) {
        Heading(stringResource(R.string.setup_home_title))
        Hint(stringResource(R.string.setup_home_hint))
        Button(onClick = { guide(null) }, Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RelayGreen)) { Text(stringResource(R.string.setup_home_action)) }
    }
}
/** The chosen mode; each row opens the guide at the step that changes it. */
@Composable private fun ModeCard(value: HomeContent, guide: (String?) -> Unit) {
    MotionCard {
        Heading(stringResource(R.string.mode_title))
        Column { ModeSummary(value.presentation.mode) { guide(it.key) } }
        OutlinedButton(onClick = { guide(null) }, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.setup_change_mode)) }
    }
}
/** This phone's own network: the Wi-Fi it is on and its IP addresses. */
@Composable private fun NetworkCard(value: HomeContent, network: () -> Unit) {
    MotionCard {
        Heading(stringResource(R.string.network_title))
        Column {
            SettingsRow(stringResource(R.string.network_wifi), value.wifi, R.drawable.symbol_wifi)
            HorizontalDivider()
            SettingsRow(stringResource(R.string.network_addresses), value.ip, R.drawable.symbol_link)
        }
        if (!value.onWifi) TextButton(onClick = network, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.ui_open_wifi)) }
    }
}
@Composable private fun VehicleTile(value: HomeContent, open: () -> Unit, modifier: Modifier, contentModifier: Modifier) {
    val ms = if (LocalRelayMotion.current) 220 else 0
    AnimatedVisibility(value.carlink || value.both || value.vehicle.sample, enter = expandVertically(tween(ms)) + fadeIn(tween(ms)),
        exit = shrinkVertically(tween(ms)) + fadeOut(tween(ms))) {
        Card(onClick = open, modifier = modifier.fillMaxWidth()) {
            Column(contentModifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Symbol(R.drawable.symbol_car, stringResource(R.string.vehicle_title))
                val state = vehicleTileState(value.vehicle)
                Heading(stringResource(if (state == VehicleTileState.OFF) R.string.vehicle_off else R.string.vehicle_title))
                when (state) {
                    VehicleTileState.OFF -> Hint(stringResource(R.string.vehicle_privacy))
                    VehicleTileState.WAITING -> Text(stringResource(R.string.vehicle_waiting))
                    VehicleTileState.DATA -> { val glance = vehicleGlance(value.vehicle.reports)
                        Text(stringResource(R.string.vehicle_glance, glance[0], glance[1], glance[2])) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CarlinkCodeSheet(value: HomeContent) {
    val haptic = LocalHapticFeedback.current
    var deliveredSuccess by remember { mutableIntStateOf(value.pairingSuccess) }
    LaunchedEffect(value.pairingSuccess) {
        if (value.pairingSuccess > deliveredSuccess) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        deliveredSuccess = value.pairingSuccess
    }
    value.pending?.let { request ->
        val machine = remember(request) { PinSheetState(request.confirmation, request::respond) }
        var digits by remember(request) { mutableStateOf("") }
        val shake = remember { Animatable(0f) }
        val motion = LocalRelayMotion.current
        LaunchedEffect(value.pinRejected) {
            if (request.incorrect && motion) {
                kotlinx.coroutines.delay(200)
                shake.animateTo(0f, keyframes {
                durationMillis = 300; -12f at 50; 12f at 100; -8f at 150; 8f at 200; 0f at 300
                })
            }
        }
        ModalBottomSheet(onDismissRequest = { request.cancel(); machine.cancel() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Heading(stringResource(if (request.confirmation) R.string.direlay_carlink_confirm else R.string.ui_pin_title))
                if (request.incorrect) Text(stringResource(R.string.vehicle_wrong_code), color = MaterialTheme.colorScheme.error)
                if (request.confirmation) Text(stringResource(R.string.direlay_carlink_confirm_hint, request.headUnitId))
                else OutlinedTextField(digits, { machine.edit(it); digits = machine.digits },
                    Modifier.fillMaxWidth().offset(x = shake.value.dp), singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 32.sp, fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    label = { Text(stringResource(R.string.direlay_carlink_pin)) })
                if (machine.finished) Hint(stringResource(R.string.ui_connecting))
                Button(onClick = { machine.submit(); digits = "" }, enabled = machine.canSubmit || (!machine.finished && digits.length == 6),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.ui_connect)) }
                TextButton(onClick = { request.cancel(); machine.cancel() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(android.R.string.cancel)) }
            }
        }
    }
}
