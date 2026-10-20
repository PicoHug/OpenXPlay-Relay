// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-16
package org.openxplay.direlay.clink

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Shared across link instances: a new handoff must not overtake resource release. A release that
 * failed cannot be retried, so it is logged and dropped; only the attempt whose force close fails
 * is aborted, and no later handoff waits on that release again.
 */
internal class LinkReconnect(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: (CompletableFuture<Unit>, Long) -> Unit = { done, ms -> done.get(ms, TimeUnit.MILLISECONDS); Unit },
) {
    private class Release(val done: CompletableFuture<Unit>, val force: () -> Unit)
    private val pending = mutableListOf<Release>()
    @Synchronized fun releasing(done: CompletableFuture<Unit>, force: () -> Unit) {
        pending.add(Release(done, force))
    }
    fun awaitReleased(diagnostics: ClinkDiagnostics) {
        val deadline = now() + 5_000
        while (true) {
            val release = synchronized(this) {
                pending.removeAll { it.done.isDone && !it.done.isCompletedExceptionally }
                pending.firstOrNull()
            } ?: return
            diagnostics.emit(ClinkStage.CLEANUP_WAIT)
            try { wait(release.done, (deadline - now()).coerceAtLeast(0)) }
            catch (_: TimeoutException) {
                diagnostics.emit(ClinkStage.CLEANUP_FORCE)
                try {
                    release.force()
                    check(release.done.isDone) { "Link cleanup incomplete" }
                } catch (failure: Exception) {
                    drop(release)
                    diagnostics.emit(ClinkFailures.describe(ClinkStage.CLEANUP_FORCE, failure))
                    throw failure
                }
            } catch (failure: Exception) {
                // A failed release surfaces here as ExecutionException; anything else is the waiter's own.
                if (!release.done.isCompletedExceptionally) throw failure
            }
            drop(release)
            cause(release.done)?.let { diagnostics.emit(ClinkFailures.describe(ClinkStage.CLEANUP_WAIT, it)) }
            diagnostics.emit(ClinkStage.CLEANUP_DONE)
        }
    }
    private fun drop(release: Release) {
        synchronized(this) { pending.remove(release) }
    }
    private fun cause(done: CompletableFuture<Unit>): Throwable? =
        if (!done.isCompletedExceptionally) null else try { done.join(); null } catch (error: Exception) { error }
}

/**
 * Runs every cleanup step even after one fails, then settles [released] once [after] completes, so
 * a failed step cannot leave the reconnect barrier waiting. Step failures go to [failed]; never throws.
 */
internal fun settleRelease(released: CompletableFuture<Unit>, steps: List<() -> Unit>, failed: (Throwable) -> Unit,
    after: () -> CompletableFuture<Unit> = { CompletableFuture.completedFuture(Unit) }) {
    var failure: Throwable? = null
    try {
        for (step in steps) try { step() } catch (error: Exception) {
            failed(error)
            if (failure == null) failure = error
        }
    } finally {
        // Whoever registered [after] reports its failure; [released] only waits for it.
        after().whenComplete { _, _ ->
            val error = failure
            if (error == null) released.complete(Unit) else released.completeExceptionally(error)
        }
    }
}

internal class LinkBackoff(private val max: Long = 15_000L) {
    private var delay = 1_000L
    @Synchronized fun next(): Long = delay.also { delay = (delay * 2).coerceAtMost(max) }
    @Synchronized fun succeeded() { delay = 1_000L }
}
