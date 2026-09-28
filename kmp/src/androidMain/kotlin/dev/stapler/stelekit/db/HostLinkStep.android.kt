// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation

/**
 * Android's [HostLinkStep] (Epic 4.2, Story 4.2.1). `StorageMoveChoiceDialog`'s existing UI gating
 * (`isLinkAvailable`, keyed off `isGraphGitCloned`) only lets a user reach "Link" for a git-cloned
 * graph, and for that graph `GitShadowWorktree`'s write-back-to-SAF cache mode is already running
 * invisibly — this step needs no new sync machinery, only to let
 * [GraphRelocationCoordinator.link] reach its happy path instead of always failing fast with
 * [DomainError.StorageError.DestinationNotWritable] (the bug this function fixes: before this,
 * no `androidMain` construction site ever supplied a [HostLinkStep] at all).
 *
 * [persistsDestinationOnSuccess] is `false` per plan.md's Story 4.2.1 acceptance criteria: "Link
 * never repoints the location of record — only Relocate does." `AndroidGitRepository`'s
 * resolution logic and the graph's `storage_locations` row are both left exactly as they were.
 */
fun createAndroidHostLinkStep(): HostLinkStep = object : HostLinkStep {
    override suspend fun link(
        existingOpfsPath: String,
        destination: StorageLocation,
    ): Either<DomainError.StorageError, Unit> = Unit.right()

    override val persistsDestinationOnSuccess: Boolean = false
}
