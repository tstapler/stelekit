// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.sketch.v2.bridge.ProjectionReport
import dev.stapler.stelekit.sketch.v2.bridge.ProjectionReports
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import kotlinx.coroutines.launch

object TechnicalPanelTags {
    const val PROJECTION = "sketch.technical.projection"
}

/** The data of one technical sheet while the editor is open: the bundle read from [source] and what an edit changed in it. */
class TechnicalPanelState(val source: TechnicalDataSource, val sheet: SheetUuid) {
    /** The project's bundle, or null until it is read (or when it cannot be). */
    var bundle: ProjectBundle? by mutableStateOf(null)
        private set

    suspend fun load() {
        bundle = source.load()
    }

    /** Takes [edited] as the bundle once it is saved; a refused save leaves the panel on what is stored. */
    suspend fun save(edited: ProjectBundle) {
        if (source.save(edited)) bundle = edited
    }
}

/**
 * The technical sheet's named dimensions and zones beside the drawing, and the projection report: how the geometry measures
 * against the named dimensions once it is rounded to sixteenths, and how many elements v1 cannot hold (ux.md section 9).
 */
@Composable
internal fun TechnicalTab(controller: SketchEditorController, state: TechnicalPanelState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.load() }
    Column(modifier) {
        state.bundle?.let { bundle ->
            TechnicalDataPanel(bundle, state.sheet, onBundleChange = { edited -> scope.launch { state.save(edited) } })
        }
        val report = remember(controller.revision) { projectionReport(controller, state) }
        ProjectionMessages.lines(report).forEach { line ->
            Text(line, Modifier.padding(horizontal = 8.dp).testTag(TechnicalPanelTags.PROJECTION), color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun projectionReport(controller: SketchEditorController, state: TechnicalPanelState): ProjectionReport {
    val scene = controller.doc.scene
    return ProjectionReports.of(V2ToV1.projectScene(scene), scene.elementsInOrder, state.source.expectations())
}
