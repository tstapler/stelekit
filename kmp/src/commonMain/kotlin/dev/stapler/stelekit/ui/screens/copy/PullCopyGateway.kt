// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.ApplyFailure
import dev.stapler.stelekit.merge.MergePlan
import dev.stapler.stelekit.merge.MergeProgress
import dev.stapler.stelekit.merge.MergeResult
import dev.stapler.stelekit.merge.PageMergeService
import dev.stapler.stelekit.merge.PageSource
import dev.stapler.stelekit.merge.PlanRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * [CopyFlowGateway] over [PageMergeService] reading from [source] (the [dev.stapler.stelekit.merge.PullPageSource]
 * in a pull). The target is the active graph, so the service's router always resolves `ActiveTargetWriter`.
 * Nothing here queues: a page that cannot be written or read is `failed` and stays retryable.
 *
 * @param reopenDestination makes the pull destination the active graph again (the user may have
 *   switched away mid-run); false means it could not be re-opened and nothing is retried
 */
class PullCopyGateway(
    private val service: PageMergeService,
    private val source: PageSource,
    private val reopenDestination: suspend () -> Boolean,
) : CopyFlowGateway {
    override val progress: StateFlow<MergeProgress> get() = service.progress

    override suspend fun plan(request: PlanRequest): Either<DomainError, MergePlan> = service.plan(request, source)

    override suspend fun apply(plan: MergePlan): Either<ApplyFailure, MergeResult> = service.apply(plan)

    override fun cancel() = service.cancel()

    override suspend fun retryFailed(): Either<ApplyFailure, MergeResult> {
        if (!reopenDestination()) {
            return ApplyFailure.Failed(DomainError.MergeError.Retryable("Couldn't re-open the destination graph")).left()
        }
        return service.retryFailed()
    }
}
