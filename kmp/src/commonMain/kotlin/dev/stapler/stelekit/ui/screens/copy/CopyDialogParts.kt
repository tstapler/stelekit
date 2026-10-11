// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.copy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.stapler.stelekit.merge.CopyDirection

/** User-visible wording for the copy dialogs (design/ux.md S4-S6, S9). One place so tests and siblings share it. */
object CopyDialogStrings {
    const val REASSURANCE = "Nothing in the destination is deleted or overwritten. The source graph is not changed."
    const val NOTHING_TO_COPY = "Nothing to copy - destination already has all selected content"
    const val STALE = "Things changed - review again"
    const val STOP = "Stop"
    const val STOP_HELPER = "Pages already copied stay copied."
    const val STOP_AND_SWITCH = "Stop and switch"
    const val KEEP_COPYING = "Keep copying"
    const val DONE = "Done"
    const val BACK = "Back"
    const val RETRY = "Retry"
    const val RETRY_FAILED = "Retry failed"
    const val UNDO = "Undo this copy"
    const val CONTINUE = "Continue"
    const val RESUME = "Resume"
    const val DISMISS = "Dismiss"
    const val DISMISS_SNACKBAR = "You can run the same copy again any time."
    const val NOTHING_NEEDED = "Nothing needed copying."
    const val CHOOSE_ANOTHER = "Choose another destination"
    const val COMPLETE = "Copy finished"

    fun pages(n: Int): String = if (n == 1) "1 page" else "${groupThousands(n)} pages"

    fun copyButton(n: Int): String = "Copy ${pages(n)}"

    fun checking(checked: Int, total: Int): String =
        "Checking ${groupThousands(checked)} of ${groupThousands(total)} pages..."

    fun newLine(n: Int) = "$n new - will be created"
    fun combinedLine(n: Int) = "$n already exist - blocks will be combined (nothing removed)"
    fun unchangedLine(n: Int) = "$n unchanged - nothing to do"
    fun conflictLine(n: Int) = "$n may conflict - both versions kept, flagged"
    fun unreadableLine(n: Int) = "$n couldn't be read"
    fun assetsLine(n: Int) = "$n assets renamed to avoid overwriting"

    fun dryRunTitle(direction: CopyDirection, count: Int?, source: String, target: String): String {
        val what = if (count != null && count > 0) "Copy ${pages(count)}" else "Copy pages"
        return when (direction) {
            CopyDirection.Push -> "$what to \"$target\"?"
            CopyDirection.Pull -> "$what from \"$source\" into \"$target\"?"
        }
    }

    fun progressTitle(direction: CopyDirection, source: String, target: String): String = when (direction) {
        CopyDirection.Push -> "Copying to \"$target\""
        CopyDirection.Pull -> "Copying from \"$source\" into \"$target\""
    }

    fun progressCount(done: Int, total: Int) = "${groupThousands(done)} of ${groupThousands(total)} pages"

    fun backgroundNotice(target: String) = "Copy to $target continues in the background"

    /** Label of the sidebar / command-palette entry: push on Android/Desktop, pull on iOS/Web. */
    fun entryLabel(direction: CopyDirection): String = when (direction) {
        CopyDirection.Push -> "Copy pages to..."
        CopyDirection.Pull -> "Copy pages from..."
    }

    /** Graph-switcher row overflow item (pull only): copy [source] into the open graph. */
    fun rowOverflowLabel(source: String, current: String) = "Copy pages from $source to $current"

    fun pullSwitchConfirm(graph: String) = "A copy into $graph is running. Stop it and switch?"

    fun targetGone(target: String) = "$target is no longer available."
}

/** Wording for the result (S6) and interrupted-copy (S9) dialogs. */
object CopyOutcomeStrings {
    fun resultTitle(direction: CopyDirection, source: String, target: String): String = when (direction) {
        CopyDirection.Push -> "Copied to \"$target\""
        CopyDirection.Pull -> "Copied pages from \"$source\""
    }

    fun failedTitle(target: String) = "Couldn't copy to $target"

    fun stoppedBody(kept: Int, notCopied: Int): String {
        val first = if (kept == 1) "1 page was copied and kept." else "${groupThousands(kept)} pages were copied and kept."
        val second = if (notCopied == 1) "1 was not copied." else "${groupThousands(notCopied)} were not copied."
        return "$first $second"
    }

    const val STOPPED_HINT = "Run the copy again to continue, or undo."

    fun conflictsKept(n: Int) = if (n == 1) "1 conflict kept (both versions)" else "$n conflicts kept (both versions)"
    fun reviewConflicts(n: Int) = if (n == 1) "Review 1 conflict" else "Review $n conflicts"
    fun failedHeader(n: Int) = "Failed pages ($n)"

    fun interruptedTitle(target: String) = "A copy to \"$target\" was interrupted"

    fun interruptedBody(copied: Int, total: Int?): String =
        if (total != null && total > 0) "${groupThousands(copied)} of ${groupThousands(total)} pages were copied."
        else "${CopyDialogStrings.pages(copied)} ${if (copied == 1) "was" else "were"} copied."

    const val RESUME_SAFE = "Resuming is safe: finished pages won't repeat."
    const val CANNOT_RESUME = "Can't resume: the staged pages are gone. Start the copy again from Copy pages to..."
    const val OPEN_PICKER = "Open picker"
}

internal fun groupThousands(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * Shared dialog chrome. [dismissible] false blocks outside taps; Back/Esc always route to
 * [onDismissRequest] so each dialog maps it to its own safe action (Back, Stop, Done).
 */
@Composable
internal fun CopyDialogSurface(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            modifier = modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}

/** Stacked full-width actions: they survive 200% font scale and RTL without clipping. */
@Composable
internal fun ActionColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End,
    ) { content() }
}

internal val ActionButtonModifier: Modifier = Modifier.heightIn(min = 48.dp)

internal fun Modifier.politeLiveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
