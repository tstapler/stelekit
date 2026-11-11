// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.LoadedSketchProject
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.RevisionToken
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchPage
import dev.stapler.stelekit.model.SketchPrimitive
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Test and non-SQLDelight backend: the three repositories share one in-memory [Store]. */
fun inMemorySketchRepositories(): SketchRepositories {
    val store = InMemorySketchStore()
    return SketchRepositories(
        projects = InMemorySketchProjectRepository(store),
        sheets = InMemorySketchSheetRepository(store),
        dimensions = InMemorySketchDimensionRepository(store),
    )
}

internal class InMemorySketchStore {
    val tokens = SketchRevisionTokens()
    val projects = MutableStateFlow<Map<ProjectUuid, SketchProject>>(emptyMap())
    val sheets = MutableStateFlow<Map<SheetUuid, SketchSheet>>(emptyMap())
    val primitives = MutableStateFlow<Map<String, SketchPrimitive>>(emptyMap())
    val dimensions = MutableStateFlow<Map<String, SketchDimension>>(emptyMap())

    /** Mirrors the SQL `INSERT OR REPLACE`: the unique key `(project, key, sourceKind, sourceRef)` evicts a clash. */
    fun putDimension(d: SketchDimension) = dimensions.update { all ->
        all.filterValues { it.uuid == d.uuid || !it.sameKeyAs(d) } + (d.uuid to d)
    }

    private fun SketchDimension.sameKeyAs(o: SketchDimension) =
        projectUuid == o.projectUuid && key == o.key && sourceKind == o.sourceKind && sourceRef == o.sourceRef
}

private fun Either<DomainError, Unit>.thenDo(block: () -> Unit): Either<DomainError, Unit> = onRight { block() }

@Suppress("InMemoryPagination")
private class InMemorySketchProjectRepository(private val store: InMemorySketchStore) : SketchProjectRepository {
    private val _invalidations = MutableSharedFlow<ProjectUuid>(extraBufferCapacity = 16)
    override val invalidations: SharedFlow<ProjectUuid> = _invalidations

    override fun getProjects(limit: Int, offset: Int): Flow<Either<DomainError, List<SketchProject>>> =
        store.projects.map { all ->
            all.values.sortedWith(compareByDescending<SketchProject> { it.createdAt }.thenBy { it.uuid.value })
                .drop(offset).take(limit).right()
        }

    override fun getProjectByUuid(uuid: ProjectUuid): Flow<Either<DomainError, SketchProject?>> =
        store.projects.map { it[uuid].right() }

    override suspend fun loadProject(uuid: ProjectUuid): Either<DomainError, LoadedSketchProject?> {
        val token = store.tokens.current(uuid)
        return store.projects.value[uuid]?.let { LoadedSketchProject(it, token) }.right()
    }

    override suspend fun saveProject(project: SketchProject): Either<DomainError, Unit> {
        store.projects.update { all -> all.filterValues { it.slug != project.slug || it.uuid == project.uuid } + (project.uuid to project) }
        return Unit.right()
    }

    override suspend fun deleteProject(uuid: ProjectUuid): Either<DomainError, Unit> {
        val sheetIds = store.sheets.value.values.filter { it.projectUuid == uuid }.map { it.uuid }.toSet()
        store.primitives.update { all -> all.filterValues { it.sheetUuid !in sheetIds } }
        store.sheets.update { all -> all.filterValues { it.projectUuid != uuid } }
        store.dimensions.update { all -> all.filterValues { it.projectUuid != uuid } }
        store.projects.update { it - uuid }
        store.tokens.forget(uuid)
        return Unit.right()
    }

    override suspend fun replaceProjectContents(uuid: ProjectUuid, bundle: ProjectBundle): Either<DomainError, Unit> {
        val sheetIds = bundle.sheets.mapTo(HashSet()) { it.uuid }
        val consistent = bundle.project.uuid == uuid && bundle.sheets.all { it.projectUuid == uuid } &&
            bundle.dimensions.all { it.projectUuid == uuid } && bundle.primitives.all { it.sheetUuid in sheetIds }
        if (!consistent) return DomainError.DatabaseError.WriteFailed("bundle does not belong to project").left()
        deleteProject(uuid)
        store.projects.update { it + (uuid to bundle.project) }
        store.sheets.update { all -> all + bundle.sheets.associateBy { it.uuid } }
        store.primitives.update { all -> all + bundle.primitives.associateBy { it.uuid } }
        bundle.dimensions.forEach { store.putDimension(it) }
        store.tokens.bump(uuid)
        _invalidations.emit(uuid)
        return Unit.right()
    }
}

@Suppress("InMemoryPagination")
private class InMemorySketchSheetRepository(private val store: InMemorySketchStore) : SketchSheetRepository {
    override fun getSheets(projectUuid: ProjectUuid, limit: Int): Flow<Either<DomainError, SketchPage<SketchSheet>>> =
        store.sheets.map { all ->
            val sorted = all.values.filter { it.projectUuid == projectUuid }.sortedWith(compareBy({ it.position }, { it.uuid.value }))
            SketchPage(sorted.take(limit.coerceAtLeast(0)), sorted.size > limit.coerceAtLeast(0)).right()
        }

    override fun getPrimitives(sheetUuid: SheetUuid, limit: Int, offset: Int): Flow<Either<DomainError, List<SketchPrimitive>>> =
        store.primitives.map { all ->
            all.values.filter { it.sheetUuid == sheetUuid }.sortedWith(compareBy({ it.z }, { it.uuid }))
                .drop(offset).take(limit.coerceIn(0, SketchSheetRepository.MAX_PRIMITIVE_PAGE)).right()
        }

    override suspend fun saveSheet(sheet: SketchSheet, token: RevisionToken): Either<DomainError, Unit> =
        store.tokens.check(token).thenDo { store.sheets.update { it + (sheet.uuid to sheet) } }

    override suspend fun deleteSheet(uuid: SheetUuid, token: RevisionToken): Either<DomainError, Unit> =
        store.tokens.check(token).thenDo {
            store.primitives.update { all -> all.filterValues { it.sheetUuid != uuid } }
            store.sheets.update { it - uuid }
        }

    override suspend fun savePrimitives(primitives: List<SketchPrimitive>, token: RevisionToken): Either<DomainError, Unit> =
        store.tokens.check(token).thenDo { store.primitives.update { it + primitives.associateBy { p -> p.uuid } } }

    override suspend fun deletePrimitive(uuid: String, token: RevisionToken): Either<DomainError, Unit> =
        store.tokens.check(token).thenDo { store.primitives.update { it - uuid } }
}

@Suppress("InMemoryPagination")
private class InMemorySketchDimensionRepository(private val store: InMemorySketchStore) : SketchDimensionRepository {
    override fun getDimensions(projectUuid: ProjectUuid, limit: Int, offset: Int): Flow<Either<DomainError, List<SketchDimension>>> =
        store.dimensions.map { all ->
            all.values.filter { it.projectUuid == projectUuid }.sortedWith(compareBy({ it.key }, { it.uuid }))
                .drop(offset).take(limit.coerceIn(0, SketchDimensionRepository.MAX_DIMENSION_PAGE)).right()
        }

    override suspend fun saveDimension(dimension: SketchDimension, token: RevisionToken): Either<DomainError, Unit> =
        store.tokens.check(token).thenDo { store.putDimension(dimension) }

    override suspend fun deleteDimension(uuid: String, token: RevisionToken): Either<DomainError, Unit> =
        store.tokens.check(token).thenDo { store.dimensions.update { it - uuid } }
}
