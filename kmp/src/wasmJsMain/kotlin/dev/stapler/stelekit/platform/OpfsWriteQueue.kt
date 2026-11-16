// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * The one ordered queue of every OPFS operation (write, delete, move, copy-verify, mirror write), per path set
 * (Task 1.4.7f, D-119, D-120).
 *
 * [enqueue] starts an operation only after the tails of ALL the paths it names have settled (a failed predecessor does
 * not block it, its failure is kept for [awaitDurable]) and makes the new operation the tail of each named path. So a
 * replace of `dest` by `tmp` runs after the write of `tmp` and after any earlier write to `dest`, and the host
 * write-through, which awaits [tailOf], waits for every earlier operation too. An operation removes its tail entry only
 * while it is still the tail.
 *
 * Single-threaded JS: no lock is needed. [onPendingChanged] receives the number of paths with an unsettled operation
 * after every change, which the page's `beforeunload` guard reads.
 */
class OpfsWriteQueue(
    private val scope: CoroutineScope,
    private val onPendingChanged: (pendingPaths: Int) -> Unit = {},
) {
    private val tails = LinkedHashMap<String, Deferred<Unit>>()
    private val failures = LinkedHashMap<String, DomainError>()

    /** Paths with an operation that has not settled. */
    val pendingPaths: Int get() = tails.size

    /** The last operation queued for [path], or null when everything queued for it has settled. */
    fun tailOf(path: String): Deferred<Unit>? = tails[path]

    /**
     * Queues [op] behind the tails of [paths]. [op] reports its own failure as a typed `Left`; an exception other than
     * cancellation is mapped to `WriteFailed` of the first path. The returned deferred completes when [op] has run.
     */
    fun enqueue(paths: List<String>, op: suspend () -> Either<DomainError, Unit>): Deferred<Unit> {
        require(paths.isNotEmpty()) { "an OPFS operation must name at least one path" }
        val predecessors = paths.distinct().mapNotNull { tails[it] }
        lateinit var self: Deferred<Unit>
        self = scope.async {
            predecessors.forEach { awaitQuietly(it) }
            val outcome = runGuarded(paths.first(), op)
            settle(paths.distinct(), self, outcome)
        }
        paths.distinct().forEach { tails[it] = self }
        onPendingChanged(tails.size)
        return self
    }

    /**
     * Completes when every operation queued for [path] so far has settled. `Left` when the latest operation that
     * touched [path] failed (a quota rejection, a missing `move()`, a read-back mismatch); a later success clears it.
     */
    suspend fun awaitDurable(path: String): Either<DomainError, Unit> {
        tails[path]?.let { awaitQuietly(it) }
        return failures[path]?.left() ?: Unit.right()
    }

    private fun settle(paths: List<String>, self: Deferred<Unit>, outcome: Either<DomainError, Unit>) {
        for (path in paths) {
            outcome.fold({ failures[path] = it }, { failures.remove(path) })
            if (tails[path] === self) tails.remove(path)
        }
        onPendingChanged(tails.size)
    }

    private suspend fun runGuarded(path: String, op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> = try {
        op()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        DomainError.FileSystemError.WriteFailed(path, e.message ?: "OPFS operation failed").left()
    }

    private suspend fun awaitQuietly(operation: Deferred<Unit>) {
        try {
            operation.await()
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            // A failed predecessor never blocks its successors; its failure is already kept per path.
        }
    }
}
