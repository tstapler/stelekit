// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.merge

import dev.stapler.stelekit.jobs.JobHandle
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobTracker

/**
 * Follows one page-copy run at a time: [begin] opens the job and [settle] closes it from whichever outcome
 * delivery the host uses. Split from [TrackedCopyRunHost] so the bookkeeping needs no real merge service.
 */
internal class CopyJobTracking(private val tracker: JobTracker) {
    private var active: JobHandle? = null

    fun begin(title: String, onCancel: () -> Unit) {
        active = tracker.begin(JobKind.PageCopy, title, onCancel)
    }

    /** Closes the job opened by [begin] as cancelled when the host declined to start. */
    fun declined() {
        active?.cancelled()
        active = null
    }

    fun settle(outcome: CopyRunOutcome) {
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
    }
}

/**
 * Shows a page copy in the jobs list; the wrapped host still owns the run, its scope and its outcome delivery.
 * The host hands an outcome to whichever observer is attached when the run ends, so [attach] settles the job too.
 */
class TrackedCopyRunHost(private val delegate: CopyRunHost, tracker: JobTracker) : CopyRunHost by delegate {
    private val tracking = CopyJobTracking(tracker)
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
        tracking.begin(title) { delegate.stop(service) }
        val started = launch()
        if (!started) tracking.declined()
        return started
    }

    private fun settling(onOutcome: (CopyRunOutcome) -> Unit): (CopyRunOutcome) -> Unit = { outcome ->
        tracking.settle(outcome)
        onOutcome(outcome)
    }
}
