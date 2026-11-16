// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 3.6.6c (UX-74): the empty canvas offers "Pick a tool and draw, or import an .excalidraw file" with Import and "Keyboard
 * shortcuts", shown through the onboarding sequencer: it waits for the coach mark, goes with the first element and comes back
 * when the sheet is emptied. Robolectric; Gradle-only like the other Compose host tests (see BUILD.bazel).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1280dp-h800dp-mdpi")
class EmptyStateTest {
    @get:Rule
    val rule = createComposeRule()

    private var empty by mutableStateOf(true)
    private var imports = 0
    private var shortcuts = 0

    private fun banner(settings: InMemorySettings = InMemorySettings()) =
        BannerSlotState(SketchNoticeQueue(sequencer = OnboardingSequencer(OnboardingCounters(settings))))

    private fun show(banner: BannerSlotState) {
        rule.setContent {
            SketchScreenV2(
                SketchEditorState(),
                SketchShellConfig(
                    ToolStripModel(false), PointerClass.MouseOnly, banner = banner, sheetEmpty = empty,
                    emptyActions = EmptyStateActions(onImport = { imports++ }, onShortcuts = { shortcuts++ }),
                ),
                window = WindowDp(1280, 800),
            )
        }
        rule.waitForIdle()
    }

    /** A device whose coach mark was already shown, so only the empty state is in play. */
    private fun spent(): BannerSlotState = banner(InMemorySettings().also { OnboardingCounters(it).retire(OnboardingItem.CoachMark) })

    @Test
    fun anEmptySheet_should_ShowThePromptAndBothActions() {
        show(spent())
        rule.onNodeWithTag(EmptyStateTags.ROOT).assertIsDisplayed()
        rule.onNodeWithTag(EmptyStateTags.PROMPT).assertTextEquals("Pick a tool and draw, or import an .excalidraw file")
        rule.onNodeWithTag(EmptyStateTags.IMPORT).assertTextEquals(SketchStrings.Empty.IMPORT).assertHasClickAction()
        rule.onNodeWithTag(EmptyStateTags.SHORTCUTS).assertTextEquals("Keyboard shortcuts").assertHasClickAction()
    }

    @Test
    fun theActions_should_RunTheHostsImportAndOpenTheShortcuts() {
        show(spent())
        rule.onNodeWithTag(EmptyStateTags.IMPORT).performClick()
        rule.onNodeWithTag(EmptyStateTags.SHORTCUTS).performClick()
        assertEquals(1, imports)
        assertEquals(1, shortcuts)
    }

    @Test
    fun thePrompt_should_BeAPoliteLiveRegion() {
        show(spent())
        val config = rule.onNodeWithTag(EmptyStateTags.PROMPT).fetchSemanticsNode().config
        assertTrue(SemanticsProperties.LiveRegion in config)
    }

    @Test
    fun theFirstElement_should_RemoveIt_And_EmptyingTheSheetAgainShouldBringItBack() {
        show(spent())
        rule.onNodeWithTag(EmptyStateTags.ROOT).assertIsDisplayed()
        empty = false
        rule.waitForIdle()
        rule.onNodeWithTag(EmptyStateTags.ROOT).assertDoesNotExist()
        empty = true
        rule.waitForIdle()
        rule.onNodeWithTag(EmptyStateTags.ROOT).assertIsDisplayed()
    }

    @Test
    fun theEmptyState_should_WaitForTheCoachMark_ThenAppear() {
        show(banner())
        rule.onNodeWithTag(CoachMarkTags.ROW).assertIsDisplayed()
        rule.onNodeWithTag(EmptyStateTags.ROOT).assertDoesNotExist()
        rule.onNodeWithTag(CoachMarkTags.GOT_IT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(EmptyStateTags.ROOT).assertIsDisplayed()
    }

    @Test
    fun aSheetWithElements_should_NeverShowIt() {
        empty = false
        show(spent())
        rule.onNodeWithTag(EmptyStateTags.ROOT).assertDoesNotExist()
    }
}
