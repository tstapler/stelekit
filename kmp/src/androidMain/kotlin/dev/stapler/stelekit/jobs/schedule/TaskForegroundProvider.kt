// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.work.ForegroundInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-kind foreground notification factory for user-initiated one-shot work only; periodic work never
 * consults it. In v1 only the model download registers one (preserving its dataSync notification).
 */
class TaskForegroundProvider {
    fun interface Factory {
        suspend fun create(context: Context, key: TaskKey): ForegroundInfo
    }

    private val factories = ConcurrentHashMap<TaskKind, Factory>()

    fun register(kind: TaskKind, factory: Factory) {
        factories[kind] = factory
    }

    fun factoryFor(kind: TaskKind): Factory? = factories[kind]
}
