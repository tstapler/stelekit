// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** Open identity of a schedulable task type. Charset excludes `_` and `.` so encoded keys stay injective. */
@kotlin.jvm.JvmInline
value class TaskKind(val id: String) {
    init {
        require(id.matches(KIND_PATTERN)) { "TaskKind must match [a-z0-9-]+: '$id'" }
    }

    private companion object {
        val KIND_PATTERN = Regex("[a-z0-9-]+")
    }
}

/** Owner of a task instance: a graph id, or `""` for app-wide. Per-kind defaults are not a scope. */
@kotlin.jvm.JvmInline
value class TaskScope(val value: String)

/** Identity of one scheduled instance; source of its work name, settings key and run-log key. */
data class TaskKey(val kind: TaskKind, val scope: TaskScope) {
    /**
     * Platform work name. Legacy kinds keep their pre-existing names (ADR-005); every other
     * kind hashes the scope unconditionally so a raw scope can never alias another key's name.
     */
    fun workName(): String = when (kind.id) {
        GIT_SYNC -> "stelekit_git_sync_${scope.value}"
        MODEL_DOWNLOAD -> LEGACY_MODEL_DOWNLOAD_WORK_NAME
        else -> "stelekit_task_${kind.id}_${stableHex(scope.value)}"
    }

    private companion object {
        const val GIT_SYNC = "git-sync"
        const val MODEL_DOWNLOAD = "model-download"

        // Mirrors androidMain DepthModelDownloader.UNIQUE_WORK_NAME; commonMain cannot reference it.
        const val LEGACY_MODEL_DOWNLOAD_WORK_NAME = "depth-model-download"
    }
}

private const val FNV_OFFSET_BASIS = -3750763034362895579L // 0xcbf29ce484222325
private const val FNV_PRIME = 1099511628211L // 0x100000001b3

/** 64-bit FNV-1a over the UTF-8 bytes of [s], as 16 lowercase hex digits. Stable across processes. */
fun stableHex(s: String): String {
    var hash = FNV_OFFSET_BASIS
    for (b in s.encodeToByteArray()) {
        hash = (hash xor (b.toLong() and 0xff)) * FNV_PRIME
    }
    return hash.toULong().toString(16).padStart(16, '0')
}
