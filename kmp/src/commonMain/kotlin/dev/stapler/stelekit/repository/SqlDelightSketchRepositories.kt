// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.db.Sketch_dimensions
import dev.stapler.stelekit.db.Sketch_primitives
import dev.stapler.stelekit.db.Sketch_projects
import dev.stapler.stelekit.db.Sketch_sheets
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.SteleDatabaseQueries
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
import dev.stapler.stelekit.units.Rational
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * SQLDelight-backed sketch repositories. Like `SqlDelightAssetRepository` they call the generated queries
 * directly (no direct-SQL-write opt-in); callers serialise writes through `DatabaseWriteActor.execute`.
 */
fun sqlDelightSketchRepositories(database: SteleDatabase): SketchRepositories {
    val tokens = SketchRevisionTokens()
    val queries = database.steleDatabaseQueries
    return SketchRepositories(
        projects = SqlDelightSketchProjectRepository(queries, tokens),
        sheets = SqlDelightSketchSheetRepository(queries, tokens),
        dimensions = SqlDelightSketchDimensionRepository(queries, tokens),
    )
}

private val sketchJson = Json { ignoreUnknownKeys = true }

private fun parseObject(text: String): JsonObject =
    try { sketchJson.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap()) } catch (_: Exception) { JsonObject(emptyMap()) }

private fun parseArray(text: String): JsonArray =
    try { sketchJson.parseToJsonElement(text) as? JsonArray ?: JsonArray(emptyList()) } catch (_: Exception) { JsonArray(emptyList()) }

private fun parseStrings(text: String): List<String> =
    try { sketchJson.decodeFromString(ListSerializer(String.serializer()), text) } catch (_: Exception) { emptyList() }

private fun Sketch_projects.toModel() = SketchProject(
    uuid = ProjectUuid(uuid), slug = slug, name = name, createdAt = created_at,
    settings = parseObject(settings_json), fusionResults = parseObject(fusion_results_json),
    sourceHash = source_hash, lastDigestHash = last_digest_hash, modelRevision = model_revision,
    baseRevision = base_revision, lastSyncedSidecarHash = last_synced_sidecar_hash,
)

private fun Sketch_sheets.toModel() = SketchSheet(
    uuid = SheetUuid(uuid), projectUuid = ProjectUuid(project_uuid), kind = kind, viewDirection = view_direction,
    unit = unit, scaleSixteenthsPerUnit = scale_sixteenths_per_unit, sourceAssetUuid = source_asset_uuid,
    layers = parseStrings(layer_json), zones = parseArray(zones_json), position = position,
)

private fun Sketch_primitives.toModel() = SketchPrimitive(
    uuid = uuid, sheetUuid = SheetUuid(sheet_uuid), kind = kind, x1 = x1, y1 = y1, x2 = x2, y2 = y2,
    name = name, layer = layer, z = z,
)

private fun Sketch_dimensions.toModel() = SketchDimension(
    uuid = uuid, projectUuid = ProjectUuid(project_uuid), key = key, kind = kind,
    value = Rational(value_num, value_den),
    precision = precision_num?.let { n -> precision_den?.let { d -> Rational(n, d) } },
    sourceKind = source_kind, sourceRef = source_ref, tolerance = Rational(tolerance_num, tolerance_den),
    locked = locked != 0L,
)

private suspend fun SteleDatabaseQueries.put(p: SketchProject) = insertSketchProject(
    p.uuid.value, p.slug, p.name, p.createdAt, p.settings.toString(), p.fusionResults.toString(), p.sourceHash,
    p.lastDigestHash, p.modelRevision, p.baseRevision, p.lastSyncedSidecarHash,
)

private suspend fun SteleDatabaseQueries.put(s: SketchSheet) = insertSketchSheet(
    s.uuid.value, s.projectUuid.value, s.kind, s.viewDirection, s.unit, s.scaleSixteenthsPerUnit, s.sourceAssetUuid,
    sketchJson.encodeToString(ListSerializer(String.serializer()), s.layers), s.zones.toString(), s.position,
)

private suspend fun SteleDatabaseQueries.put(p: SketchPrimitive) =
    insertSketchPrimitive(p.uuid, p.sheetUuid.value, p.kind, p.x1, p.y1, p.x2, p.y2, p.name, p.layer, p.z)

private suspend fun SteleDatabaseQueries.put(d: SketchDimension) = insertSketchDimension(
    d.uuid, d.projectUuid.value, d.key, d.kind, d.value.num, d.value.den, d.precision?.num, d.precision?.den,
    d.sourceKind, d.sourceRef, d.tolerance.num, d.tolerance.den, if (d.locked) 1L else 0L,
)

private class StaleEditorException : RuntimeException(SketchRevisionTokens.STALE_EDITOR)

