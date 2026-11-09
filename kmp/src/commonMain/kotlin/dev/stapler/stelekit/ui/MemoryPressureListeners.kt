// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Severity of an OS trim-memory signal, ordered from mildest to most severe. */
enum class MemoryPressureLevel {
    Moderate,
    RunningLow,
    RunningCritical,
    Complete;

    companion object {
        // Android Context.TRIM_MEMORY_* values; kept here so the mapping is pure and testable.
        private const val RUNNING_LOW = 10
        private const val RUNNING_CRITICAL = 15
        private const val COMPLETE = 80

        private const val UI_HIDDEN = 20

        /**
         * Maps an Android `onTrimMemory` int. RUNNING_MODERATE (5) and the UI_HIDDEN..MODERATE (20..79)
         * levels are [Moderate]; the running levels 10 and 15 map to [RunningLow] and [RunningCritical].
         */
        fun fromAndroidTrimLevel(level: Int): MemoryPressureLevel = when {
            level >= COMPLETE -> Complete
            level >= UI_HIDDEN -> Moderate
            level >= RUNNING_CRITICAL -> RunningCritical
            level >= RUNNING_LOW -> RunningLow
            else -> Moderate
        }
    }
}

/**
 * Fan-out for the OS memory-pressure signal. The host's `onTrimMemory` hook is a single slot that
 * reaches [StelekitViewModel.onMemoryPressure]; this registry lets other stacks (the asset
 * pipeline) react without adding a second slot.
 */
class MemoryPressureListeners {
    private val logger = Logger("MemoryPressureListeners")
    // Copy-on-write so notifyListeners iterates a stable snapshot while listeners (un)register.
    private val listeners = MutableStateFlow<List<(MemoryPressureLevel) -> Unit>>(emptyList())

    /** Returns a handle that removes [listener]; call it when the owner leaves composition. */
    fun register(listener: () -> Unit): () -> Unit = registerLevelListener { listener() }

    /** Like [register], but the listener receives the typed [MemoryPressureLevel]. */
    fun registerLevelListener(listener: (MemoryPressureLevel) -> Unit): () -> Unit {
        listeners.update { it + listener }
        return { listeners.update { current -> current - listener } }
    }

    /** Invokes every listener; one that throws does not stop the others. No-arg callers mean [MemoryPressureLevel.Moderate]. */
    fun notifyListeners(level: MemoryPressureLevel = MemoryPressureLevel.Moderate) {
        for (listener in listeners.value) {
            try {
                listener(level)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("Memory-pressure listener failed: ${e.message}")
            }
        }
    }
}
