// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTextBudget
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

/** What the Upgrade chip's three actions do; the host forwards the sheet's buttons to the same actions. */
data class UpgradeOfferActions(val onStart: () -> Unit, val onNotNow: () -> Unit, val onKeepClassic: () -> Unit)

/**
 * The classic editor's adapter for the consent row (Task 1.4.6g, 1.4.6i): it MEASURES the chip summary at the live width and
 * font scale, reports through [onRowFits] whether the row can show it in its 2 lines, and, when it cannot, opens the consent
 * sheet instead if [gate] allows (not busy, not already today, not dismissed this session). An open sheet stays open and
 * reflows when the window or font scale changes; it is never closed by a measurement. Every dismissal of the sheet means
 * Not now.
 */
@Composable
fun UpgradeOfferHost(slug: String, gate: UpgradeConsentGate, interaction: InteractionState, actions: UpgradeOfferActions, onRowFits: (Boolean) -> Unit) {
    var sheetOpen by remember { mutableStateOf(false) }
    val reportRowFits by rememberUpdatedState(onRowFits)
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyMedium
    BoxWithConstraints {
        val textWidth = rowTextWidth(maxWidth)
        val lines = measurer.measure(SketchMigrationStrings.Banners.UPGRADE_SUMMARY, style, constraints = Constraints(maxWidth = with(LocalDensity.current) { textWidth.roundToPx() })).lineCount
        val replacesRow = UpgradeRowFit.sheetReplacesRow(lines)
        LaunchedEffect(replacesRow) { reportRowFits(!replacesRow && !sheetOpen) }
        LaunchedEffect(replacesRow, interaction) {
            if (replacesRow && !sheetOpen && gate.mayOpen(slug, interaction)) {
                gate.opened(slug)
                sheetOpen = true
                reportRowFits(false)
            }
        }
    }
    if (sheetOpen) {
        UpgradeConsentSheet(
            onStart = { sheetOpen = false; actions.onStart() },
            onNotNow = { sheetOpen = false; gate.dismissed(slug); actions.onNotNow() },
            onKeepClassic = { sheetOpen = false; actions.onKeepClassic() },
        )
    }
}

/** The width the summary may use in the banner row: the window minus the gutters, the primary and More buttons and the icon. */
private fun rowTextWidth(window: Dp): Dp {
    val fixed = 2 * BannerTextBudget.GUTTER_DP + BannerTextBudget.PRIMARY_DP + BannerTextBudget.MORE_DP + BannerTextBudget.ICON_DP + 3 * BannerTextBudget.GAP_DP
    return (window - fixed.dp).coerceAtLeast(0.dp)
}
