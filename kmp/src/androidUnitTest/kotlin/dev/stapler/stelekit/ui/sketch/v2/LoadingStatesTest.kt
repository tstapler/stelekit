// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.sketch.SketchPreviewContent
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchLoadingStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 3.6.8c (UX-94): a long export, import, library open or embed thumbnail shows a labelled progress indicator with Cancel
 * where it can stop, announced at start and end only; a thumbnail shows a neutral placeholder rather than a blank block.
 * Robolectric with a fake clock; Gradle-only like the other Compose host tests (see BUILD.bazel). It asserts the semantics
 * handed to a screen reader, not what any reader speaks.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w800dp-h800dp-mdpi")
class LoadingStatesTest {
    @get:Rule
    val rule = createComposeRule()

    private var active by mutableStateOf(false)
    private var progress by mutableFloatStateOf(0f)
    private var cancels = 0
    private var operation by mutableStateOf("")

    private fun announcement() = rule.onNodeWithTag(LoadingTags.ANNOUNCE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single().orEmpty()

    private fun change(block: () -> Unit) {
        rule.runOnUiThread { block(); Snapshot.sendApplyNotifications() }
    }

    private fun showGate(name: String, determinate: Boolean = false, cancellable: Boolean = true) {
        rule.mainClock.autoAdvance = false
        operation = name
        rule.setContent {
            LoadingGate(active, operation, progress = if (determinate) ({ progress }) else null, onCancel = if (cancellable) ({ cancels++ }) else null)
        }
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun ux94_longOperations_should_ShowLabelledProgressAnnouncedAtStartAndEnd() {
        showGate(SketchLoadingStrings.EXPORT)
        change { active = true }
        rule.mainClock.advanceTimeBy(EARLY_MS)
        rule.onNodeWithTag(LoadingTags.INDICATOR).assertDoesNotExist()
        assertEquals("", announcement(), "nothing is announced for an operation that may still finish quickly")

        rule.mainClock.advanceTimeBy(LoadingPolicy.SHOW_AFTER_MS)
        rule.onNodeWithTag(LoadingTags.INDICATOR).assertExists()
        rule.onNodeWithTag(LoadingTags.NAME).assertTextEquals(SketchLoadingStrings.EXPORT)
        assertEquals("Exporting...", announcement())

        change { active = false }
        rule.mainClock.advanceTimeBy(LATE_MS)
        rule.onNodeWithTag(LoadingTags.INDICATOR).assertDoesNotExist()
        assertEquals("Exporting finished", announcement())
    }

    @Test
    fun ux94_longOperations_should_ShowLabelledProgress_When_ExportLibraryImportEmbedExceed200ms() {
        showGate(SketchLoadingStrings.EXPORT)
        for (name in listOf(SketchLoadingStrings.EXPORT, SketchLoadingStrings.LIBRARY, SketchLoadingStrings.IMPORT)) {
            change { operation = name }
            change { active = true }
            rule.mainClock.advanceTimeBy(LoadingPolicy.SHOW_AFTER_MS + LATE_MS)
            rule.onNodeWithTag(LoadingTags.NAME).assertTextEquals(name)
            assertEquals(LoadingPolicy.startText(name), announcement())
            change { active = false }
            rule.mainClock.advanceTimeBy(LATE_MS)
        }
    }

    @Test
    fun anOperationThatFinishesInTime_should_NeverFlashAnIndicator_OrAnnounceAnEnd() {
        showGate(SketchLoadingStrings.IMPORT)
        change { active = true }
        rule.mainClock.advanceTimeBy(EARLY_MS)
        change { active = false }
        rule.mainClock.advanceTimeBy(LoadingPolicy.SHOW_AFTER_MS * 2)
        rule.onNodeWithTag(LoadingTags.INDICATOR).assertDoesNotExist()
        assertEquals("", announcement())
    }

    @Test
    fun progressUpdates_should_ChangeTheVisibleLine_ButNeverTheAnnouncement() {
        showGate(SketchLoadingStrings.EXPORT, determinate = true)
        change { active = true }
        rule.mainClock.advanceTimeBy(LoadingPolicy.SHOW_AFTER_MS + LATE_MS)
        val started = announcement()
        change { progress = 0.4f }
        rule.mainClock.advanceTimeBy(LATE_MS)
        rule.onNodeWithTag(LoadingTags.NAME).assertTextEquals("Exporting 40%")
        assertEquals(started, announcement(), "progress is not announced per update")
        val range = rule.onNodeWithTag(LoadingTags.BAR).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
        assertEquals(0.4f, range?.current)
    }

    @Test
    fun cancel_should_BeAnnouncedByNameAndRunTheHostsCancel_WhereTheOperationCanStop() {
        showGate(SketchLoadingStrings.IMPORT)
        change { active = true }
        rule.mainClock.advanceTimeBy(LoadingPolicy.SHOW_AFTER_MS + LATE_MS)
        val description = rule.onNodeWithTag(LoadingTags.CANCEL).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertEquals(listOf("Cancel: Importing"), description)
        rule.onNodeWithTag(LoadingTags.CANCEL).performClick()
        assertEquals(1, cancels)
    }

    @Test
    fun anUncancellableOperation_should_ShowNoCancel() {
        showGate(SketchLoadingStrings.LIBRARY, cancellable = false)
        change { active = true }
        rule.mainClock.advanceTimeBy(LoadingPolicy.SHOW_AFTER_MS + LATE_MS)
        rule.onNodeWithTag(LoadingTags.CANCEL).assertDoesNotExist()
    }

    @Test
    fun anEmbedThumbnailThatIsLoading_should_ShowANeutralPlaceholderNotABlankBlock() {
        rule.setContent { SketchPreviewContent(preview = null) }
        rule.waitForIdle()
        rule.onNodeWithTag(LoadingTags.PLACEHOLDER).assertIsDisplayed()
        val description = rule.onNodeWithTag(LoadingTags.PLACEHOLDER).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)
        assertTrue(description == listOf(SketchLoadingStrings.THUMBNAIL), "the placeholder says what it is waiting for")
    }

    private companion object {
        /** Well inside the 200 ms window, and a few frames past it: the fake clock only recomposes on a frame boundary. */
        const val EARLY_MS = 100L
        const val LATE_MS = 64L
    }
}
