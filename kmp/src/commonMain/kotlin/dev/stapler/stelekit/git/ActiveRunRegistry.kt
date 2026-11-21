// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-process record of which canonical owner-record paths have a run in progress, plus the in-process mutex that
 * keeps two recoverers of one JVM away from `FileChannel.tryLock` (ADR-001 decision 12, steps 0 and 3).
 * One instance per process, shared by every claimer. The keys are canonical record paths ([RepoOpGate.keyFor] is a
 * different, hashed key and is not interchangeable).
 */
class ActiveRunRegistry {
    private class Run(val origin: SyncOrigin?)

    private val runs = MutableStateFlow<Map<String, Run>>(emptyMap())
    private val recovering = MutableStateFlow<Set<String>>(emptySet())

    fun isActive(key: String): Boolean = key in runs.value

    /** Origin of the run registered for [key] (null when it has none or its origin is unknown). */
    fun originOf(key: String): SyncOrigin? = runs.value[key]?.origin

    /** Registers a run; false when [key] already has one. */
    fun register(key: String, origin: SyncOrigin?): Boolean {
        val run = Run(origin)
        while (true) {
            val current = runs.value
            if (key in current) return false
            if (runs.compareAndSet(current, current + (key to run))) return true
        }
    }

    fun unregister(key: String) {
        while (true) {
            val current = runs.value
            if (key !in current || runs.compareAndSet(current, current - key)) return
        }
    }

    /** Non-blocking in-process mutex for the recovery lock of [key]; pair with [exitRecovery] in a `finally`. */
    fun tryEnterRecovery(key: String): Boolean {
        while (true) {
            val current = recovering.value
            if (key in current) return false
            if (recovering.compareAndSet(current, current + key)) return true
        }
    }

    fun exitRecovery(key: String) {
        while (true) {
            val current = recovering.value
            if (key !in current || recovering.compareAndSet(current, current - key)) return
        }
    }
}
