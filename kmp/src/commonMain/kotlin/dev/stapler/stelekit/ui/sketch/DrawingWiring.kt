// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.projectbridge.AttachedSheetService
import dev.stapler.stelekit.projectbridge.attachedSheetService
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketchimport.ImportFlow

/** The drawing service and the Add drawing action built for one open graph. */
class DrawingWiring(val sheets: AttachedSheetService, val action: DrawingAttachAction)

/** Null when the platform has no file system or the graph has no notes folder, so the project list offers no drawings. */
fun drawingWiringFor(
    repositories: SketchRepositories,
    assets: () -> AssetIndexService,
    fileSystem: FileSystem?,
    notesPath: EffectiveNotesPath?,
    writeActor: DatabaseWriteActor?,
): DrawingWiring? {
    if (fileSystem == null || notesPath == null) return null
    val sheets = attachedSheetService(repositories, fileSystem, notesPath) { op -> writeActor?.execute(op = op) ?: op() }
    val flow = ImportFlow(assets(), repositories, fileSystem, notesPath, writeActor)
    return DrawingWiring(sheets, DrawingAttachAction(sheets, flow) { pickDrawing(fileSystem) })
}

/** The picked file's bytes and extension-bearing name, or null on cancel or when the platform cannot read bytes. */
suspend fun pickDrawing(fileSystem: FileSystem): PickedDrawing? {
    val path = fileSystem.pickFileAsync() ?: return null
    val bytes = runCatching { fileSystem.readFileBytes(path) }.getOrNull() ?: return null
    return PickedDrawing(path.substringAfterLast('/'), bytes)
}
