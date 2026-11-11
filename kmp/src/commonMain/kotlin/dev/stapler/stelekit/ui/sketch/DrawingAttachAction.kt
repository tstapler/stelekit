// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.projectbridge.AttachOutcome
import dev.stapler.stelekit.projectbridge.AttachedSheetService
import dev.stapler.stelekit.projectbridge.SheetKind
import dev.stapler.stelekit.projectbridge.SheetSource
import dev.stapler.stelekit.sketchimport.ImportFlow
import dev.stapler.stelekit.ui.sketchimport.SketchImportViewModel
import dev.stapler.stelekit.util.ContentHasher

/** A picked drawing's file name (used only for its extension) and content; the name never reaches synced data. */
class PickedDrawing(val fileName: String, val bytes: ByteArray)

/** One drawing import in progress: the tagging view model plus what to record once it is saved. */
class DrawingSession(
    val projectUuid: String,
    val viewModel: SketchImportViewModel,
    val source: SheetSource?,
    val baselineKeyCount: Int,
)

sealed interface DrawingStart {
    data object Cancelled : DrawingStart
    data class Refused(val message: String) : DrawingStart
    data class Started(val session: DrawingSession) : DrawingStart
}

/**
 * The "Add drawing" action (Story 5.9d). [pick] returns the chosen file, or null when the owner cancelled or the platform has
 * no picker. The existing import flow tags labels and writes the sheet; this class then records the drawing's kind and hash
 * in the project's `sources[]`.
 */
class DrawingAttachAction(
    private val sheets: AttachedSheetService,
    private val flow: ImportFlow,
    private val pick: suspend () -> PickedDrawing?,
) {
    suspend fun start(projectUuid: String): DrawingStart {
        val picked = pick() ?: return DrawingStart.Cancelled
        val kind = SheetKind.fromFileName(picked.fileName)
        val hash = ContentHasher.sha256(picked.bytes)
        if (kind != null && sheets.isAttached(projectUuid, hash)) return DrawingStart.Refused("That drawing is already attached")
        val registry = sheets.registryOf(projectUuid)
        val viewModel = SketchImportViewModel(flow, ProjectUuid(projectUuid), registry)
        viewModel.open(picked.fileName, picked.bytes)
        val source = kind?.let { SheetSource(it, hash) }
        return DrawingStart.Started(DrawingSession(projectUuid, viewModel, source, registry.dimensions.size))
    }

    /** Records the saved drawing; returns a message for the owner, or null on success. */
    suspend fun finish(session: DrawingSession, registry: DimensionRegistry?): String? {
        val source = session.source ?: return "That file type cannot be attached"
        val grown = registry?.takeIf { it.dimensions.size > session.baselineKeyCount }
        return DrawingText.message(sheets.attach(session.projectUuid, source, grown))
    }
}

/** Display text for the attached-drawings list and its outcomes. */
object DrawingText {
    const val HEADING = "Drawings"
    const val EMPTY = "No drawings attached"
    const val ADD = "Add drawing"

    fun message(outcome: AttachOutcome): String? = when (outcome) {
        AttachOutcome.Attached -> null
        AttachOutcome.AlreadyAttached -> "That drawing is already attached"
        AttachOutcome.LimitReached -> "This project already has the maximum number of drawings"
        AttachOutcome.ProjectNotFound -> "Create a project first"
        AttachOutcome.Failed -> "Could not attach the drawing"
    }
}
