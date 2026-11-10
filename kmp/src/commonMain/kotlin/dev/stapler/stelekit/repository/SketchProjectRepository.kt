// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.LoadedSketchProject
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SketchProject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/** Writes are serialised by the caller through `DatabaseWriteActor.execute`, as for `AssetRepository`. */
interface SketchProjectRepository {
    fun getProjects(limit: Int, offset: Int): Flow<Either<DomainError, List<SketchProject>>>
    fun getProjectByUuid(uuid: ProjectUuid): Flow<Either<DomainError, SketchProject?>>

    /** One-shot read for an editor: the project plus the revision token its saves must carry. */
    @Suppress("MissingDirectRepositoryWrite") // a read
    suspend fun loadProject(uuid: ProjectUuid): Either<DomainError, LoadedSketchProject?>

    /** Emits the project uuid after [replaceProjectContents] rebuilt its rows. */
    val invalidations: SharedFlow<ProjectUuid>

    @DirectRepositoryWrite
    suspend fun saveProject(project: SketchProject): Either<DomainError, Unit>

    /** Deletes primitives, then sheets, then dimensions, then the project, in ONE transaction. */
    @DirectRepositoryWrite
    suspend fun deleteProject(uuid: ProjectUuid): Either<DomainError, Unit>

    /** Rebuilds the project row and its cache rows in ONE transaction, bumps the token and emits [invalidations]. */
    @DirectRepositoryWrite
    suspend fun replaceProjectContents(uuid: ProjectUuid, bundle: ProjectBundle): Either<DomainError, Unit>
}
