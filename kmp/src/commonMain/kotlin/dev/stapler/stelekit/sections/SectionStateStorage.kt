package dev.stapler.stelekit.sections

import dev.stapler.stelekit.platform.PlatformSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val KEY_SECTION_STATES = "section_states"

fun PlatformSettings.getSectionStates(): Map<String, SectionState> {
    val raw = getString(KEY_SECTION_STATES, "")
    if (raw.isBlank()) return emptyMap()
    return try {
        val obj = Json.decodeFromString(JsonObject.serializer(), raw)
        buildMap {
            for ((id, value) in obj) {
                val state = SectionState.entries.find {
                    it.name.equals(value.jsonPrimitive.content, ignoreCase = true)
                }
                if (state != null) put(id, state)
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}

fun PlatformSettings.putSectionStates(states: Map<String, SectionState>) {
    val obj = buildJsonObject {
        for ((id, state) in states) put(id, JsonPrimitive(state.name.lowercase()))
    }
    putString(KEY_SECTION_STATES, obj.toString())
}
