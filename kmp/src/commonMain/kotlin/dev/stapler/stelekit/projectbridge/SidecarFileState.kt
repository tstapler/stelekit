// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Why a sidecar file cannot be trusted. A corrupt file is kept, never overwritten (Story 2.5). */
enum class CorruptReason { ConflictMarkers, Truncated, InvalidJson, InvalidUtf8, NewerSchema }

/** What one sidecar read found. Only [Absent] means "no sidecar"; everything unreadable is [Corrupt]. */
sealed interface SidecarFileState {
    data object Absent : SidecarFileState
    data class Valid(val bytes: ByteArray) : SidecarFileState {
        override fun equals(other: Any?) = other is Valid && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
    data class Corrupt(val reason: CorruptReason) : SidecarFileState
}

object SidecarClassifier {
    const val SUPPORTED_SCHEMA = 1L
    private val parser = Json

    /** Classifies raw bytes of a JSON sidecar file; [bytes] null means the file does not exist. */
    fun classifyJson(bytes: ByteArray?): SidecarFileState {
        if (bytes == null) return SidecarFileState.Absent
        val text = decode(bytes) ?: return SidecarFileState.Corrupt(CorruptReason.InvalidUtf8)
        if (hasConflictMarkers(text)) return SidecarFileState.Corrupt(CorruptReason.ConflictMarkers)
        if (text.isBlank()) return SidecarFileState.Corrupt(CorruptReason.Truncated)
        val obj = try {
            parser.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            return SidecarFileState.Corrupt(if (text.trimEnd().endsWith("}")) CorruptReason.InvalidJson else CorruptReason.Truncated)
        } ?: return SidecarFileState.Corrupt(CorruptReason.InvalidJson)
        val schema = obj["schema"]?.jsonPrimitive?.longOrNull ?: return SidecarFileState.Corrupt(CorruptReason.InvalidJson)
        if (schema > SUPPORTED_SCHEMA) return SidecarFileState.Corrupt(CorruptReason.NewerSchema)
        return SidecarFileState.Valid(bytes)
    }

    /** Classifies a text snapshot (`sources/yaml-*.yaml`): UTF-8 and conflict markers only. */
    fun classifyText(bytes: ByteArray?): SidecarFileState {
        if (bytes == null) return SidecarFileState.Absent
        val text = decode(bytes) ?: return SidecarFileState.Corrupt(CorruptReason.InvalidUtf8)
        return if (hasConflictMarkers(text)) SidecarFileState.Corrupt(CorruptReason.ConflictMarkers) else SidecarFileState.Valid(bytes)
    }

    private fun decode(bytes: ByteArray): String? =
        try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { null }

    /** Git leaves `<<<<<<< `, a bare `=======` and `>>>>>>> ` at line starts; any of them makes the file untrusted. */
    fun hasConflictMarkers(text: String): Boolean = text.lineSequence().any {
        it.startsWith("<<<<<<< ") || it.startsWith(">>>>>>> ") || it == "======="
    }
}
