// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.persist

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.units.Inches

/** Why a new v2 sheet was not created. Nothing is left behind: a failed create removes the database row it added. */
enum class CreateFailure { NotFound, Encode, Write, Unavailable }

/**
 * Creates the first sheet of a project as a v2 sheet (Task 6.2.0c): the database row at schema 2, then the empty sheet file and
 * the model through the saver's own persister (one verified write under the slug lock), so a project never holds a v2 row
 * without its file. The caller has already run the first-write flow; this class never decides consent.
 */
class NewSheetCreator(private val ports: SheetSaverPorts, private val newId: () -> String, private val limits: SceneLimits = SceneLimits.Default) {
    @OptIn(DirectRepositoryWrite::class)
    suspend fun create(project: ProjectUuid): Either<CreateFailure, SheetUuid> {
        val loaded = ports.repos.projects.loadProject(project).getOrNull() ?: return CreateFailure.NotFound.left()
        val sheet = SheetUuid(newId())
        val doc = SketchDocumentV2(sheetUuid = sheet.value)
        val bytes = SheetCodecV2.encode(doc, limits).getOrNull() ?: return CreateFailure.Encode.left()
        val layers = V2ToV1.projectScene(doc.scene).layers.map { it.encode() }
        val row = SketchSheet(
            uuid = sheet, projectUuid = project, kind = KIND, viewDirection = VIEW, unit = UNIT,
            scaleSixteenthsPerUnit = Inches.SIXTEENTHS_PER_INCH, layers = layers, schemaVersion = SheetPaths.SCHEMA_V2,
        )
        write { ports.repos.sheets.saveSheet(row, loaded.token) }.onLeft { return CreateFailure.Write.left() }
        val persisted = SheetPersister(SheetTarget(loaded.project.slug, project, sheet, null), ports)
            .persist(Encoded(bytes, SheetSaveGuard.sha(bytes), layers), null)
        return when (persisted) {
            Persist.Written, Persist.Deferred -> sheet.right()
            else -> {
                val token = ports.repos.projects.loadProject(project).getOrNull()?.token
                if (token != null) write { ports.repos.sheets.deleteSheet(sheet, token) }
                (if (persisted is Persist.Failed && persisted.reason == SaveFailure.Unavailable) CreateFailure.Unavailable else CreateFailure.Write).left()
            }
        }
    }

    private suspend fun write(op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> = ports.writeActor?.execute { op() } ?: op()

    private companion object {
        const val KIND = "plan"
        const val VIEW = "top"
        const val UNIT = "in"
    }
}
