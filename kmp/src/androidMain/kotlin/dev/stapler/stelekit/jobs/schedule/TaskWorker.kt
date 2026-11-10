// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Placeholder so [WorkManagerTaskScheduler] has a worker class for every non-git kind. Story 2.2.2
 * replaces the body (gate, run, log); until then no kind is registered, so nothing enqueues it for real.
 */
class TaskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.failure()
}