/** Runs [body] on the DB dispatcher, mapping every failure (a stale token included) to `WriteFailed`. */
private suspend fun writeOnDb(body: suspend () -> Unit): Either<DomainError, Unit> =
    withContext(PlatformDispatcher.DB) {
        try {
            body()
            Unit.right()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

private fun SketchRevisionTokens.require(token: RevisionToken) {
    if (check(token).isLeft()) throw StaleEditorException()
}

@OptIn(DirectRepositoryWrite::class)
private class SqlDelightSketchProjectRepository(
    private val queries: SteleDatabaseQueries,
    private val tokens: SketchRevisionTokens,
) : SketchProjectRepository {
    private val _invalidations = MutableSharedFlow<ProjectUuid>(extraBufferCapacity = 16)
    override val invalidations: SharedFlow<ProjectUuid> = _invalidations

    override fun getProjects(limit: Int, offset: Int): Flow<Either<DomainError, List<SketchProject>>> =
        queries.selectSketchProjects(limit.toLong(), offset.toLong()).asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override fun getProjectByUuid(uuid: ProjectUuid): Flow<Either<DomainError, SketchProject?>> =
        queries.selectSketchProjectByUuid(uuid.value).asDbFlowOrNull(PlatformDispatcher.DB) { it.toModel() }

    override suspend fun loadProject(uuid: ProjectUuid): Either<DomainError, LoadedSketchProject?> =
        withContext(PlatformDispatcher.DB) {
            try {
                // Token first: a rebuild landing between the two reads then makes the later save fail, never succeed.
                val token = tokens.current(uuid)
                queries.selectSketchProjectByUuid(uuid.value).executeAsOneOrNull()
                    ?.let { LoadedSketchProject(it.toModel(), token) }.right()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
            }
        }

    override suspend fun saveProject(project: SketchProject): Either<DomainError, Unit> =
        writeOnDb { queries.put(project) }

    override suspend fun deleteProject(uuid: ProjectUuid): Either<DomainError, Unit> =
        writeOnDb {
            queries.transaction {
                queries.deleteSketchPrimitivesByProject(uuid.value)
                queries.deleteSketchSheetsByProject(uuid.value)
                queries.deleteSketchDimensionsByProject(uuid.value)
                queries.deleteSketchProject(uuid.value)
            }
            tokens.forget(uuid)
        }

    override suspend fun replaceProjectContents(uuid: ProjectUuid, bundle: ProjectBundle): Either<DomainError, Unit> =
        writeOnDb {
            val sheetUuids = bundle.sheets.mapTo(HashSet()) { it.uuid }
            require(bundle.project.uuid == uuid && bundle.sheets.all { it.projectUuid == uuid } &&
                bundle.dimensions.all { it.projectUuid == uuid } && bundle.primitives.all { it.sheetUuid in sheetUuids }) {
                "bundle does not belong to project"
            }
            queries.transaction {
                queries.deleteSketchPrimitivesByProject(uuid.value)
                queries.deleteSketchSheetsByProject(uuid.value)
                queries.deleteSketchDimensionsByProject(uuid.value)
                queries.put(bundle.project)
                bundle.sheets.forEach { queries.put(it) }
                bundle.primitives.forEach { queries.put(it) }
                bundle.dimensions.forEach { queries.put(it) }
            }
            tokens.bump(uuid)
            _invalidations.emit(uuid)
        }
}

@OptIn(DirectRepositoryWrite::class)
private class SqlDelightSketchSheetRepository(
    private val queries: SteleDatabaseQueries,
    private val tokens: SketchRevisionTokens,
) : SketchSheetRepository {
    override fun getSheets(projectUuid: ProjectUuid, limit: Int): Flow<Either<DomainError, SketchPage<SketchSheet>>> {
        val bounded = limit.coerceAtLeast(0)
        return queries.selectSketchSheetsByProject(projectUuid.value, bounded.toLong() + 1)
            .asDbFlowList(PlatformDispatcher.DB) { it.toModel() }
            .map { either -> either.map { SketchPage(it.take(bounded), it.size > bounded) } }
    }

    override fun getPrimitives(sheetUuid: SheetUuid, limit: Int, offset: Int): Flow<Either<DomainError, List<SketchPrimitive>>> =
        queries.selectSketchPrimitivesBySheet(
            sheetUuid.value, limit.coerceIn(0, SketchSheetRepository.MAX_PRIMITIVE_PAGE).toLong(), offset.toLong(),
        ).asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override suspend fun saveSheet(sheet: SketchSheet, token: RevisionToken): Either<DomainError, Unit> =
        writeOnDb { tokens.require(token); queries.put(sheet) }

    override suspend fun deleteSheet(uuid: SheetUuid, token: RevisionToken): Either<DomainError, Unit> =
        writeOnDb {
            tokens.require(token)
            queries.transaction {
                queries.deleteSketchPrimitivesBySheet(uuid.value)
                queries.deleteSketchSheet(uuid.value)
            }
        }

    override suspend fun savePrimitives(primitives: List<SketchPrimitive>, token: RevisionToken): Either<DomainError, Unit> =
        writeOnDb {
            tokens.require(token)
            queries.transaction { primitives.forEach { queries.put(it) } }
        }

    override suspend fun deletePrimitive(uuid: String, token: RevisionToken): Either<DomainError, Unit> =
        writeOnDb { tokens.require(token); queries.deleteSketchPrimitive(uuid) }
}

@OptIn(DirectRepositoryWrite::class)
private class SqlDelightSketchDimensionRepository(
    private val queries: SteleDatabaseQueries,
    private val tokens: SketchRevisionTokens,
) : SketchDimensionRepository {
    override fun getDimensions(projectUuid: ProjectUuid, limit: Int, offset: Int): Flow<Either<DomainError, List<SketchDimension>>> =
        queries.selectSketchDimensionsByProject(
            projectUuid.value, limit.coerceIn(0, SketchDimensionRepository.MAX_DIMENSION_PAGE).toLong(), offset.toLong(),
        ).asDbFlowList(PlatformDispatcher.DB) { it.toModel() }

    override suspend fun saveDimension(dimension: SketchDimension, token: RevisionToken): Either<DomainError, Unit> =
        writeOnDb { tokens.require(token); queries.put(dimension) }

    override suspend fun deleteDimension(uuid: String, token: RevisionToken): Either<DomainError, Unit> =
        writeOnDb { tokens.require(token); queries.deleteSketchDimension(uuid) }
}
