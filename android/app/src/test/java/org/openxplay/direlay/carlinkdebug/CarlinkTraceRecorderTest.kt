// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18

package org.openxplay.direlay.carlinkdebug

import android.app.Application
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.openxplay.direlay.clink.*
import org.openxplay.stream.clink.trace.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, application = Application::class)
class CarlinkTraceRecorderTest {
    @Test fun callbacksDoNotWaitForWriterAndNumericFieldsStayNumeric() {
        val directory = Files.createTempDirectory("carlink-async").toFile()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val recorder = CarlinkTraceRecorder(directory, { entered.countDown(); release.await() })
        try {
            val frame = ClinkTraceEvent.Frame(12, 3, "RX", 123, "control", 1, 2, 0, 9, 8,
                mapOf(1 to ClinkTraceField.Number(42uL), 2 to ClinkTraceField.Bytes(100, "preview")))
            recorder.record(frame)
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val callback = java.util.concurrent.Executors.newSingleThreadExecutor()
            try { callback.submit { recorder.record(frame) }.get(1, java.util.concurrent.TimeUnit.SECONDS) }
            finally { callback.shutdownNow() }
            release.countDown(); recorder.awaitIdle()
            val fields = JSONObject(recorder.snapshot().first().json).getJSONObject("fields")
            assertTrue(fields.get("1") is Number)
            assertEquals(42, fields.getInt("1")); assertEquals(100, fields.getJSONObject("2").getInt("length"))
        } finally { release.countDown(); recorder.close(); directory.deleteRecursively() }
    }

    @Test fun ringRotationJsonAndClear() {
        val directory = Files.createTempDirectory("carlink-trace").toFile()
        val mirror = mutableListOf<String>()
        val recorder = CarlinkTraceRecorder(directory, { mirror += it }, maxBytes = 300, keepFiles = 8)
        try {
            repeat(5100) { i -> recorder.record(ClinkTraceEvent.Link(i.toLong(), 7, "stage", mapOf("index" to i.toString())))
                if (i % 100 == 0) recorder.awaitIdle()
            }
            recorder.awaitIdle()
            assertEquals(5000, recorder.snapshot().size)
            assertEquals(100L, JSONObject(recorder.snapshot().first().json).getLong("atNanos"))
            assertEquals(8, directory.listFiles()!!.size)
            assertEquals(5100, mirror.size)
            directory.listFiles()!!.forEach { file ->
                assertTrue(file.length() <= 300)
                file.readLines().forEach { line ->
                    val json = JSONObject(line)
                    assertEquals("Link", json.getString("type")); assertEquals(7, json.getInt("session"))
                    assertTrue(json.has("atNanos")); assertTrue(json.has("fields"))
                }
            }
            assertTrue(recorder.currentFile!!.exists())
            recorder.clear(); recorder.awaitIdle()
            assertTrue(recorder.snapshot().isEmpty()); assertEquals(0, directory.listFiles()!!.size)
        } finally { recorder.close(); directory.deleteRecursively() }
    }
    @Test fun vehicleIdentificationIsTraceOnly() {
        val directory = Files.createTempDirectory("carlink-identification").toFile()
        val mirrored = mutableListOf<String>()
        val recorder = CarlinkTraceRecorder(directory, { mirrored += it })
        val delegated = mutableListOf<ClinkDiagnostic>()
        try {
            val diagnostics = TracingClinkDiagnostics(ClinkDiagnostics { delegated += it }, recorder)
            diagnostics.traceLink("ble_advertisement", mapOf("major" to "1", "minor" to "9", "serial" to "7",
                "carId" to "001122", "modelId" to "3344", "vendorData" to "5566", "name" to "Car"))
            diagnostics.traceLink("ble_reply_name", mapOf("name" to "Head Unit"))
            recorder.awaitIdle()
            assertTrue(delegated.isEmpty()); assertTrue(mirrored.isEmpty())
            val content = recorder.currentFile!!.readText()
            assertTrue(content.contains("ble_advertisement")); assertTrue(content.contains("Head Unit"))
            assertFalse(content.contains("psk"))
            TracingClinkDiagnostics(ClinkDiagnostics { error("trace must bypass delegate") }, ClinkTrace.NONE)
                .traceLink("ble_reply_name", mapOf("name" to "Disabled"))
        } finally { recorder.close(); directory.deleteRecursively() }
    }
    @Test fun redactionAndDisabledPort() {
        val directory = Files.createTempDirectory("carlink-redaction").toFile()
        val recorder = CarlinkTraceRecorder(directory)
        try {
            val diagnostic = TracingClinkDiagnostics(ClinkDiagnostics.NONE, recorder)
            diagnostic.emit(ClinkDiagnostic.Handoff("Car WiFi", "wifi-secret".length))
            diagnostic.emit(ClinkStage.AUTH_PIN)
            for (service in listOf("auth", "cert")) recorder.record(ClinkTraceEvent.Frame(1, 2, "RX", 1, service,
                1, 1, 1, 1, 42, mapOf(1 to ClinkTraceField.Bytes(42, "012345 private-key accessory-identity cert-body"))))
            assertSame(ClinkTrace.NONE, CarlinkTraceRecorder.select(false) { error("disabled recorder must not be initialized") })
            CarlinkTraceRecorder.select(false) { error("disabled recorder must not be initialized") }.record(ClinkTraceEvent.Link(0, 0, "disabled"))
            recorder.awaitIdle()
            val content = recorder.currentFile!!.readText()
            for (secret in listOf("wifi-secret", "012345", "private-key", "accessory-identity", "cert-body", "disabled")) assertFalse(content.contains(secret))
            assertTrue(content.contains("Car WiFi")); assertTrue(content.contains("passphraseLength"))
            assertTrue(content.contains("bodySize"))
        } finally { recorder.close(); directory.deleteRecursively() }
    }
    @Test fun mediaCaptureStopsAtDeadlineWithoutPackets() {
        val directory = Files.createTempDirectory("carlink-deadline").toFile()
        val capture = CarlinkMediaCapture(directory, durationNs = 20_000_000)
        try {
            capture.start()
            val deadline = System.nanoTime() + 2_000_000_000
            while (capture.capturing && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(capture.capturing)
            capture.close()
            assertEquals(0L, directory.listFiles()!!.single().length())
        } finally { capture.close(); directory.deleteRecursively() }
    }

    @Test fun mediaCaptureStripsRtpAndStopsAtLimit() {
        val directory = Files.createTempDirectory("carlink-media").toFile()
        val capture = CarlinkMediaCapture(directory, maxBytes = 188)
        try {
            capture.start()
            val packet = ByteArray(200) { if (it < 12) 0 else 0x47 }
            capture.rtp(packet, 0, packet.size)
            val deadline = System.nanoTime() + 2_000_000_000
            while (capture.capturing && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(capture.capturing); capture.close()
            assertEquals(188, directory.listFiles()!!.single().length().toInt())
            assertTrue(directory.listFiles()!!.single().readBytes().all { it == 0x47.toByte() })
        } finally { capture.close(); directory.deleteRecursively() }
    }
}
