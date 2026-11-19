// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings

object CoachMarkTags {
    const val ROW = "sketch.coachmark"
    const val SHOW_ME = "sketch.coachmark.showme"
    const val GOT_IT = "sketch.coachmark.gotit"
    const val CLOSE = "sketch.coachmark.close"
}

/**
 * The host's view of the one banner slot and the onboarding sequencer (Tasks 3.6.8b, 3.6.6c): Compose state that mirrors
 * [SketchNoticeQueue] after every change, so the shell recomposes when the visible banner or the empty state changes.
 * The queue owns the order and the recurrence; this class only re-reads it.
 */
class BannerSlotState(private val queue: SketchNoticeQueue) {
    /** The banner candidate on screen, or null. */
    var head: BannerCandidate? by mutableStateOf(queue.bannerHead())
        private set

    private var emptyStateShown by mutableStateOf(false)

    /** True while the sheet is empty and no banner or higher item is up (Task 3.6.6c); never counted. */
    val emptyStateVisible: Boolean get() = emptyStateShown

    /** Offers the first-run coach mark; the sequencer refuses it once it has been shown or retired on this device. */
    fun offerCoachMark(): Boolean {
        val sequencer = queue.sequencer ?: return false
        return FirstRunCoachMark.trigger(sequencer).also { refresh() }
    }

    /** The coach mark went away in [how] (any dismissal retires it for good: the class is once, ux.md section 16). */
    fun dismissCoachMark(how: Dismissal) {
        queue.sequencer?.dismiss(OnboardingItem.CoachMark, how)
        refresh()
    }

    /** Re-reads the slot and the empty-state rule for a sheet that is [empty] or not. */
    fun updateEmpty(empty: Boolean) {
        lastEmpty = empty
        emptyStateShown = queue.sequencer?.emptyStateVisible(empty) ?: (empty && head == null)
    }

    private var lastEmpty = false

    /** Call after the queue or the sheet changed. */
    fun refresh(sheetEmpty: Boolean = lastEmpty) {
        head = queue.bannerHead()
        updateEmpty(sheetEmpty)
    }
}

/** The first-run coach mark (Task 3.6.8b): a once-per-device banner row, "New here? Quick tips." with [Show me]. */
object FirstRunCoachMark {
    /** Offers the row to [sequencer]; false when the sequencer will not show it (shown already, or retired). */
    fun trigger(sequencer: OnboardingSequencer): Boolean {
        val candidate = BannerCandidate.of(BannerRowId.CoachMark, SketchStrings.CoachMark).getOrNull() ?: return false
        return sequencer.trigger(OnboardingItem.CoachMark, candidate)
    }
}

/**
 * The coach mark's banner row: the summary, [Show me] (starts the walkthrough) and [Got it] (retires it). The row is a
 * polite live region and every control meets the touch floor, so it is operable and announced without a pointer.
 */
@Composable
fun CoachMarkRow(candidate: BannerCandidate, onShowMe: () -> Unit, onGotIt: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag(CoachMarkTags.ROW), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                candidate.summary,
                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = candidate.summary },
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onShowMe, modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(CoachMarkTags.SHOW_ME)) { Text(candidate.primaryLabel) }
            TextButton(onClick = onGotIt, modifier = Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(CoachMarkTags.GOT_IT)) { Text(SketchStrings.CoachMark.GOT_IT) }
        }
    }
}

private const val TARGET_DP = 44
