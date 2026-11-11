// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.jobs.JobKind
import kotlinx.coroutines.CancellationException

sealed interface PostSyncRunOutcome {
    /** Flag, digest opt-in or project missing: nothing ran and nothing was read. */
    data object Inactive : PostSyncRunOutcome

    data class Completed(val unloadedPages: Int) : PostSyncRunOutcome

    /** Local steps ran, but this platform has no writer for the digest page. */
    data object DigestUnavailable : PostSyncRunOutcome

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
    private val collector: ProjectSourceCollector = ProjectSourceCollector(),
    private val barrier: SettleBarrier = SettleBarrier(deps.isGitSyncBusy, deps.pageSettleProbe),
) {
    /** [syncLeftLfsFiles] is read after the barrier, when the sync has published its final state. */
    suspend fun run(graphId: String, syncLeftLfsFiles: () -> Boolean): PostSyncRunOutcome {
        if (!deps.settings.isDigestActive(graphId) || !deps.hasConfiguredProject(graphId)) {
            return PostSyncRunOutcome.Inactive
        }
        val settle = barrier.awaitSettled(collector.collect().boundPages)
        val title = if (syncLeftLfsFiles()) "$TITLE (sync left LFS files held)" else TITLE
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
                    handle.succeed()
                    PostSyncRunOutcome.Completed(settle.unloaded)
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

    companion object {
        const val TITLE = "Project digest"
        const val UNAVAILABLE = "digest unavailable: no writer on this platform"

        /** Seam for the digest-write and LLM stages (Stories 5.4c, 5.6b); nothing calls it until they land. */
        internal fun projectOutputs(input: DigestInput, observations: List<Observation>): ProjectOutputs = ProjectOutputs(
            digest = DigestRenderer.render(input),
            payload = LlmPayloadBuilder.build(input.slug, input.result, input.registry, observations, input.checks),
        )
    }
}
