// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18

package org.openxplay.direlay.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import org.openxplay.direlay.R
import org.openxplay.direlay.relay.RelayRuntime

@Composable fun CarlinkDebugPanel(runtime: RelayRuntime, enabled: Boolean) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(emptyList<org.openxplay.direlay.carlinkdebug.CarlinkTraceRecorder.TraceLine>()) }
    var filter by remember { mutableStateOf(0) }
    var capturing by remember { mutableStateOf(false) }
    var bytes by remember { mutableStateOf(0L) }
    var dropped by remember { mutableStateOf(0L) }
    var file by remember { mutableStateOf<java.io.File?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(runtime, enabled) {
        while (true) {
            if (enabled || lines.isNotEmpty()) {
                lines = runtime.carlinkTraceRecorder.snapshot(); dropped = runtime.carlinkTraceRecorder.dropped.get()
                file = runtime.carlinkTraceRecorder.currentFile; failure = runtime.carlinkTraceRecorder.failure
                capturing = runtime.carlinkMediaCapture.capturing; bytes = runtime.carlinkMediaCapture.bytes
            }
            delay(500)
        }
    }
    val types = listOf(null, "Rtsp", "Frame", "Media", "Link", "Error")
    Options(listOf(R.string.carlink_trace_all, R.string.carlink_trace_rtsp, R.string.carlink_trace_frames,
        R.string.carlink_trace_media, R.string.carlink_trace_link, R.string.carlink_trace_errors).map { stringResource(it) }, filter) { filter = it }
    Text(stringResource(R.string.carlink_trace_counters, lines.size, dropped, bytes))
    // The negotiated mode is the one number that says what the head unit was told to decode:
    // its width, height and frame rate, and the decoders the session claims. Without it the
    // panel only shows counters, and a session stuck at 30 fps looks the same as a 60 fps one.
    lines.lastOrNull { it.type == "Negotiation" }?.let { Text(it.json, style = MaterialTheme.typography.bodySmall) }
    lines.lastOrNull { it.type == "Media" }?.let { Text(it.json, style = MaterialTheme.typography.bodySmall) }
    failure?.let { Text(stringResource(R.string.carlink_trace_failure, it)) }
    OutlinedButton(enabled = file != null, onClick = {
        file?.let {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.carlink-trace", it)
            val intent = Intent(Intent.ACTION_SEND).setType("application/x-ndjson").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, null))
        }
    }) { Text(stringResource(R.string.carlink_trace_share)) }
    TextButton(onClick = { runtime.carlinkTraceRecorder.clear(); runtime.carlinkMediaCapture.clear(); lines = emptyList(); file = null }) {
        Text(stringResource(R.string.carlink_trace_clear))
    }
    OutlinedButton(enabled = enabled || capturing, onClick = {
        if (capturing) runtime.carlinkMediaCapture.stop() else runtime.carlinkMediaCapture.start()
        capturing = runtime.carlinkMediaCapture.capturing
    }) { Text(stringResource(if (capturing) R.string.carlink_capture_stop else R.string.carlink_capture_start)) }
    val captureDir = context.getExternalFilesDir("carlink-trace")
    fun newestCapture() = captureDir?.listFiles { f -> f.isFile && f.extension == "ts" }?.maxByOrNull { it.lastModified() }
    OutlinedButton(enabled = !capturing && newestCapture() != null, onClick = {
        newestCapture()?.let { ts ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.carlink-trace", ts)
            val intent = Intent(Intent.ACTION_SEND).setType("video/mp2t").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, null))
        }
    }) { Text(stringResource(R.string.carlink_capture_share)) }
    LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
        items(lines.asReversed().filter { types[filter] == null || it.type == types[filter] }) {
            Text(it.json, style = MaterialTheme.typography.bodySmall)
        }
    }
}
