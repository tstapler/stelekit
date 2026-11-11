// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import dev.stapler.stelekit.jobs.JobTags
import java.util.concurrent.TimeUnit

/** Builds the WorkManager requests, input data, tags and constraints [WorkManagerTaskScheduler] enqueues. */
internal class TaskRequestFactory(
    private val registry: TaskRegistry,
    private val workers: WorkerClassResolver,
    private val capabilities: SchedulerCapabilities,
) {
    fun buildPeriodic(key: TaskKey, config: TaskConfig, tagged: Boolean, initialDelayMs: Long? = null): PeriodicWorkRequest {
        val effective = config.schedule.clamp(capabilities, JitterSeed.of(key))
        val worker = workerClassFor(key)
        val builder = if (effective.flexMinutes != null) {
            PeriodicWorkRequest.Builder(worker, effective.intervalMinutes.toLong(), TimeUnit.MINUTES, effective.flexMinutes.toLong(), TimeUnit.MINUTES)
        } else {
            PeriodicWorkRequest.Builder(worker, effective.intervalMinutes.toLong(), TimeUnit.MINUTES)
        }
        builder.setConstraints(constraintsOf(config.constraints))
            .setInputData(inputData(key, config.constraints.network, effective.intervalMinutes))
        if (initialDelayMs != null) {
            if (initialDelayMs > 0) builder.setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
        } else if (effective.offsetMinutes > 0) {
            builder.setInitialDelay(effective.offsetMinutes.toLong(), TimeUnit.MINUTES)
        }
        if (tagged) schedTags(key, registry.specFor(key.kind)).forEach(builder::addTag)
        return builder.build()
    }

    fun workerClassFor(key: TaskKey): Class<out ListenableWorker> =
        workers.bindingFor(key.kind)?.workerClass ?: TaskWorker::class.java

    fun inputData(key: TaskKey, network: NetworkPolicy, interval: Int?): Data {
        val b = Data.Builder().putString(TaskWorkKeys.KEY_NETWORK_POLICY, TaskWorkKeys.encode(network))
        val binding = workers.bindingFor(key.kind)
        if (binding != null) {
            b.putAll(binding.input(key, interval))
        } else {
            b.putString(TaskWorkKeys.KEY_TASK_KIND, key.kind.id).putString(TaskWorkKeys.KEY_TASK_SCOPE, key.scope.value)
        }
        return b.build()
    }

    /** Scope tag value is the raw scope (the reconciler reads it back); a null `jobKind` gets no kind tag. */
    fun schedTags(key: TaskKey, spec: TaskSpec?): List<String> = buildList {
        add(SchedTags.ANY)
        add(SchedTags.scope(key.scope))
        add(SchedTags.kindId(key.kind))
        spec?.jobKind?.let { add(JobTags.kind(it)) }
    }

    fun constraintsOf(c: TaskConstraints): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (c.network == NetworkPolicy.Unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(c.requiresCharging)
        .setRequiresBatteryNotLow(c.batteryNotLow)
        .setRequiresStorageNotLow(c.requiresStorageNotLow)
        .build()
}
