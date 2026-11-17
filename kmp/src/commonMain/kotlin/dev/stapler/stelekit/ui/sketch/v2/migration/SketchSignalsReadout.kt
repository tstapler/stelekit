// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.AdoptionCounts
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualityCounts
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

/**
 * What the Settings > Sketches readout shows (Task 6.2.3b): the metric-8 numerator and, on its own line, the imports; then
 * the flag-exit counters. Under `NewProjectsOnly` the result carries its label, because a new diagram in an existing project
 * is classic there and is not counted. Pure, so a test reads the lines without a composition.
 */
class SignalsReadoutModel(adoption: AdoptionCounts, quality: QualityCounts, mode: V2Mode) {
    val adoptionLines: List<String> = listOf(SketchStrings.Signals.authored(adoption.authored), SketchStrings.Signals.imported(adoption.imported))

    val qualityLines: List<String> = listOf(
        SketchStrings.Signals.sessions(quality.v2Sessions),
        SketchStrings.Signals.crashes(quality.crashes),
        SketchStrings.Signals.solves(quality.solves),
        SketchStrings.Signals.noConvergence(quality.noConvergence),
        SketchStrings.Signals.conflicts(quality.conflicts),
    )

    /** The label of the result under `NewProjectsOnly`, else null. */
    val modeLabel: String? = SketchStrings.Signals.NEW_PROJECTS_ONLY.takeIf { mode == V2Mode.NewProjectsOnly }

    val selfReportNote: String = SketchStrings.Signals.SELF_REPORT
}

object SignalsReadoutTags {
    const val ADOPTION = "sketch.signals.adoption"
    const val QUALITY = "sketch.signals.quality"
}

private val LineGap = 4.dp

/** The readout; each count is its own line. Reads only local counters, so it can be shown on any platform. */
@Composable
fun SketchSignalsReadout(model: SignalsReadoutModel, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(LineGap), modifier = modifier) {
        Text(SketchStrings.Signals.HEADING, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(SketchStrings.Signals.LOCAL_ONLY, style = MaterialTheme.typography.bodySmall)
        Column(modifier = Modifier.testTag(SignalsReadoutTags.ADOPTION)) { model.adoptionLines.forEach { Text(it) } }
        model.modeLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(model.selfReportNote, style = MaterialTheme.typography.bodySmall)
        Column(modifier = Modifier.testTag(SignalsReadoutTags.QUALITY)) { model.qualityLines.forEach { Text(it) } }
    }
}
