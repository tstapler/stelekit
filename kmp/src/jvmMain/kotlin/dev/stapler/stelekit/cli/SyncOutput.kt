package dev.stapler.stelekit.cli

import kotlinx.serialization.json.Json

class SyncOutput(private val jsonMode: Boolean) {
    private val json = Json { prettyPrint = false }

    fun info(message: String) {
        if (!jsonMode) println("[stelekit-sync] $message")
    }

    fun error(message: String) {
        System.err.println("[stelekit-sync] ERROR: $message")
    }

    fun result(result: SyncResult) {
        if (jsonMode) println(json.encodeToString(SyncResult.serializer(), result))
    }
}
