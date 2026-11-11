// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.MeasurementAnnotation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One queued write; it must read any mutable editor state when it runs, not when it is submitted. */
typealias PersistenceOp = suspend () -> Either<DomainError, Unit>

/**
 * Runs annotation writes strictly in submission order on one consumer, so a slow earlier save can
 * never land after a later one (no lost rows, no resurrected undone rows). A failing or throwing write is
 * reported and the queue keeps draining. Owns its scope; [close] drains what is queued.
 */
class AnnotationPersistenceQueue(
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    private val onThrowable: (Throwable) -> Unit = {},
) {
    private class Entry(val op: PersistenceOp, val onResult: (Either<DomainError, Unit>) -> Unit)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val entries = Channel<Entry>(Channel.UNLIMITED)
    private val _pending = MutableStateFlow(0)
    private val _failure = MutableStateFlow<DomainError?>(null)

    /** Writes submitted but not yet finished. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    /** The most recent write failure, cleared by the next successful write or [clearFailure]. */
    val failure: StateFlow<DomainError?> = _failure.asStateFlow()

    init {
        scope.launch { for (entry in entries) run(entry) }
    }

    /** Enqueue [op]; [onResult] runs on the queue's consumer right after it. False once closed. */
    fun submit(onResult: (Either<DomainError, Unit>) -> Unit = {}, op: PersistenceOp): Boolean {
        _pending.update { it + 1 }
        val sent = entries.trySend(Entry(op, onResult)).isSuccess
        if (!sent) _pending.update { it - 1 }
        return sent
    }

    /** Suspends until every submitted write has finished. */
    suspend fun awaitIdle() {
        _pending.first { it == 0 }
    }

    fun clearFailure() {
        _failure.value = null
    }

    /** Stop accepting writes; already-queued ones still run. */
    fun close() {
        entries.close()
    }

    /** Drop queued writes and stop now (the owning database is going away). */
    fun cancel() {
        entries.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        _pending.value = 0
    }

    private suspend fun run(entry: Entry) {
        try {
            val result = entry.op()
            result.fold({ err -> _failure.value = err }, { _failure.value = null })
            entry.onResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Not turned into a Left: the owner decides (the editor surfaces it as error state).
            _failure.value = DomainError.DatabaseError.WriteFailed(t.message ?: t::class.simpleName ?: "unknown")
            onThrowable(t)
        } finally {
            _pending.update { it - 1 }
        }
    }
}

/**
 * Rows to show once the initial load completes: persisted rows this session has not touched (added,
 * deleted or undone) and that are not already shown, followed by the rows added this session. Keeps a late load from dropping
 * persisted rows (and from letting a later bulk save delete them).
 */
fun mergeInitialLoad(
    persisted: List<MeasurementAnnotation>,
    local: List<MeasurementAnnotation>,
    touchedUuids: Set<String>,
): List<MeasurementAnnotation> {
    val localUuids = local.mapTo(HashSet()) { it.uuid }
    return persisted.filter { it.uuid !in localUuids && it.uuid !in touchedUuids } + local
}
