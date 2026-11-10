// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.RevisionToken
import dev.stapler.stelekit.model.SketchDimension
import kotlinx.coroutines.flow.Flow

interface SketchDimensionRepository {
    /** Ordered by key; a [limit] above [MAX_DIMENSION_PAGE] is clamped. */
    fun getDimensions(projectUuid: ProjectUuid, limit: Int = MAX_DIMENSION_PAGE, offset: Int = 0): Flow<Either<DomainError, List<SketchDimension>>>

    /** Upserts on uuid and on the unique key `(project, key, sourceKind, sourceRef)`. */
    @DirectRepositoryWrite
    suspend fun saveDimension(dimension: SketchDimension, token: RevisionToken): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteDimension(uuid: String, token: RevisionToken): Either<DomainError, Unit>

    companion object {
        const val MAX_DIMENSION_PAGE = 500
    }
}
