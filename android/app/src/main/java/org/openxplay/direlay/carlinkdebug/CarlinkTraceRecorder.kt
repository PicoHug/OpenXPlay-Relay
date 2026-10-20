// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-18

package org.openxplay.direlay.carlinkdebug

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import org.json.JSONArray
import org.openxplay.stream.clink.trace.*

class CarlinkTraceRecorder(private val directory: File, private val mirror: (String) -> Unit = {},
    private val maxBytes: Long = 16L * 1024 * 1024, private val keepFiles: Int = 8,
    private val ringSize: Int = 5000) : ClinkTrace, AutoCloseable {
    init { require(maxBytes > 0 && keepFiles > 0 && ringSize > 0) }
    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private val wake = Semaphore(0)
    private val accepting = AtomicBoolean(true)
    private val pending = AtomicLong()
    val dropped = AtomicLong()
    private val ring = ArrayDeque<TraceLine>()
    private val ringLock = Any()
    private var writer: java.io.BufferedWriter? = null
    private var size = 0L
    private var part = 0
    private val start = "${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}"
    @Volatile var currentFile: File? = null
        private set
    @Volatile var failure: String? = null
        private set
    @Volatile var session: Long = 0
        private set
    private val worker = Thread({
        while (accepting.get() || queue.isNotEmpty()) {
            wake.tryAcquire(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            wake.drainPermits()
            while (true) {
                val action = queue.poll() ?: break
                try { action() } catch (e: Exception) { failure = e.javaClass.simpleName }
                finally { pending.decrementAndGet() }
            }
        }
        runCatching { writer?.close() }
    }, "carlink-trace-writer").apply { isDaemon = true; start() }
    data class TraceLine(val type: String, val json: String)
    private fun enqueue(action: () -> Unit) {
        if (!accepting.get()) return
        if (pending.incrementAndGet() > 8192) { pending.decrementAndGet(); dropped.incrementAndGet(); return }
        queue.add(action); wake.release()
    }
    override fun record(event: ClinkTraceEvent) {
        if (event.session > 0) session = event.session
        enqueue {
            val line = encode(event)
            val type = event.type
            synchronized(ringLock) {
                if (ring.size == ringSize) ring.removeFirst()
                ring.addLast(TraceLine(type, line))
            }
            // Vehicle identity stays in the trace file/UI, including when the mirror writes to logcat.
            if (event !is ClinkTraceEvent.Link || event.stage !in setOf("ble_advertisement", "ble_reply_name"))
                runCatching { mirror(line.take(1000)) }
            val bytes = line.toByteArray(Charsets.UTF_8).size + 1
            if (writer == null || size + bytes > maxBytes && size > 0) rotate()
            writer!!.apply { write(line); newLine(); flush() }; size += bytes
        }
    }
    private fun rotate() {
        writer?.close(); check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "trace-$start-${(part++).toString().padStart(8, '0')}.jsonl")
        writer = file.bufferedWriter(); currentFile = file; size = 0
        directory.listFiles { f -> f.name.startsWith("trace-") && f.extension == "jsonl" }.orEmpty()
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .filter { it != file }.drop(keepFiles - 1).forEach { it.delete() }
    }
    fun snapshot(): List<TraceLine> = synchronized(ringLock) { ring.toList() }
    fun clear() { enqueue {
        writer?.close(); writer = null; currentFile = null; size = 0
        directory.listFiles { f -> f.name.startsWith("trace-") && f.extension == "jsonl" }.orEmpty().forEach { it.delete() }
        synchronized(ringLock) { ring.clear() }; dropped.set(0)
    } }
    internal fun awaitIdle() { val done = java.util.concurrent.CountDownLatch(1); enqueue { done.countDown() }; check(done.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
    override fun close() { accepting.set(false); wake.release(); worker.join(5000) }

    companion object {
        fun select(enabled: Boolean, recorder: () -> ClinkTrace): ClinkTrace = if (enabled) recorder() else ClinkTrace.NONE
        fun encode(event: ClinkTraceEvent): String {
            val json = JSONObject().put("type", event.type).put("atNanos", event.atNanos).put("session", event.session)
            fun put(name: String, value: Any?) { json.put(name, value ?: JSONObject.NULL) }
            fun map(values: Map<*, *>): JSONObject = JSONObject().also { obj -> values.forEach { (k, v) -> obj.put(k.toString(), v) } }
            when (event) {
                is ClinkTraceEvent.Rtsp -> { put("direction", event.direction); put("startLine", event.startLine); put("headers", map(event.headers)); put("body", event.body) }
                is ClinkTraceEvent.Frame -> {
                    put("direction", event.direction); put("port", event.port); put("service", event.service); put("category", event.category)
                    put("method", event.method); put("kind", event.kind); put("sequence", event.sequence); put("bodySize", event.bodySize)
                    put("fields", JSONObject().also { fields ->
                        if (event.service !in setOf("auth", "cert")) event.fields.forEach { (id, value) -> fields.put(id.toString(), when (value) {
                            is ClinkTraceField.Number -> java.math.BigInteger(value.value.toString())
                            is ClinkTraceField.Bytes -> JSONObject().put("length", value.length).put("preview", value.preview)
                        }) }
                    })
                }
                is ClinkTraceEvent.Socket -> { put("service", event.service); put("event", event.event); put("remoteAddress", event.remoteAddress); put("port", event.port); put("errorClass", event.errorClass) }
                is ClinkTraceEvent.Negotiation -> { put("mode", event.mode); put("decoders", JSONArray(event.decoders)); put("audio", event.audio); put("rtpPort", event.rtpPort); put("display", event.display) }
                is ClinkTraceEvent.Media -> {
                    put("liveFrames", event.liveFrames); put("placeholderFrames", event.placeholderFrames); put("droppedFrames", event.droppedFrames)
                    put("keyframes", event.keyframes); put("videoBytes", event.videoBytes); put("rtpPackets", event.rtpPackets); put("rtpBytes", event.rtpBytes)
                    put("sendStalls", event.sendStalls); put("audioFrames", event.audioFrames); put("queueOverflows", event.queueOverflows)
                }
                is ClinkTraceEvent.Video -> { put("event", event.event); put("source", event.source); put("codec", event.codec); put("width", event.width); put("height", event.height); put("sps", JSONArray(event.sps)); put("pps", JSONArray(event.pps)) }
                is ClinkTraceEvent.Input -> { put("kind", event.kind); put("coordinates", JSONArray(event.coordinates.map { listOf(it.first, it.second) })) }
                is ClinkTraceEvent.Error -> { put("code", event.code); put("exceptionClass", event.exceptionClass); put("location", event.location) }
                is ClinkTraceEvent.Link -> { put("stage", event.stage); put("fields", map(event.fields)) }
            }
            return json.toString()
        }
    }
}
