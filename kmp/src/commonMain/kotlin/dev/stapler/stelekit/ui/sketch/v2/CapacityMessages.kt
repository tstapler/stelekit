// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

/** What the user can do when drawing is refused at the scene cap (Story 2.2.4, UX-73): a way forward, never a dead end. */
enum class CapacityAction { DeleteSome, SplitIntoNewSketch, Ok }

/** The hard-cap message and its actions; [splitEnabled] is false with nothing selected, and [splitDisabledReason] then says why. */
data class HardCapMessage(val text: String, val actions: List<CapacityAction>, val splitEnabled: Boolean) {
    val splitDisabledReason: String? get() = if (splitEnabled) null else SketchStrings.Capacity.SPLIT_NEEDS_SELECTION
}

/** The capacity messages as pure data; the composables below only draw them, so the wording is tested without a screen. */
object CapacityMessages {
    /** The hard-cap message for a refused add or paste, or null for any other error. [selectionCount] enables the split action. */
    fun hardCapFor(error: CommandError, selectionCount: Int, limits: SceneLimits = SceneLimits.Default): HardCapMessage? =
        if (error is CommandError.CapacityExceeded) hardCap(selectionCount, limits) else null

    fun hardCap(selectionCount: Int, limits: SceneLimits = SceneLimits.Default): HardCapMessage = HardCapMessage(
        SketchStrings.Capacity.hardCap(limits.maxElements),
        CapacityAction.entries,
        splitEnabled = selectionCount > 0,
    )

    /** The `ConstraintLimit` toast (Review, Details, Dismiss): the 201st constraint is refused by the command (Story 4.2.1). */
    fun constraintLimit(limits: SceneLimits = SceneLimits.Default): SketchNotice = SketchNotice(
        key = SketchStrings.Capacity.CONSTRAINT_LIMIT_KEY,
        tier = BannerTier.Warning,
        text = SketchStrings.Capacity.CONSTRAINT_LIMIT_SUMMARY,
        action = SketchStrings.Capacity.CONSTRAINT_LIMIT_REVIEW,
        shortTitle = SketchStrings.Capacity.CONSTRAINT_LIMIT_SHORT,
        details = SketchStrings.Capacity.constraintLimitDetails(limits.maxConstraints),
    )

    /** The Android `LowMemory` toast: Save now, Dismiss under More, Details; the scene stays in memory. */
    fun lowMemory(): SketchNotice = SketchNotice(
        key = SketchStrings.Capacity.LOW_MEMORY_KEY,
        tier = BannerTier.Warning,
        text = SketchStrings.Capacity.LOW_MEMORY_SUMMARY,
        action = SketchStrings.Capacity.LOW_MEMORY_ACTION,
        shortTitle = SketchStrings.Capacity.LOW_MEMORY_SHORT,
        details = SketchStrings.Capacity.LOW_MEMORY_DETAILS,
    )

    /** After the source sheet's undo brought the moved elements back: offers deleting the new sketch (never done silently). */
    fun splitUndone(sheetLabel: String): SketchNotice = SketchNotice(
        key = SketchStrings.Capacity.SPLIT_UNDONE_KEY,
        tier = BannerTier.Info,
        text = SketchStrings.Capacity.splitUndoneNotice(sheetLabel),
        action = SketchStrings.Capacity.SPLIT_UNDONE_ACTION,
    )
}

object CapacityTags {
    const val NOTICE = "sketch.capacity.hardcap"
    const val DELETE_SOME = "sketch.capacity.delete"
    const val SPLIT = "sketch.capacity.split"
    const val OK = "sketch.capacity.ok"
}

/** The hard-cap notice: the message and its three actions at the touch floor; the split action says why when it is disabled. */
@Composable
fun HardCapNotice(message: HardCapMessage, onAction: (CapacityAction) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag(CapacityTags.NOTICE), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(ChromeBudget.GUTTER_DP.dp)) {
            Text(
                message.text,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = message.text },
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ChromeBudget.TOAST_GAP_DP.dp)) {
                for (action in message.actions) {
                    val enabled = action != CapacityAction.SplitIntoNewSketch || message.splitEnabled
                    TextButton(
                        onClick = { onAction(action) },
                        enabled = enabled,
                        modifier = Modifier.defaultMinSize(minHeight = ChromeBudget.TOOL_TARGET_DP.dp).testTag(tagOf(action)).semantics {
                            message.splitDisabledReason?.takeIf { action == CapacityAction.SplitIntoNewSketch }?.let { stateDescription = it }
                        },
                    ) { Text(labelOf(action)) }
                }
            }
        }
    }
}

private fun tagOf(action: CapacityAction): String = when (action) {
    CapacityAction.DeleteSome -> CapacityTags.DELETE_SOME
    CapacityAction.SplitIntoNewSketch -> CapacityTags.SPLIT
    CapacityAction.Ok -> CapacityTags.OK
}

private fun labelOf(action: CapacityAction): String = when (action) {
    CapacityAction.DeleteSome -> SketchStrings.Capacity.DELETE_SOME
    CapacityAction.SplitIntoNewSketch -> SketchStrings.Capacity.SPLIT
    CapacityAction.Ok -> SketchStrings.Capacity.OK
}
