// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

enum class JobKind(val label: String) {
    GitClone("Git clone"),
    ModelDownload("Model download"),
    GitSync("Git sync"),
    PageCopy("Page copy"),
    StorageMove("Storage move"),
}

sealed interface JobState {
    data object Queued : JobState

    /** [progressPercent] is 0–100, or null while the total is unknown. */
    data class Running(val progressPercent: Int?) : JobState

    data object Succeeded : JobState

    /** [reason] is plain-language copy when the job supplied one. */
    data class Failed(val reason: String?) : JobState

    data object Cancelled : JobState
}

data class JobStatus(
    val id: String,
    val kind: JobKind,
    val title: String,
    val state: JobState,
    /** False when nothing can stop the job from here. */
    val cancellable: Boolean = true,
) {
    val isActive: Boolean get() = state is JobState.Queued || state is JobState.Running
}

/**
 * Read-only view over work that outlives a screen: downloads, clones and anything else that may finish later.
 * Platforms back it with their persisted job store, so a fresh process sees jobs a previous one started.
 */
interface BackgroundJobRegistry {
    /** Every known job, active ones first. */
    val jobs: Flow<List<JobStatus>>

    /** Stops [id] if it is still active. */
    fun cancel(id: String)

    /** Forgets finished jobs so they leave the list. */
    fun clearFinished()
}

/** Default for platforms with no background job store. */
object NoBackgroundJobs : BackgroundJobRegistry {
    override val jobs: Flow<List<JobStatus>> = flowOf(emptyList())
    override fun cancel(id: String) = Unit
    override fun clearFinished() = Unit
}

/** Active jobs first, otherwise keeping the registry's order. */
fun List<JobStatus>.activeFirst(): List<JobStatus> = sortedByDescending { it.isActive }
