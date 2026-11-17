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
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualityThresholds
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.ThresholdReading
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

/**
 * What the Settings > Sketches readout shows (Task 6.2.3b): the metric-8 numerator and, on its own line, the imports; then
 * the flag-exit counters. Under `NewProjectsOnly` the result carries its label, because a new diagram in an existing project
 * is classic there and is not counted. Pure, so a test reads the lines without a composition.
 */
class SignalsReadoutModel(
    adoption: AdoptionCounts,
    quality: QualityCounts,
    mode: V2Mode,
    recentSessions: List<Boolean> = emptyList(),
    recentSolves: List<Boolean> = emptyList(),
) {
    val adoptionLines: List<String> = listOf(SketchStrings.Signals.authored(adoption.authored), SketchStrings.Signals.imported(adoption.imported))

    val qualityLines: List<String> = listOf(
        SketchStrings.Signals.sessions(quality.v2Sessions),
        SketchStrings.Signals.crashes(quality.crashes),
        SketchStrings.Signals.solves(quality.solves),
        SketchStrings.Signals.noConvergence(quality.noConvergence),
        SketchStrings.Signals.conflicts(quality.conflicts),
    )

    /** The flag-exit readings (Task 6.2.5d); advice for the owner, never a switch. */
    val thresholdLines: List<String> = listOf(
        SketchStrings.Signals.crashReading(readingText(QualityThresholds.crashRow(quality))),
        SketchStrings.Signals.noConvergenceReading(readingText(QualityThresholds.noConvergenceRow(quality))),
    )

    val stopShipLine: String? = QualityThresholds.stopShip(recentSessions, recentSolves)
        .let { if (it.crashes || it.noConvergence) SketchStrings.Signals.STOP_SHIP else null }

    /** The label of the result under `NewProjectsOnly`, else null. */
    val modeLabel: String? = SketchStrings.Signals.NEW_PROJECTS_ONLY.takeIf { mode == V2Mode.NewProjectsOnly }

    val selfReportNote: String = SketchStrings.Signals.SELF_REPORT
}

private fun readingText(reading: ThresholdReading): String = when (reading) {
    ThresholdReading.Met -> "within the flip threshold"
    ThresholdReading.NotMet -> "above the flip threshold"
    ThresholdReading.InsufficientData -> "not enough data yet"
}

object SignalsReadoutTags {
    const val ADOPTION = "sketch.signals.adoption"
    const val QUALITY = "sketch.signals.quality"
    const val THRESHOLDS = "sketch.signals.thresholds"
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
        Text(SketchStrings.Signals.READINGS_NOTE, style = MaterialTheme.typography.bodySmall)
        Column(modifier = Modifier.testTag(SignalsReadoutTags.THRESHOLDS)) { model.thresholdLines.forEach { Text(it) } }
        model.stopShipLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
