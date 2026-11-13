// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.work.Data

/**
 * androidMain-only per-kind hooks owned by the adapter, not the port. Run order inside a worker:
 * [preRun] -> network gate -> task body -> [postRun]. [input] is the work request's input data. Hook failures never change the worker `Result`.
 */
interface TaskAdapterHooks {
    /** Returns a non-null skip reason (e.g. `"clone-running"`) to skip the run before the gate and body. */
    suspend fun preRun(context: Context, key: TaskKey, input: Data): String? = null

    suspend fun postRun(context: Context, key: TaskKey, input: Data, result: TaskRunResult) {}
}
