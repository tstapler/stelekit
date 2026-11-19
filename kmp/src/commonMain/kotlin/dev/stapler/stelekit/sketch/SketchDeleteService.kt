// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchProjectRepository

/**
 * Deletes one sketch project: its sidecar files first, then its database rows. The order matters because the reconciler imports
 * any sidecar folder it finds that the database does not know, so rows deleted first would come back on the next sync. A failure
 * while removing files leaves the project untouched and listed; a failure after them leaves the rows, which the next reconcile
 * pushes back to disk (the delete is undone, never half-done).
 */
class SketchDeleteService(
    private val projects: SketchProjectRepository,
    private val store: () -> ProjectSidecarStore?,
    private val write: suspend (suspend () -> Either<DomainError, Unit>) -> Either<DomainError, Unit>,
    private val slugLock: SidecarSlugLock = SidecarSlugLock(),
) {
    @OptIn(DirectRepositoryWrite::class)
    suspend fun delete(projectUuid: String, slug: String): Either<DomainError, Unit> = slugLock.withSlug(slug) {
        either {
            store()?.deleteProject(slug)?.bind()
            write { projects.deleteProject(ProjectUuid(projectUuid)) }.bind()
        }
    }
}
