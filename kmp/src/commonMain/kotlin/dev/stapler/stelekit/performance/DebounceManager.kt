package dev.stapler.stelekit.performance

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages debounced execution of tasks identified by a key.
 * Used to throttle high-frequency updates like disk writes.
 */
class DebounceManager(
    private val scope: CoroutineScope,
    private val delayMs: Long = 300L
) {
    private val jobs = mutableMapOf<String, Job>()
    private val pendingActions = mutableMapOf<String, suspend () -> Unit>()
    private val mutex = Mutex()

    fun debounce(key: String, action: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                jobs[key]?.cancel()
                pendingActions[key] = action
                jobs[key] = scope.launch {
                    delay(delayMs)
                    mutex.withLock {
                        pendingActions.remove(key)
                        jobs.remove(key)
                    }
                    action()
                }
            }
        }
    }

    /**
     * Cancel all pending debounced tasks without executing them.
     */
    suspend fun cancelAll() {
        mutex.withLock {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            pendingActions.clear()
        }
    }

    /**
     * Execute all pending debounced tasks immediately, bypassing the delay.
     * Use on app shutdown to ensure no data is lost.
     */
    suspend fun flushAll() {
        val actions: List<suspend () -> Unit>
        mutex.withLock {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            actions = pendingActions.values.toList()
            pendingActions.clear()
        }
        for (action in actions) {
            try {
                action()
            } catch (_: Exception) {
                // Best-effort on shutdown
            }
        }
    }
}
