// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CopyRunOutcome {
    data class Finished(val result: Either<ApplyFailure, MergeResult>) : CopyRunOutcome

    /** An unexpected Throwable escaped the run; the manifest stays InProgress so S9 offers Resume. */
    data class Crashed(val message: String) : CopyRunOutcome
}

/**
 * Runs `apply` outside any composition so a dialog leaving the screen (or an Android activity
 * going to the background) never cancels a copy. The interrupted marker is the manifest itself:
 * `PageMergeService` writes it InProgress at start and Complete at the end, so a crash or process
 * death leaves it for [interruptedCopies].
 */
interface CopyRunHost {
    val running: StateFlow<Boolean>

    /** False (and [onOutcome] never called) when a run is already active. */
    fun start(service: PageMergeService, plan: MergePlan, onOutcome: (CopyRunOutcome) -> Unit): Boolean

    /** Re-applies the pages that failed in the last run, with the same lifetime guarantees as [start]. */
    fun retryFailed(service: PageMergeService, onOutcome: (CopyRunOutcome) -> Unit): Boolean

    /** Asks the run to stop after the page in flight. */
    fun stop(service: PageMergeService)
}

/** One unfinished copy found at launch; [pagesCopied] counts manifest entries written so far. */
data class InterruptedCopy(val mergeId: String, val sourceGraphId: String, val targetGraphId: String, val pagesCopied: Int)

fun interruptedCopies(store: MergeManifestStore): List<InterruptedCopy> =
    store.findInterrupted().map { InterruptedCopy(it.mergeId, it.sourceGraphId, it.targetGraphId, it.pages.size) }

/** Owns its scope unless [scope] is supplied (the Android host passes an application-scoped one). */
open class ScopedCopyRunHost(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : CopyRunHost {
    private val logger = Logger("CopyRunHost")
    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val handler = CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) logger.error("copy run: ${e::class.simpleName}: ${e.message}", e)
    }

    override fun start(service: PageMergeService, plan: MergePlan, onOutcome: (CopyRunOutcome) -> Unit): Boolean =
        launchRun(onOutcome) { service.apply(plan) }

    override fun retryFailed(service: PageMergeService, onOutcome: (CopyRunOutcome) -> Unit): Boolean =
        launchRun(onOutcome) { service.retryFailed() }

    private fun launchRun(
        onOutcome: (CopyRunOutcome) -> Unit,
        run: suspend () -> Either<ApplyFailure, MergeResult>,
    ): Boolean {
        if (!_running.compareAndSet(expect = false, update = true)) return false
        scope.launch(handler) {
            val outcome = try {
                CopyRunOutcome.Finished(run())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.error("copy run crashed", e)
                CopyRunOutcome.Crashed(e.message ?: e::class.simpleName.orEmpty())
            } finally {
                _running.value = false
            }
            onOutcome(outcome)
        }
        return true
    }

    override fun stop(service: PageMergeService) = service.cancel()
}

/** Desktop/Web/iOS default: a host with its own scope. */
class DefaultCopyRunHost : ScopedCopyRunHost()
