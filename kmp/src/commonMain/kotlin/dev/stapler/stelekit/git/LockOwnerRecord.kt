// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Random id minted once per process start; identifies "this process" in a [LockOwnerRecord]. */
@JvmInline
@Serializable
value class InstanceId(val value: String)

/**
 * Content of `.git/stelekit-sync.owner`. Evidence of who last claimed the repository, not a lock;
 * its file mtime doubles as the owner's heartbeat (ADR-001 decision 12).
 */
@Serializable
data class LockOwnerRecord(
    val instanceId: InstanceId,
    val pid: Long,
    /** Linux/Android: `/proc/<pid>/stat` field 22 ticks. macOS/Windows: process start epoch ms. Null when unreadable. */
    val processStartTicks: Long?,
    /** `readlink /proc/self/ns/pid`, null where unreadable. */
    val pidNs: String?,
    val host: String,
    val bootId: String?,
    val op: String,
    val origin: String,
    val startedAtMs: Long,
    /** Stable per-install id; hostnames collide and change, so liveness proof on namespace-less platforms needs this. */
    val machineId: String? = null,
) {
    fun encode(): String = CODEC.encodeToString(serializer(), this)

    companion object {
        // ignoreUnknownKeys: a newer build may add fields; an older reader must still see the record as live.
        private val CODEC = Json { ignoreUnknownKeys = true }

        /** Empty, partial or unparseable text is [RecordDecode.Torn], never "no record" (a half-written record is still a live owner). */
        fun decode(text: String): RecordDecode {
            if (text.isBlank()) return RecordDecode.Torn
            return try {
                RecordDecode.Valid(CODEC.decodeFromString(serializer(), text))
            } catch (_: SerializationException) {
                RecordDecode.Torn
            } catch (_: IllegalArgumentException) {
                RecordDecode.Torn
            }
        }
    }
}

sealed interface RecordDecode {
    data class Valid(val record: LockOwnerRecord) : RecordDecode
    data object Torn : RecordDecode
}
