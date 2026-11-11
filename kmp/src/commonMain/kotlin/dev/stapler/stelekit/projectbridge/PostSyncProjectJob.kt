// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.jobs.JobHandle
import dev.stapler.stelekit.jobs.JobKind
import kotlinx.coroutines.CancellationException

sealed interface PostSyncRunOutcome {
    /** Flag, digest opt-in or project missing: nothing ran and nothing was read. */
    data object Inactive : PostSyncRunOutcome

    /** The bound source inputs are unchanged since the last digest, so nothing ran and nothing was written. */
    data object NotRelevant : PostSyncRunOutcome

    data class Completed(val unloadedPages: Int) : PostSyncRunOutcome

    /** Local steps ran, but this platform has no writer for the digest page. */
    data object DigestUnavailable : PostSyncRunOutcome

    /** The digest page was not written (refused, or deferred until the next sync); nothing else is affected. */
    data class DigestNotWritten(val result: DigestWriteResult) : PostSyncRunOutcome

    /** The database had not caught up with the pulled files; retry on the next trigger. */
    data class Unsettled(val result: SettleResult.Unsettled) : PostSyncRunOutcome

    data class Failed(val errorType: String) : PostSyncRunOutcome
}

/** The digest text and the LLM payload, both projected from one fusion result so they cannot disagree. */
data class ProjectOutputs(val digest: String, val payload: LlmPayload)

/**
 * One post-sync pass for a graph. Today the shell only collects (nothing), waits for the database to catch up
 * with the pulled files, and reports through the jobs list; later stories add stage calls between the barrier
 * and the report. Nothing is written without a settled barrier.
 */
