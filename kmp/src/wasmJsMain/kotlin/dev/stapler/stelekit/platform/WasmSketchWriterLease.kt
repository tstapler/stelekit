// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** `PlatformFileSystem.reloadSidecarRoot` could not read the sidecar root back (too large or failed): its cache is not trustworthy. */
class SidecarReloadFailed(message: String) : Exception(message)

/**
 * The sketch writer lease of the web build (Task 1.4.7i; D-123, D-142, D-150): a releasable Web Lock
 * `stelekit-sketch-writer:<graphId>`, taken when the first v2 sheet of the ready graph opens for editing (never at startup)
 * and released when [readyGraphId] moves to another graph or to none.
 *
 * A tab that finds the lock held is [SketchWriterLeaseState.HeldElsewhere]; it waits for the lock, and on the takeover calls
 * [reloadSidecarRoot] BEFORE it reports `Held`, so a stale cache can never overwrite a newer file (UX-142). [epoch] grows
 * each time the lease becomes `Held` after the instance has held it or seen it held elsewhere (D-150).
 *
 * Defence in depth: the driver lock `stelekit-sqlite-driver-<graphId>` already keeps a second tab of a persistent graph out
 * at boot (D-149).
 *
 * @param readyGraphId the id of the graph that is ready now (`GraphManager.readyGraph`), null when none is.
 * @param reloadSidecarRoot `PlatformFileSystem.reloadSidecarRoot`: drops idle cached sidecar entries and preloads them again.
 */
class WasmSketchWriterLease(
    private val readyGraphId: StateFlow<String?>,
    private val reloadSidecarRoot: suspend () -> Unit,
) : SketchWriterLease {
    private val logger = Logger("WasmSketchWriterLease")
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> logger.warn("writer lease task failed: ${e.message}") },
    )
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SketchWriterLeaseState.Acquiring)

    override val state: StateFlow<SketchWriterLeaseState> = _state.asStateFlow()

    override var epoch: Long = 0L
        private set

    private var held: WebLock.HeldLock? = null
    private var leaseGraph: String? = null
    private var waiter: Job? = null
    private var releasing: Job? = null
    private var generation = 0
    private var everHeldOrBlocked = false

    init {
        scope.launch { readyGraphId.collect { ready -> if (leaseGraph != null && ready != leaseGraph) release() } }
    }

    override suspend fun acquire(): SketchWriterLeaseState = mutex.withLock {
        if (_state.value == SketchWriterLeaseState.Held) return@withLock SketchWriterLeaseState.Held
        releasing?.join()
        val graph = readyGraphId.value ?: return@withLock _state.value
        if (waiter?.isActive == true && leaseGraph == graph) return@withLock SketchWriterLeaseState.HeldElsewhere
        val gen = generation
        val lock = WebLock.tryAcquireHeld(lockName(graph))
        if (lock == null) {
            leaseGraph = graph
            everHeldOrBlocked = true
            _state.value = SketchWriterLeaseState.HeldElsewhere
            waiter = scope.launch { waitForTakeover(graph, gen) }
        } else {
            becomeHeld(graph, lock, gen)
        }
        _state.value
    }

    override fun release() {
        generation++
        waiter?.cancel()
        waiter = null
        val lock = held
        held = null
        leaseGraph = null
        _state.value = SketchWriterLeaseState.Acquiring
        if (lock != null) releasing = scope.launch(NonCancellable) { lock.release() }
    }

    private suspend fun waitForTakeover(graph: String, gen: Int) {
        val lock = WebLock.acquireHeld(lockName(graph))
        mutex.withLock { becomeHeld(graph, lock, gen) }
    }

    /** Reloads the sidecar root, then reports `Held`; a release that raced the reload gives the lock back instead. */
    private suspend fun becomeHeld(graph: String, lock: WebLock.HeldLock, gen: Int) {
        try {
            reloadSidecarRoot()
        } catch (e: SidecarReloadFailed) {
            // An empty or partial cache would let a reconcile push the database over the real files: stay read-only.
            lock.release()
            logger.warn("sidecar reload failed, the lease stays read-only: ${e.message}")
            if (gen == generation) _state.value = SketchWriterLeaseState.HeldElsewhere
            return
        } catch (e: Throwable) {
            lock.release()
            throw e
        }
        if (gen != generation || readyGraphId.value != graph) {
            lock.release()
            return
        }
        if (everHeldOrBlocked) epoch++
        everHeldOrBlocked = true
        held = lock
        leaseGraph = graph
        waiter = null
        _state.value = SketchWriterLeaseState.Held
    }

    companion object {
        /** The Web Lock name of [graphId]'s sketch writer lease. */
        fun lockName(graphId: String): String = "stelekit-sketch-writer:$graphId"
    }
}

/** The id of the ready graph of [graphManager] as a state, for the lease to release by when the graph changes. */
fun readyGraphIdState(graphManager: GraphManager): StateFlow<String?> =
    graphManager.readyGraph
        .map { it?.id?.value }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Default), SharingStarted.Eagerly, graphManager.readyGraphId?.value)
