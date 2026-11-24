// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings as S

/**
 * The technical sheet's most-used actions as chips in the thumb-reach chip row: the Dimensions toggle and its all/selected scope
 * are always there, the Constraints and Technical data panels join them while a shape is selected. They are shortcuts; the
 * overflow menu keeps its entries.
 */
internal fun technicalChips(controller: SketchEditorController, extras: EditorExtras, constraintsAvailable: Boolean, technicalPanel: Boolean): List<ActionChip> {
    if (controller.sheetKind != SheetKind.Technical) return emptyList()
    val on = extras.pills.visible
    val allShapes = extras.pills.scope == DimensionScope.All
    val dimensions = listOfNotNull(
        ActionChip(
            DimensionPillTags.CHIP_DIMENSIONS,
            if (on) S.Chips.DIMENSIONS else S.Chips.DIMENSIONS_OFF,
            if (on) S.Chips.DIMENSIONS_HIDE else S.Chips.DIMENSIONS_SHOW,
            StripIcon.Technical,
            onClick = { extras.pills.toggle(); controller.setSheetDimensions(extras.pills.visible) },
        ),
        ActionChip(
            LengthUnitTags.CHIP_ROW,
            S.Units.chipRowLabel(extras.lengthUnit.format.chipLabel),
            extras.lengthUnit.format.chipDescription,
            null,
            onClick = extras.lengthUnit::cycle,
        ),
    )
    val scopeChip = listOfNotNull(
        if (on) {
            ActionChip(
                DimensionPillTags.CHIP_DIMENSION_SCOPE,
                if (allShapes) S.Chips.SCOPE_ALL else S.Chips.SCOPE_SELECTED,
                if (allShapes) S.Chips.SCOPE_ALL_DESCRIPTION else S.Chips.SCOPE_SELECTED_DESCRIPTION,
                StripIcon.Select,
                onClick = extras.pills::toggleScope,
            )
        } else {
            null
        },
    )
    if (controller.ui.selection.isEmpty()) return dimensions + scopeChip
    return dimensions + listOfNotNull(
        if (constraintsAvailable) ActionChip(DimensionPillTags.CHIP_CONSTRAINTS, S.Chips.CONSTRAINTS, S.Chips.CONSTRAINTS_DESCRIPTION, StripIcon.Constrain) { controller.ui.panelTab = PanelTab.Constraints } else null,
        if (technicalPanel) ActionChip(DimensionPillTags.CHIP_TECHNICAL, S.Chips.TECHNICAL, S.Chips.TECHNICAL_DESCRIPTION, StripIcon.Grid) { controller.ui.panelTab = PanelTab.Technical } else null,
    ) + scopeChip
}
