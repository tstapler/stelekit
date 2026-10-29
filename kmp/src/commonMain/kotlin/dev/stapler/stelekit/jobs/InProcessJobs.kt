// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs

import dev.stapler.stelekit.merge.CopyRunHost
import dev.stapler.stelekit.merge.CopyRunOutcome
import dev.stapler.stelekit.merge.MergePlan
import dev.stapler.stelekit.merge.PageMergeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Reports one running job's lifecycle. A finished handle ignores later calls, so the first outcome wins. */
interface JobHandle {
    fun progress(percent: Int?)
    fun succeed()
    fun fail(reason: String?)
    fun cancelled()
}

/** Lets work that runs inside the app process show up in the jobs list. */
interface JobTracker {
    /** [onCancel] makes the job cancellable from the list; null hides the Cancel button. */
    fun begin(kind: JobKind, title: String, onCancel: (() -> Unit)? = null): JobHandle
}

object NoJobTracker : JobTracker {
    private val handle = object : JobHandle {
        override fun progress(percent: Int?) = Unit
        override fun succeed() = Unit
        override fun fail(reason: String?) = Unit
        override fun cancelled() = Unit
    }

    override fun begin(kind: JobKind, title: String, onCancel: (() -> Unit)?): JobHandle = handle
}

/**
 * Runs [block] as a tracked job: success on return, cancelled on [CancellationException], failed on any other
 * Throwable. The exception is rethrown. A block that returns a failure value instead of throwing calls
 * [JobHandle.fail] itself.
 */
suspend fun <T> JobTracker.track(
    kind: JobKind,
    title: String,
    onCancel: (() -> Unit)? = null,
    block: suspend (JobHandle) -> T,
): T {
    val handle = begin(kind, title, onCancel)
    try {
        return block(handle).also { handle.succeed() }
    } catch (e: CancellationException) {
        handle.cancelled()
        throw e
    } catch (e: Throwable) {
        handle.fail(e.message)
        throw e
    }
}

/**
 * Jobs that live and die with the app process (git sync, page copies, storage moves). Unlike WorkManager work they
 * do not survive a restart; they exist so the user can see what is running and why something failed.
 */
class InProcessJobs(private val maxFinished: Int = MAX_FINISHED) : JobTracker, BackgroundJobRegistry {
    private val entries = MutableStateFlow<List<JobStatus>>(emptyList())
    private val cancelHooks = MutableStateFlow<Map<String, () -> Unit>>(emptyMap())
    private val nextId = MutableStateFlow(0L)

    override val jobs: Flow<List<JobStatus>> = entries.map { it.reversed().activeFirst() }

    override fun begin(kind: JobKind, title: String, onCancel: (() -> Unit)?): JobHandle {
        val id = "local-${nextId.getAndUpdate { it + 1 }}"
        if (onCancel != null) cancelHooks.update { it + (id to onCancel) }
        entries.update { it + JobStatus(id, kind, title, JobState.Running(null), cancellable = onCancel != null) }
        return Handle(id)
    }

    override fun cancel(id: String) {
        cancelHooks.value[id]?.invoke()
    }

    override fun clearFinished() {
        entries.update { list -> list.filter { it.isActive } }
    }

    private fun finish(id: String, state: JobState) {
        cancelHooks.update { it - id }
        entries.update { list ->
            val updated = list.map { if (it.id == id && it.isActive) it.copy(state = state) else it }
            val finished = updated.count { !it.isActive }
            if (finished <= maxFinished) updated else updated.dropFirstFinished(finished - maxFinished)
        }
    }

    private fun List<JobStatus>.dropFirstFinished(count: Int): List<JobStatus> {
        var left = count
        return filter { job -> if (!job.isActive && left > 0) { left--; false } else true }
    }

    private inner class Handle(private val id: String) : JobHandle {
        override fun progress(percent: Int?) {
            entries.update { list ->
                list.map { if (it.id == id && it.isActive) it.copy(state = JobState.Running(percent?.coerceIn(0, 100))) else it }
            }
        }

        override fun succeed() = finish(id, JobState.Succeeded)
        override fun fail(reason: String?) = finish(id, JobState.Failed(reason))
        override fun cancelled() = finish(id, JobState.Cancelled)
    }

    private companion object {
        const val MAX_FINISHED = 20
    }
}

/** One list over several sources; [cancel] and [clearFinished] reach all of them, and each ignores ids it does not own. */
class CompositeJobRegistry(private val sources: List<BackgroundJobRegistry>) : BackgroundJobRegistry {
    override val jobs: Flow<List<JobStatus>> =
        if (sources.isEmpty()) NoBackgroundJobs.jobs
        else combine(sources.map { it.jobs }) { lists -> lists.flatMap { it }.activeFirst() }

    override fun cancel(id: String) = sources.forEach { it.cancel(id) }
    override fun clearFinished() = sources.forEach { it.clearFinished() }
}

/**
 * Shows a page copy in the jobs list; the wrapped host still owns the run, its scope and its outcome delivery.
 * The host hands an outcome to whichever observer is attached when the run ends, so [attach] settles the job too.
 */
class TrackedCopyRunHost(private val delegate: CopyRunHost, private val tracker: JobTracker) : CopyRunHost by delegate {
    private var active: JobHandle? = null
    private val attached = mutableMapOf<(CopyRunOutcome) -> Unit, (CopyRunOutcome) -> Unit>()

    override fun start(service: PageMergeService, plan: MergePlan, onOutcome: (CopyRunOutcome) -> Unit): Boolean =
        tracked("Copying pages", service) { delegate.start(service, plan, settling(onOutcome)) }

    override fun retryFailed(service: PageMergeService, onOutcome: (CopyRunOutcome) -> Unit): Boolean =
        tracked("Retrying failed pages", service) { delegate.retryFailed(service, settling(onOutcome)) }

    override fun attach(onOutcome: (CopyRunOutcome) -> Unit) {
        val wrapped = settling(onOutcome)
        attached[onOutcome] = wrapped
        delegate.attach(wrapped)
    }

    override fun detach(onOutcome: (CopyRunOutcome) -> Unit) {
        delegate.detach(attached.remove(onOutcome) ?: onOutcome)
    }

    private fun tracked(title: String, service: PageMergeService, launch: () -> Boolean): Boolean {
        val handle = tracker.begin(JobKind.PageCopy, title) { delegate.stop(service) }
        active = handle
        val started = launch()
        if (!started) {
            active = null
            handle.cancelled()
        }
        return started
    }

    private fun settling(onOutcome: (CopyRunOutcome) -> Unit): (CopyRunOutcome) -> Unit = { outcome ->
        val handle = active
        active = null
        when (outcome) {
            is CopyRunOutcome.Crashed -> handle?.fail(outcome.message)
            is CopyRunOutcome.Finished -> outcome.result.fold(
                ifLeft = { handle?.fail("The copy did not complete") },
                ifRight = { result ->
                    if (result.failed.isEmpty()) handle?.succeed() else handle?.fail("${result.failed.size} pages failed")
                },
            )
        }
        onOutcome(outcome)
    }
}