class PostSyncProjectJob(
    private val deps: ProjectBridgeDeps,
    private val collector: ProjectSourceCollector = ProjectSourceCollector(reader = ProjectSourceReader { deps.boundSources().toTexts() }),
    private val barrier: SettleBarrier = SettleBarrier(deps.isGitSyncBusy, deps.pageSettleProbe),
    private val digestWriter: DigestWriter = DigestWriter(deps.routerProvider),
    /** The fused digest input for the graph's project; null (the default) defers to [fusion], so nothing is written without either. */
    private val digestInput: suspend (graphId: String) -> DigestInput? = { null },
    /** Null until the app wires an LLM; the step itself is also gated per graph (Story 5.6b). */
    private val analysisStep: ProjectAnalysisStep? = null,
    /** The fused digest and payload for the graph's project; null while the collector yields nothing. */
    private val outputsProvider: suspend (graphId: String) -> ProjectOutputs? = { null },
    /** Fuses what the collector found into the digest input and payload when no provider above supplies them (Story 5.8). */
    private val fusion: ProjectFusionStage? = null,
) {
    /** [syncLeftLfsFiles] is read after the barrier, when the sync has published its final state. */
    suspend fun run(graphId: String, syncLeftLfsFiles: () -> Boolean): PostSyncRunOutcome {
        if (!deps.settings.isDigestActive(graphId) || !deps.hasConfiguredProject(graphId)) {
            return PostSyncRunOutcome.Inactive
        }
        val reconcile = reconcileSidecars()
        val collected = collector.collect()
        val inputHash = ProjectRelevanceHasher.hash(collected)
        if (ProjectRelevanceHasher.isUnchanged(inputHash, deps.settings.lastDigestInputHash(graphId))) {
            return PostSyncRunOutcome.NotRelevant
        }
        val settle = barrier.awaitSettled(collected.boundPages)
        val base = if (syncLeftLfsFiles()) "$TITLE (sync left LFS files held)" else TITLE
        val title = if (reconcile?.needsResolution == true) "$base ($RESOLVE_CONFLICT)" else base
        val handle = deps.jobTracker.begin(JobKind.ProjectDigest, title)
        return try {
            when (settle) {
                is SettleResult.Unsettled -> {
                    handle.fail("${settle.note}; will retry after the next sync")
                    PostSyncRunOutcome.Unsettled(settle)
                }
                is SettleResult.Settled -> if (deps.routerProvider() == null) {
                    handle.fail(UNAVAILABLE)
                    PostSyncRunOutcome.DigestUnavailable
                } else {
                    finish(graphId, handle, settle.unloaded, collected, inputHash)
                }
            }
        } catch (e: CancellationException) {
            handle.cancelled()
            throw e
        } catch (e: Throwable) {
            val type = e::class.simpleName ?: "Error"
            handle.fail("project digest failed ($type)")
            PostSyncRunOutcome.Failed(type)
        }
    }

    /**
     * Brings each project's database state and pulled sidecar into agreement before anything reads the projects.
     * A failure never stops the digest: it is reported per project in the summary instead.
     */
    private suspend fun reconcileSidecars(): ReconcileSummary? {
        val repos = deps.sketchRepositories() ?: return null
        val store = deps.sidecarStore() ?: return null
        return try {
            SidecarReconciler(repos, store, deps.writeActor()).reconcileAll()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /** Digest page first (a local, loop-safe write), then the gated analysis; an analysis failure is reported first. */
    private suspend fun finish(graphId: String, handle: JobHandle, unloaded: Int, collected: CollectedSources, inputHash: String): PostSyncRunOutcome {
        val digest = writeDigest(graphId, collected)
        val analysis = runAnalysis(graphId, collected)
        if (analysis is AnalysisStepOutcome.Blocked || analysis is AnalysisStepOutcome.Failed) {
            handle.fail(analysisReason(analysis))
            return PostSyncRunOutcome.Failed("project analysis")
        }
        val ok = digest == null || digest is DigestWriteResult.Written || digest is DigestWriteResult.Unchanged
        if (ok) handle.succeed() else handle.fail(digest?.note)
        // Remembered only after a complete, settled write so a deferred, refused or partial run retries on the next trigger.
        if (digest != null && ok && unloaded == 0) deps.settings.setLastDigestInputHash(graphId, inputHash)
        return when (digest) {
            null, is DigestWriteResult.Written, is DigestWriteResult.Unchanged -> PostSyncRunOutcome.Completed(unloaded)
            is DigestWriteResult.Unavailable -> PostSyncRunOutcome.DigestUnavailable
            is DigestWriteResult.Failed -> PostSyncRunOutcome.Failed("DigestWriteFailed")
            is DigestWriteResult.Deferred, is DigestWriteResult.Refused -> PostSyncRunOutcome.DigestNotWritten(digest)
        }
    }

    /** Runs after the barrier only, so the page is never replaced from a half-settled database. */
    private suspend fun writeDigest(graphId: String, collected: CollectedSources): DigestWriteResult? {
        val input = digestInput(graphId) ?: fusion?.digestInput(collected) ?: return null
        return digestWriter.write(graphId, deps.settings.digestPageTitle(graphId, input.slug), input)
    }

    private suspend fun runAnalysis(graphId: String, collected: CollectedSources): AnalysisStepOutcome {
        val step = analysisStep ?: return AnalysisStepOutcome.Skipped
        val outputs = outputsProvider(graphId) ?: fusion?.outputs(collected) ?: return AnalysisStepOutcome.Skipped
        return step.run(graphId, outputs)
    }

    private fun analysisReason(outcome: AnalysisStepOutcome): String = when (outcome) {
        is AnalysisStepOutcome.Blocked -> outcome.reason
        is AnalysisStepOutcome.Failed -> "project analysis failed: ${outcome.reason}"
        else -> ""
    }

    companion object {
        const val TITLE = "Project digest"
        const val RESOLVE_CONFLICT = "resolve the sync conflict"
        const val UNAVAILABLE = "digest unavailable: no writer on this platform"

        /** Seam for the digest-write and LLM stages (Stories 5.4c, 5.6b); nothing calls it until they land. */
        internal fun projectOutputs(input: DigestInput, observations: List<Observation>): ProjectOutputs = ProjectOutputs(
            digest = DigestRenderer.render(input),
            payload = LlmPayloadBuilder.build(input.slug, input.result, input.registry, observations, input.checks),
        )
    }
}
