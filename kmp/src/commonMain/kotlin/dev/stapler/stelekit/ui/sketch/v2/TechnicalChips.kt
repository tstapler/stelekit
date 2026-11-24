// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings as S

/**
 * The technical sheet's most-used actions as chips in the thumb-reach chip row, shown while a shape is selected: the Dimensions
 * toggle, the Constraints panel and the Technical data panel. They are shortcuts; the overflow menu keeps its entries.
 */
internal fun technicalChips(controller: SketchEditorController, extras: EditorExtras, constraintsAvailable: Boolean, technicalPanel: Boolean): List<ActionChip> {
    if (controller.sheetKind != SheetKind.Technical || controller.ui.selection.isEmpty()) return emptyList()
    val on = extras.pills.visible
    return listOfNotNull(
        ActionChip(
            DimensionPillTags.CHIP_DIMENSIONS,
            if (on) S.Chips.DIMENSIONS else S.Chips.DIMENSIONS_OFF,
            if (on) S.Chips.DIMENSIONS_HIDE else S.Chips.DIMENSIONS_SHOW,
            StripIcon.Technical,
            extras.pills::toggle,
        ),
        ActionChip(
            LengthUnitTags.CHIP_ROW,
            S.Units.chipRowLabel(extras.lengthUnit.format.chipLabel),
            extras.lengthUnit.format.chipDescription,
            null,
            extras.lengthUnit::cycle,
        ),
        if (constraintsAvailable) ActionChip(DimensionPillTags.CHIP_CONSTRAINTS, S.Chips.CONSTRAINTS, S.Chips.CONSTRAINTS_DESCRIPTION, StripIcon.Constrain) { controller.ui.panelTab = PanelTab.Constraints } else null,
        if (technicalPanel) ActionChip(DimensionPillTags.CHIP_TECHNICAL, S.Chips.TECHNICAL, S.Chips.TECHNICAL_DESCRIPTION, StripIcon.Grid) { controller.ui.panelTab = PanelTab.Technical } else null,
    )
}
