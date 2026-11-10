// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(ExperimentalAtomicApi::class)

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What a `pause` took from the live work: the config `resume` restores. */
data class PausedSnapshot(val config: TaskConfig)

/**
 * Record, per key, of the last [TaskConfig] an adapter `apply`ed, plus a [PausedSnapshot] taken at
 * `pause` time (ADR-001, ADR-003). Backed by an explicit [Settings] (never a hidden global) so a
 * no-op `PlatformSettings` cannot silently swallow it: snapshots also live in a process-static
 * in-memory copy, written first. Every method is guarded and never throws except cancellation.
 *
 * Resume precedence (adapters implement it, the ledger supplies the pieces): an explicit store
 * entry/tombstone saying disabled wins; else [pausedSnapshot]; else [lastApplied]; else the store.
 */
class AppliedConfigLedger(private val settings: Settings) {
    private val logger = Logger("AppliedConfigLedger")

    /** Persists [config] as the last applied config for [key]. False when it could not be persisted. */
    fun recordApplied(key: TaskKey, config: TaskConfig): Boolean =
        put(TaskSettingsKeys.applied(key), encode(config))

    fun lastApplied(key: TaskKey): TaskConfig? = get(TaskSettingsKeys.applied(key))

    /**
     * Records what `pause` is about to cancel. Writes the process-static copy first, then settings;
     * a settings failure is swallowed so a snapshot problem can never block the cancel.
     */
    fun snapshotPaused(key: TaskKey, config: TaskConfig) {
        val settingsKey = pausedKey(key)
        updateStatic { it + (settingsKey to config) }
        put(settingsKey, encode(config))
    }

    /** In-memory copy first, then settings; null when neither holds one. */
    fun pausedSnapshot(key: TaskKey): PausedSnapshot? {
        val settingsKey = pausedKey(key)
        val config = inMemory.load()[settingsKey] ?: get(settingsKey)
        return config?.let { PausedSnapshot(it) }
    }

    fun clearPausedSnapshot(key: TaskKey) {
        val settingsKey = pausedKey(key)
        updateStatic { it - settingsKey }
        remove(settingsKey)
    }

    /** `cancel` / disable: forgets both the applied record and any snapshot for [key]. */
    fun clear(key: TaskKey) {
        clearPausedSnapshot(key)
        remove(TaskSettingsKeys.applied(key))
    }

    // Settings has no remove(): an empty string reads as absent.
    private fun remove(settingsKey: String) { put(settingsKey, "") }

    private fun put(settingsKey: String, value: String): Boolean = try {
        settings.putString(settingsKey, value)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("ledger write failed for $settingsKey", e)
        false
    }

    private fun get(settingsKey: String): TaskConfig? = try {
        val raw = settings.getString(settingsKey, "")
        if (raw.isEmpty()) null else decode(raw)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("ledger read failed for $settingsKey", e)
        null
    }

    private fun encode(config: TaskConfig): String =
        TaskConfigCodec.encodeEntry(TaskEntry(config.enabled, TaskOverrides(config.schedule, config.constraints)))

    private fun decode(raw: String): TaskConfig? {
        val entry = TaskConfigCodec.decodeEntry(raw) ?: return null
        val ov = entry.overrides ?: return null
        val schedule = ov.schedule ?: return null
        return TaskConfig(entry.enabled, schedule, ov.constraints)
    }

    private fun pausedKey(key: TaskKey): String = TaskKeyEncoding.settingsKey(PAUSED_PREFIX, key)

    companion object {
        /** Distinct from `task_applied_<kind>.`: a kind never contains `_`, so `paused_` cannot be a kind prefix. */
        private const val PAUSED_PREFIX = "task_applied_paused"

        // Copy-on-write; shared by every ledger in the process so a no-op Settings still restores in-process.
        private val inMemory = AtomicReference<Map<String, TaskConfig>>(emptyMap())

        private fun updateStatic(transform: (Map<String, TaskConfig>) -> Map<String, TaskConfig>) {
            while (true) {
                val current = inMemory.load()
                if (inMemory.compareAndSet(current, transform(current))) return
            }
        }

        /** Test hook: drops the process-static snapshot copy so suites do not leak state into each other. */
        fun clearProcessStatic() { inMemory.store(emptyMap()) }
    }
}
