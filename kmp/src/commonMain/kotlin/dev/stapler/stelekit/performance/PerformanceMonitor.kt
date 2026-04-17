package dev.stapler.stelekit.performance

import dev.stapler.stelekit.util.PlatformTime
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object PerformanceMonitor {
    private val logger = Logger("Performance")
    // Limit stored events to avoid memory leaks in long sessions
    private const val MAX_EVENTS = 1000
    
    private val _events = MutableStateFlow<List<TraceEvent>>(emptyList())
    val events: StateFlow<List<TraceEvent>> = _events.asStateFlow()

    private val _activeTraces = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun startTrace(name: String): String {
        val now = PlatformTime.now()
        // Simple map for active traces. Nested traces with same name will overwrite start time (limitation accepted for MVP)
        _activeTraces.update { it + (name to now) }
        return name
    }

    fun endTrace(name: String, logThresholdMs: Long = 100) {
        val endTime = PlatformTime.now()
        var startTime: Long? = null
        
        _activeTraces.update { 
            startTime = it[name]
            it - name 
        }

        if (startTime != null) {
            // Duration is in nanoseconds (JVM) or equivalent high-res unit
            val durationNs = endTime - startTime
            // Convert to ms for UI and logging
            val durationMs = durationNs / 1_000_000

            val event = TraceEvent(
                name = name,
                startTime = startTime,
                duration = durationMs,
                type = "trace",
                thread = PlatformTime.currentThreadName()
            )
            addEvent(event)
            
            if (durationMs > logThresholdMs) {
                logger.warn("Slow operation detected: $name took ${durationMs}ms")
            }
        }
    }

    fun mark(name: String) {
        val now = PlatformTime.now()
        val event = TraceEvent(
            name = name,
            startTime = now,
            duration = 0,
            type = "mark",
            thread = PlatformTime.currentThreadName()
        )
        addEvent(event)
    }
    
    fun clear() {
        _events.value = emptyList()
        _activeTraces.value = emptyMap()
    }

    private fun addEvent(event: TraceEvent) {
        _events.update { current ->
            if (current.size >= MAX_EVENTS) {
                current.drop(1) + event
            } else {
                current + event
            }
        }
    }
}

/**
 * Performance trace event
 */
data class TraceEvent(
    val name: String,
    val startTime: Long,
    val duration: Long,
    val type: String,
    val thread: String
)
