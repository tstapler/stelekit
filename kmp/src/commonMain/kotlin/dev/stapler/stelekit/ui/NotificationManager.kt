package dev.stapler.stelekit.ui

import dev.stapler.stelekit.model.Notification
import dev.stapler.stelekit.model.NotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

class NotificationManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _activeNotifications = MutableStateFlow<List<Notification>>(emptyList())
    val activeNotifications: StateFlow<List<Notification>> = _activeNotifications.asStateFlow()

    private val _history = MutableStateFlow<List<Notification>>(emptyList())
    val history: StateFlow<List<Notification>> = _history.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun show(
        content: String, 
        type: NotificationType = NotificationType.INFO, 
        timeout: Long? = 3000
    ) {
        val id = generateUuid()
        val notification = Notification(
            id = id,
            content = content,
            type = type,
            timestamp = Clock.System.now(),
            timeout = timeout
        )

        // Add to active
        _activeNotifications.value = _activeNotifications.value + notification

        // Add to history (limit 50)
        val currentHistory = _history.value.toMutableList()
        currentHistory.add(0, notification)
        if (currentHistory.size > 50) {
            currentHistory.removeAt(currentHistory.lastIndex)
        }
        _history.value = currentHistory

        // Handle auto-clear
        if (timeout != null && timeout > 0) {
            val job = scope.launch {
                delay(timeout)
                clear(id)
            }
            jobs[id] = job
        }
    }

    fun clear(id: String) {
        _activeNotifications.value = _activeNotifications.value.filter { it.id != id }
        jobs[id]?.cancel()
        jobs.remove(id)
    }

    fun clearAll() {
        _activeNotifications.value = emptyList()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
    
    // Helper for UUID generation since java.util.UUID is not commonMain
    private fun generateUuid(): String {
        val chars = "0123456789abcdef"
        fun randomHex(length: Int) = (1..length).map { chars.random() }.joinToString("")
        return "${randomHex(8)}-${randomHex(4)}-${randomHex(4)}-${randomHex(4)}-${randomHex(12)}"
    }
}
