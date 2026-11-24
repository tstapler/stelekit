// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.projectbridge.TechnicalData
import dev.stapler.stelekit.projectbridge.TechnicalEditError
import dev.stapler.stelekit.sketch.ViewDirection
import dev.stapler.stelekit.sketch.ZoneConstraints
import dev.stapler.stelekit.sketch.ZoneReport
import dev.stapler.stelekit.sketch.ZoneSet
import dev.stapler.stelekit.ui.sketch.DimensionField
import dev.stapler.stelekit.ui.sketch.ZonePanel
import dev.stapler.stelekit.ui.sketch.fromJson
import dev.stapler.stelekit.ui.sketch.lengthTextOf
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import dev.stapler.stelekit.units.toInches

object TechnicalDataTags {
    const val PANEL = "sketch.technical"
    const val SUM_VIOLATED = "sketch.technical.sum"
}

/** One named dimension as the panel shows it: [valueText] is what the field starts with. */
data class DimensionRow(val key: String, val valueText: String, val locked: Boolean, val representable: Boolean)

/**
 * What the technical-data panel reads from the model (Story 4.2.5): the project's named dimensions, the sheet's zones and the
 * zone check, which re-runs on every committed edit exactly as the v1 editor does ([ZoneConstraints.resolve] over the same
 * [ZoneSet] and view direction).
 */
data class TechnicalDataView(val dimensions: List<DimensionRow>, val zones: ZoneSet, val report: ZoneReport?) {
    companion object {
        fun of(bundle: ProjectBundle, sheet: SheetUuid, format: LengthFormat = LengthFormat.FEET_INCHES): TechnicalDataView {
            val current = bundle.sheets.firstOrNull { it.uuid == sheet }
            val zones = ZoneSet.fromJson(TechnicalData.zones(bundle, sheet))
            val direction = current?.let { ViewDirection.fromWire(it.viewDirection) } ?: ViewDirection.TOP
            return TechnicalDataView(bundle.dimensions.map { rowOf(it, format) }, zones, ZoneConstraints.resolve(zones, direction).getOrNull())
        }

        private fun rowOf(d: SketchDimension, format: LengthFormat): DimensionRow {
            val inches = d.value.toInches().getOrNull()
            return DimensionRow(d.key, inches?.let { lengthTextOf(it.sixteenths, format) } ?: "${d.value}\"", d.locked, inches != null)
        }
    }
}

/**
 * Named dimensions and zones of a converted sheet, edited with the v1 components ([DimensionField], [ZonePanel]). Each commit
 * is ONE model write: [onBundleChange] gets the bundle with only the edited zone or dimension changed, and the host
 * saves it through its usual autosave. A refused edit (unknown or locked) changes nothing. The caller scrolls it (the editor panel does).
 */
@Composable
fun TechnicalDataPanel(
    bundle: ProjectBundle,
    sheet: SheetUuid,
    onBundleChange: (ProjectBundle) -> Unit,
    modifier: Modifier = Modifier,
    format: LengthFormat = LengthFormat.FEET_INCHES,
    onFormatCycle: (() -> Unit)? = null,
) {
    val view = remember(bundle, sheet, format) { TechnicalDataView.of(bundle, sheet, format) }
    fun apply(edit: Either<TechnicalEditError, ProjectBundle>) = edit.onRight { if (it != bundle) onBundleChange(it) }
    Column(modifier.padding(8.dp).testTag(TechnicalDataTags.PANEL), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(SketchStrings.TechnicalData.HEADING, style = MaterialTheme.typography.titleSmall)
        Text(SketchStrings.TechnicalData.DIMENSIONS_HEADING, style = MaterialTheme.typography.labelLarge)
        if (view.dimensions.isEmpty()) Text(SketchStrings.TechnicalData.NO_DIMENSIONS)
        view.dimensions.forEach { row ->
            if (row.locked) {
                Text(SketchStrings.TechnicalData.lockedDimension(row.key, row.valueText))
            } else {
                DimensionField(
                    initial = row.valueText,
                    label = row.key,
                    format = format,
                    onFormatCycle = onFormatCycle,
                    onCommit = { inches -> inches.toRational().onRight { apply(TechnicalData.withDimensionValue(bundle, row.key, it)) } },
                )
            }
        }
        ZonePanel(
            zones = view.zones,
            report = view.report,
            onAdd = { apply(TechnicalData.withZoneAdded(bundle, sheet, it)) },
            onText = { key, text -> apply(TechnicalData.withZoneText(bundle, sheet, key, text)) },
            onTotal = { key, total -> apply(TechnicalData.withZoneTotal(bundle, sheet, key, total)) },
            onRemove = { apply(TechnicalData.withZoneRemoved(bundle, sheet, it)) },
        )
        if (view.report?.sumViolated == true) {
            Text(SketchStrings.TechnicalData.SUM_VIOLATED, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TechnicalDataTags.SUM_VIOLATED))
        }
    }
}
