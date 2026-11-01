// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Fan-out for the OS memory-pressure signal. The host's `onTrimMemory` hook is a single slot that
 * reaches [StelekitViewModel.onMemoryPressure]; this registry lets other stacks (the asset
 * pipeline) react without adding a second slot.
 */
class MemoryPressureListeners {
    private val logger = Logger("MemoryPressureListeners")
    // Copy-on-write so notifyListeners iterates a stable snapshot while listeners (un)register.
    private val listeners = MutableStateFlow<List<() -> Unit>>(emptyList())

    /** Returns a handle that removes [listener]; call it when the owner leaves composition. */
    fun register(listener: () -> Unit): () -> Unit {
        listeners.update { it + listener }
        return { listeners.update { current -> current - listener } }
    }

    /** Invokes every listener; one that throws does not stop the others. */
    fun notifyListeners() {
        for (listener in listeners.value) {
            try {
                listener()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("Memory-pressure listener failed: ${e.message}")
            }
        }
    }
}
