// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.persist

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.units.Inches

/** Why a new v2 sheet was not created. Nothing is left behind: a failed create removes the database row it added. */
enum class CreateFailure { NotFound, Encode, Write, Unavailable, Deferred }

/**
 * Creates the first sheet of a project as a v2 sheet (Task 6.2.0c): the empty sheet file and the model through the saver's own
 * persister (one verified write under the slug lock), then the database row, so a project never holds a v2 row
 * without its file; a busy sync or an unreconciled pull is [CreateFailure.Deferred], never a silent success. The caller has already run the first-write flow; this class never decides consent.
 */
class NewSheetCreator(private val ports: SheetSaverPorts, private val newId: () -> String, private val limits: SceneLimits = SceneLimits.Default) {
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
        // The persister writes the file and the model first and the database row last, so no crash or deferral leaves a v2 row without its file.
        val persisted = SheetPersister(SheetTarget(loaded.project.slug, project, sheet, null), ports)
            .persist(Encoded(bytes, SheetSaveGuard.sha(bytes), layers), null, row)
        return when (persisted) {
            Persist.Written -> sheet.right()
            Persist.Deferred -> CreateFailure.Deferred.left()
            is Persist.Failed -> (if (persisted.reason == SaveFailure.Unavailable) CreateFailure.Unavailable else CreateFailure.Write).left()
            is Persist.Conflict, Persist.LeaseLost -> CreateFailure.Write.left()
        }
    }

    private companion object {
        const val KIND = "plan"
        const val VIEW = "top"
        const val UNIT = "in"
    }
}
