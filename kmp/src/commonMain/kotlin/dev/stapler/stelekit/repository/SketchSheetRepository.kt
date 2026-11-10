// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.RevisionToken
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchPage
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.model.SketchSheet
import kotlinx.coroutines.flow.Flow

interface SketchSheetRepository {
    /** At most [limit] sheets in position order; `hasMore` says the project has more. */
    fun getSheets(projectUuid: ProjectUuid, limit: Int = DEFAULT_SHEET_LIMIT): Flow<Either<DomainError, SketchPage<SketchSheet>>>

    /** One page of at most [MAX_PRIMITIVE_PAGE] primitives in z order; a larger [limit] is clamped. */
    fun getPrimitives(sheetUuid: SheetUuid, limit: Int = MAX_PRIMITIVE_PAGE, offset: Int = 0): Flow<Either<DomainError, List<SketchPrimitive>>>

    @DirectRepositoryWrite
    suspend fun saveSheet(sheet: SketchSheet, token: RevisionToken): Either<DomainError, Unit>

    /** Deletes the sheet's primitives, then the sheet. */
    @DirectRepositoryWrite
    suspend fun deleteSheet(uuid: SheetUuid, token: RevisionToken): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun savePrimitives(primitives: List<SketchPrimitive>, token: RevisionToken): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deletePrimitive(uuid: String, token: RevisionToken): Either<DomainError, Unit>

    companion object {
        const val DEFAULT_SHEET_LIMIT = 50
        const val MAX_PRIMITIVE_PAGE = 500
    }
}
