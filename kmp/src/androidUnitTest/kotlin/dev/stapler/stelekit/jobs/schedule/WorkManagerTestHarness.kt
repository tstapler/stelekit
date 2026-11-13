// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Clock
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.UUID

/** Fixed clock so `nextScheduleTimeMillis` is deterministic across apply calls (as in the jitter spike). */
class FixedClock(private val now: Long = 1_700_000_000_000L) : Clock {
    override fun currentTimeMillis(): Long = now
}

/** Shared Robolectric WorkManager setup and read-back helpers for the scheduler adapter tests. */
class WorkManagerTestHarness {
    val context: Context = ApplicationProvider.getApplicationContext()

    init {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setClock(FixedClock()).build(),
        )
    }

    val wm: WorkManager get() = WorkManager.getInstance(context)

    fun infos(name: String): List<WorkInfo> = wm.getWorkInfosForUniqueWork(name).get()

    fun active(name: String): WorkInfo? = infos(name).firstOrNull { !it.state.isFinished }

    /** Room is not on the test classpath, so the WorkSpec is read reflectively: (workerClassName, input). */
    fun spec(id: UUID): Pair<String, Data> {
        val impl = WorkManagerImpl.getInstance(context)
        val db = impl.javaClass.getMethod("getWorkDatabase").invoke(impl)
        val dao = db.javaClass.getMethod("workSpecDao").invoke(db)
        val ws = dao.javaClass.getMethod("getWorkSpec", String::class.java).invoke(dao, id.toString())
        return (ws.javaClass.getField("workerClassName").get(ws) as String) to (ws.javaClass.getField("input").get(ws) as Data)
    }
}
