// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SetLayerVisible
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.search.ElementLabelIndex
import dev.stapler.stelekit.sketch.v2.search.LabelEntry
import dev.stapler.stelekit.sketch.v2.view.ScreenRect
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.components.typedItemsIndexed
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchGoToStrings

object GoToElementTags {
    const val DIALOG = "sketch.goto"
    const val SEARCH = "sketch.goto.search"
    const val COUNT = "sketch.goto.count"
    const val MORE = "sketch.goto.more"
    const val LIST = "sketch.goto.list"
    const val CLOSE = "sketch.goto.close"
    fun result(index: Int) = "sketch.goto.result.$index"
}

/** What activating a result does (UX-12): select and pan to the element, or offer the way out of a hidden layer. */
sealed interface GoToOutcome {
    /** [id] is selected and [viewport] shows it; [announcement] is the polite live-region text; [unlock] is offered beside a locked element. */
    data class Selected(val id: ElementId, val viewport: SketchViewport, val announcement: String, val unlock: Command?) : GoToOutcome

    /** The element's layer is hidden: nothing is selected, [showLayer] is the offered action. */
    data class LayerHidden(val showLayer: Command, val announcement: String) : GoToOutcome
}

/** The behaviour of "Go to element..." without a screen, so the whole reach is tested at scene level. */
object GoToElementModel {
    /**
     * Activates [entry]: the view pans (never zooms) by the least amount that brings the element inside [visible], it becomes
     * the selection, and "Selected: Rectangle, Billing API" is announced. A hidden layer selects nothing; a locked element is
     * selected with Unlock on offer, as in the Elements list.
     */
    fun activate(scene: SketchSceneV2, entry: LabelEntry, viewport: SketchViewport, visible: ScreenRect): GoToOutcome? {
        val element = scene[entry.id] ?: return null
        val layer = scene.layer(element.base.layerId)
        if (entry.layerHidden && layer != null) return GoToOutcome.LayerHidden(SetLayerVisible(layer.id, true), SketchGoToStrings.layerHidden(entry.name))
        val revealed = ElementsPanelModel.reveal(scene, entry.id, viewport, visible)?.viewport ?: viewport
        val unlock = if (element.base.locked) LockElements(listOf(entry.id), false) else null
        return GoToOutcome.Selected(entry.id, revealed, SketchGoToStrings.selected(entry.kind, entry.text), unlock)
    }

    /** The row text a screen reader hears: kind, label, then "locked" or "on a hidden layer". */
    fun rowText(entry: LabelEntry): String {
        val state = when {
            entry.layerHidden -> SketchGoToStrings.HIDDEN_LAYER
            entry.locked -> SketchGoToStrings.LOCKED
            else -> null
        }
        return SketchGoToStrings.row(entry.kind, entry.text.ifBlank { entry.name }, state)
    }
}

/**
 * The "Go to element..." dialog (Story 3.6.5): a search field, a virtualised list of at most `maxGoToResults` matches (label
 * and kind only) and "N more" announced, so a screen-reader user reaches any element although only 200 emit semantics nodes.
 * [onActivate] receives the chosen entry; the host calls [GoToElementModel.activate] and applies the outcome.
 */
@Composable
fun GoToElementDialog(scene: SketchSceneV2, onActivate: (LabelEntry) -> Unit, onDismiss: () -> Unit) {
    val index = remember(scene) { ElementLabelIndex.of(scene) }
    var query by remember { mutableStateOf("") }
    val matches = remember(index, query) { index.search(query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(GoToElementTags.DIALOG),
        title = { Text(SketchGoToStrings.TITLE) },
        text = {
            Column {
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth().focusRequester(focus).testTag(GoToElementTags.SEARCH),
                    label = { Text(SketchGoToStrings.SEARCH_LABEL) },
                    singleLine = true,
                )
                val count = SketchGoToStrings.resultCount(matches.shown.size, matches.more)
                Text(
                    count,
                    Modifier.testTag(GoToElementTags.COUNT).padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = count },
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT).testTag(GoToElementTags.LIST)) {
                    typedItemsIndexed(matches.shown, key = { _, entry -> entry.id.value }) { position, entry ->
                        Text(
                            GoToElementModel.rowText(entry),
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = TARGET_DP.dp)
                                .testTag(GoToElementTags.result(position))
                                .clickable { onActivate(entry) }
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (matches.more > 0) {
                    Text(SketchGoToStrings.more(matches.more), Modifier.testTag(GoToElementTags.MORE).padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onDismiss, Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(GoToElementTags.CLOSE)) { Text(SketchGoToStrings.CLOSE) }
        },
    )
}

private val LIST_MAX_HEIGHT = 320.dp
private const val TARGET_DP = 44
