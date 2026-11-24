// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.SheetStyleExtras
import dev.stapler.stelekit.sketch.v2.commands.SheetDimensionsExtras
import dev.stapler.stelekit.sketch.v2.commands.SheetKindExtras
import dev.stapler.stelekit.sketch.v2.commands.withDimensionsOn
import dev.stapler.stelekit.sketch.v2.commands.withSheetKind
import dev.stapler.stelekit.sketch.v2.commands.withSheetStyle
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.sketch.v2.render.ElementSeeds
import dev.stapler.stelekit.sketch.v2.render.SeedSource
import dev.stapler.stelekit.sketch.v2.render.SequenceSeedSource
import dev.stapler.stelekit.sketch.v2.render.SheetStyleDefaults
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.tools.AddElementCommand

/**
 * What a NEW element starts with on one sheet: its look (Clean unless the sheet is Sketchy, OD-5) and its once-assigned Sketchy
 * seed (edits never reseed). The sheet's choice rides in the document's extras; it is not an undo step.
 */
class ElementCreation(initial: SketchDocumentV2, private val seeds: SeedSource) {
    var sheetStyle: SheetStyleDefaults by mutableStateOf(SheetStyleExtras.read(initial.extras))
        private set

    /** Whether the sheet is a diagram or a technical sheet (dimensions in inches, constraint tools on). */
    var sheetKind: SheetKind by mutableStateOf(SheetKindExtras.read(initial.extras))
        private set

    /** Whether the sheet shows its dimensions, as the user last chose; null until they do (the sheet kind's default then applies). It also decides the exports. */
    var dimensionsOn: Boolean? by mutableStateOf(SheetDimensionsExtras.read(initial.extras))
        private set

    /** The style a new shape takes before the Style panel's defaults row changes it. */
    fun startingStyle(): ElementStyle = sheetStyle.styleForNewElement()

    /** The drawn shape with its seed assigned; a shape that already has one (a duplicate, an import) is unchanged. */
    fun seeded(command: AddElementCommand): AddElementCommand {
        val element = command.element
        val base = ElementSeeds.ensureSeed(element.base, seeds)
        return if (base == element.base) command else AddElementCommand(element.withBase(base))
    }

    /** Takes the style stored in a reloaded document. */
    fun reload(doc: SketchDocumentV2) {
        sheetStyle = SheetStyleExtras.read(doc.extras)
        sheetKind = SheetKindExtras.read(doc.extras)
        dimensionsOn = SheetDimensionsExtras.read(doc.extras)
    }

    fun choose(style: SheetStyleDefaults) {
        sheetStyle = style
    }

    fun chooseKind(kind: SheetKind) {
        sheetKind = kind
    }

    fun chooseDimensions(on: Boolean) {
        dimensionsOn = on
    }

    /** [doc] as it is saved: with the sheet's style in its extras. */
    fun forSave(doc: SketchDocumentV2): SketchDocumentV2 = doc.withSheetStyle(sheetStyle).withSheetKind(sheetKind).withDimensionsOn(dimensionsOn)

    companion object {
        /** A seed source that differs per sheet and per session without a random source: the clock folded with the sheet. */
        fun defaultSeeds(sheetUuid: String, clockMillis: Long): SeedSource = SequenceSeedSource((clockMillis xor sheetUuid.hashCode().toLong()).toInt())
    }
}

/** Makes [style] the sheet's style: new shapes start Clean or Sketchy; shapes already drawn keep their own look. Not an undo step. */
fun SketchEditorController.setSheetStyle(style: SheetStyleDefaults) {
    if (readOnly || style == creation.sheetStyle) return
    creation.choose(style)
    defaultStyle = defaultStyle.copy(roughness = style.newElementRoughness)
    saves.submit(creation.forSave(history.present))
}

/** Makes the sheet a diagram or a technical sheet: the technical tools, inch dimensions and the auto-constrain default follow. Not an undo step. */
fun SketchEditorController.setSheetKind(kind: SheetKind) {
    if (readOnly || kind == creation.sheetKind) return
    creation.chooseKind(kind)
    saves.submit(creation.forSave(history.present))
}

/** Remembers in the sheet whether its dimensions show, so the exports and the embed follow the editor. Not an undo step. */
fun SketchEditorController.setSheetDimensions(on: Boolean) {
    if (readOnly || on == creation.dimensionsOn) return
    creation.chooseDimensions(on)
    saves.submit(creation.forSave(history.present))
}
