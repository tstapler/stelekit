// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs

import android.content.Context
import androidx.work.WorkManager
import dev.stapler.stelekit.logging.Logger

private val logger = Logger("WorkManagerAccess")

/** The process's [WorkManager], or null (logged) when it has not been initialised yet. */
internal fun workManagerOrNull(context: Context): WorkManager? = try {
    WorkManager.getInstance(context)
} catch (e: IllegalStateException) {
    logger.warn("WorkManager is not initialised", e)
    null
}
