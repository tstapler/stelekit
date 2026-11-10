// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException

/** Outcome of [TaskSettingsStore.readResult]; [ReadFailed] means "cannot read", which is not "disabled". */
sealed interface TaskReadResult {
    data class Ok(val config: TaskConfig) : TaskReadResult
    data class ReadFailed(val cause: Throwable) : TaskReadResult
}

/**
 * Typed, versioned, fail-closed store over [Settings], one string per [TaskKey] (ADR-003).
 *
 * Resolution: `TaskConfig = entry.enabled + (overrides ?: kind defaults ?: spec.defaultConfig)`. The
 * first read of a key with neither an entry nor a marker calls [seed] once (lazy legacy migration);
 * an existing entry or marker never calls it. A tombstone is an entry that decodes to disabled, so
 * a removed graph never re-seeds, and an explicit [write] supersedes it.
 */
class TaskSettingsStore(
    private val settings: Settings,
    private val seed: LegacyConfigSeed,
    private val registry: TaskRegistry,
) {
    private val logger = Logger("TaskSettingsStore")

    /** Fail-closed: any read failure resolves to the disabled default. */
    suspend fun read(key: TaskKey): TaskConfig = when (val r = readResult(key)) {
        is TaskReadResult.Ok -> r.config
        is TaskReadResult.ReadFailed -> failClosedConfig(key)
    }

    /**
     * For workers and the status line. A stored value that fails to decode is [TaskReadResult.Ok]
     * (disabled); a settings exception or an unavailable legacy seed is [TaskReadResult.ReadFailed].
     */
    suspend fun readResult(key: TaskKey): TaskReadResult = try {
        val entry = loadEntry(key)
        TaskReadResult.Ok(resolve(key, entry))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("settings read failed for ${TaskKeyEncoding.encode(key)}", e)
        TaskReadResult.ReadFailed(e)
    }

    /** "Using default" is `overrides == null`. Fail-closed to the disabled entry. */
    suspend fun readEntry(key: TaskKey): TaskEntry = try {
        loadEntry(key)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("settings read failed for ${TaskKeyEncoding.encode(key)}", e)
        TaskConfigCodec.DISABLED_ENTRY
    }

    /** Returns false when the value could not be persisted. Supersedes a tombstone. */
    suspend fun write(key: TaskKey, entry: TaskEntry): Boolean =
        put(TaskSettingsKeys.entry(key), TaskConfigCodec.encodeEntry(entry))

    suspend fun tombstone(key: TaskKey): Boolean = put(TaskSettingsKeys.entry(key), TaskConfigCodec.TOMBSTONE)

    suspend fun isTombstoned(key: TaskKey): Boolean = try {
        settings.getString(TaskSettingsKeys.entry(key), "") == TaskConfigCodec.TOMBSTONE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Null when no defaults are stored or they cannot be read. */
    suspend fun readDefaults(kind: TaskKind): TaskDefaults? = try {
        loadDefaults(kind)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun writeDefaults(kind: TaskKind, defaults: TaskDefaults): Boolean =
        put(TaskSettingsKeys.defaults(kind), TaskConfigCodec.encodeDefaults(defaults))

    private fun put(settingsKey: String, value: String): Boolean = try {
        settings.putString(settingsKey, value)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("settings write failed for $settingsKey", e)
        false
    }

    private fun loadDefaults(kind: TaskKind): TaskDefaults? {
        val raw = settings.getString(TaskSettingsKeys.defaults(kind), "")
        return if (raw.isEmpty()) null else TaskConfigCodec.decodeDefaults(raw)
    }

    private suspend fun loadEntry(key: TaskKey): TaskEntry {
        val entryKey = TaskSettingsKeys.entry(key)
        val raw = settings.getString(entryKey, "")
        if (raw.isNotEmpty()) return TaskConfigCodec.decodeEntry(raw) ?: TaskConfigCodec.DISABLED_ENTRY
        val markerKey = TaskSettingsKeys.migrated(key)
        if (settings.containsKey(markerKey)) return TaskConfigCodec.DISABLED_ENTRY
        // A throwing seed propagates before any write, so no marker is recorded and the next read retries.
        val seeded = seed.seed(key)
        val entry = seeded?.let { TaskEntry(it.enabled, TaskOverrides(it.schedule, it.constraints)) }
            ?: TaskConfigCodec.DISABLED_ENTRY
        if (seeded != null) settings.putString(entryKey, TaskConfigCodec.encodeEntry(entry))
        settings.putString(markerKey, "1")
        return entry
    }

    private fun resolve(key: TaskKey, entry: TaskEntry): TaskConfig {
        val base = registry.specFor(key.kind)?.defaultConfig ?: FALLBACK_CONFIG
        val defaults = loadDefaults(key.kind)
        val ov = entry.overrides
        return TaskConfig(
            enabled = entry.enabled,
            schedule = ov?.schedule ?: defaults?.schedule ?: base.schedule,
            constraints = ov?.constraints ?: defaults?.constraints ?: base.constraints,
        )
    }

    private fun failClosedConfig(key: TaskKey): TaskConfig {
        val base = registry.specFor(key.kind)?.defaultConfig ?: FALLBACK_CONFIG
        return base.copy(enabled = false)
    }

    private companion object {
        val FALLBACK_CONFIG = TaskConfig(enabled = false, schedule = Schedule.of(60, 0), constraints = TaskConstraints())
    }
}
