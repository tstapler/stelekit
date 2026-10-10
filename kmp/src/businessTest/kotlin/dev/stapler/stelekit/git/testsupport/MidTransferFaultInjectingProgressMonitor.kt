// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.testsupport

import java.net.SocketException
import org.eclipse.jgit.lib.ProgressMonitor

/**
 * git-sync-resilience Story 6.1.2's fault-injection seam: JGit's own [ProgressMonitor] callback,
 * passed to `CloneCommand`/`FetchCommand.setProgressMonitor()` exactly as production code
 * (`AndroidGitRepository`/`JvmGitRepository`) already does for progress reporting. [update] fires
 * repeatedly from *inside* JGit's real pack-receive/index loop while a clone/fetch is actually
 * transferring data — throwing a real [SocketException] from there lets the exception propagate
 * out through JGit's genuine `CloneCommand`/`FetchCommand` call stack, the same as an actual
 * dropped socket would, rather than a hand-constructed
 * [org.eclipse.jgit.api.errors.TransportException]/[SocketException] pair.
 *
 * Chosen over JGit's pluggable-`Transport`/`TransportProtocol` registration API (also viable, per
 * `research/pitfalls.md` §5.2) because it needs no custom `Transport`/`FetchConnection`
 * implementation — this is the smallest seam that still fires from real, unmodified JGit code.
 *
 * Fires **at most once** per instance (guarded by [hasFired]): a fixture reused across a
 * retry-then-succeed sequence aborts the first attempt's transfer but lets a second attempt
 * complete normally, mirroring [dev.stapler.stelekit.git.testsupport.FailureSequence]'s
 * "fail-then-succeed" shape for the JGit-transport level instead of the fake-repository level.
 */
class MidTransferFaultInjectingProgressMonitor : ProgressMonitor {
    @Volatile private var hasFired = false

    override fun start(totalTasks: Int) {}
    override fun beginTask(title: String, totalWork: Int) {}

    override fun update(completed: Int) {
        if (hasFired) return
        hasFired = true
        throw SocketException("simulated mid-transfer connection drop")
    }

    override fun endTask() {}
    override fun isCancelled() = false
    override fun showDuration(enabled: Boolean) {}
}
