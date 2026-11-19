// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.clipboard.ExcalidrawPaste
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.ImportScene
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.importer.ImportedImage
import dev.stapler.stelekit.sketch.v2.runtime.importing.ImportPlan
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Codes
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Import as Strings
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** What applying an import did: [Added] with the document the sheet now holds (null for a new sketch), or [Failed] with its code and message. */
internal sealed interface ImportApplied {
    data class Added(val document: SketchDocumentV2?) : ImportApplied

    data class Failed(val code: String, val message: String) : ImportApplied
}

/**
 * The commit step of the import flow: stores the embedded pictures once each, then adds the elements as ONE command (or as the
 * content of a new sheet) on [commitContext]. Nothing is changed unless every picture stored.
 */
internal class ImportApplier(
    private val port: ImportSheetPort,
    private val images: ImportImageSink,
    private val commitContext: CoroutineContext,
) {
    private val imageFailure = ImportApplied.Failed(Codes.IMAGE_SAVE_FAILED, Strings.IMAGE_SAVE_FAILED)

    suspend fun apply(plan: ImportPlan): ImportApplied {
        if (!store(plan.images)) return imageFailure
        return withContext(commitContext) { if (plan.intoNewSketch) addAsNewSketch(plan) else addToSheet(plan) }
    }

    suspend fun applyPaste(paste: ExcalidrawPaste.Ready): ImportApplied {
        if (!store(paste.images)) return imageFailure
        return withContext(commitContext) {
            val applied = port.commit(paste.command).fold({ error -> return@withContext failure(error) }, { it })
            port.select(paste.pastedIds.toSet())
            ImportApplied.Added(applied.doc)
        }
    }

    private fun store(pictures: List<ImportedImage>): Boolean = pictures.all { images.store(it.bytes).isRight() }

    private fun addToSheet(plan: ImportPlan): ImportApplied {
        val applied = port.commit(plan.command).fold({ error -> return failure(error) }, { it })
        port.select(applied.changedIds)
        return ImportApplied.Added(applied.doc)
    }

    private suspend fun addAsNewSketch(plan: ImportPlan): ImportApplied {
        val document = ImportScene(plan.elements, plan.layers).apply(SketchDocumentV2()).fold({ error -> return failure(error) }, { it.doc })
        port.createSheet(document).fold({ return ImportApplied.Failed(Codes.NEW_SKETCH_FAILED, Strings.NEW_SKETCH_FAILED) }, { })
        return ImportApplied.Added(null)
    }

    private fun failure(error: CommandError): ImportApplied =
        if (error is CommandError.CapacityExceeded) {
            ImportApplied.Failed(Codes.CAPACITY_EXCEEDED, Strings.CAP_REACHED)
        } else {
            ImportApplied.Failed(Codes.UNEXPECTED, Strings.FAILED)
        }
}
