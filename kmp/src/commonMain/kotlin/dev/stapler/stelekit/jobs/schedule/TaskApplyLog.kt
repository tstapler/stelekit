// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import dev.stapler.stelekit.logging.Logger

/**
 * One `TaskApply` log line per schedule application, so the persisted log shows what the platform was
 * actually asked for (clamped interval, flex, first-run offset), not just what Settings stored.
 */
object TaskApplyLog {
    private val logger = Logger("TaskApply")

    fun line(key: TaskKey, config: TaskConfig, effective: EffectiveSchedule?): String =
        "TaskApply key=${TaskKeyEncoding.encode(key).replace(WHITESPACE, "_")} enabled=${config.enabled} " +
            "effectiveInterval=${effective?.intervalMinutes ?: "-"} flex=${effective?.flexMinutes ?: "-"} " +
            "offset=${effective?.offsetMinutes ?: "-"} network=${config.constraints.network.name.lowercase()}"

    fun log(key: TaskKey, config: TaskConfig, effective: EffectiveSchedule?) {
        logger.info(line(key, config, effective))
    }

    private val WHITESPACE = Regex("\\s")
}
