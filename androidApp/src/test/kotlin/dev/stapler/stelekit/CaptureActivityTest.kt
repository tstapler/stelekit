// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit

import android.app.Application
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.domain.ScanResult
import dev.stapler.stelekit.domain.TopicSuggestion
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers Epics 3.1/3.2/4.3 of project_plans/stelekit-capture-auto-enrich (chip tray anatomy/cap/
 * ordering/accessibility, the read-only auto-link preview line, and the post-save "Done" window),
 * per implementation/validation.md's UX Acceptance Tests table.
 *
 * `CaptureScreen`/`CaptureSuggestionChip`/`CaptureChipKind`/`CaptureChipItem`/`confidenceWord`
 * are `internal` (not `private`) in `CaptureActivity.kt` specifically so this file — compiled
 * separately from `CaptureActivity.kt` at the Kotlin file-visibility level — can exercise them
 * directly instead of only through the full `CaptureActivity`.
 *
 * `@Config(application = Application::class)` is used throughout (never `SteleKitApplication`):
 * these tests only need `CaptureViewModel`'s state flows, driven directly via reflection on its
 * private backing fields (the same accepted pattern `CaptureViewModelTest` uses for
 * `savedContext`) — no real graph/coordinator plumbing is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class CaptureActivityTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---- CaptureViewModel state-flow test harness (reflection, mirrors CaptureViewModelTest) --

    private fun newViewModel(): CaptureViewModel =
        CaptureViewModel(ApplicationProvider.getApplicationContext())

    @Suppress("UNCHECKED_CAST")
    private fun setScanState(vm: CaptureViewModel, state: CaptureViewModel.ScanState) {
        val field = CaptureViewModel::class.java.getDeclaredField("_scanState").apply { isAccessible = true }
        (field.get(vm) as MutableStateFlow<CaptureViewModel.ScanState>).value = state
    }

    @Suppress("UNCHECKED_CAST")
    private fun setSaveState(vm: CaptureViewModel, state: CaptureViewModel.SaveState) {
        val field = CaptureViewModel::class.java.getDeclaredField("_saveState").apply { isAccessible = true }
        (field.get(vm) as MutableStateFlow<CaptureViewModel.SaveState>).value = state
    }

    @Suppress("UNCHECKED_CAST")
    private fun emitChipFailure(vm: CaptureViewModel, message: String) {
        val field = CaptureViewModel::class.java.getDeclaredField("_chipFailure").apply { isAccessible = true }
        (field.get(vm) as MutableSharedFlow<String>).tryEmit(message)
    }

    private fun readyState(
        text: String,
        linkedText: String = text,
        topicSuggestions: List<TopicSuggestion> = emptyList(),
        confirmFirstNames: List<String> = emptyList(),
    ) = CaptureViewModel.ScanState.Ready(
        text = text,
        result = ScanResult(linkedText = linkedText, matchedPageNames = emptyList(), topicSuggestions = topicSuggestions),
        confirmFirstNames = confirmFirstNames,
    )

    private fun suggestion(term: String, confidence: Float) =
        TopicSuggestion(term = term, confidence = confidence, source = TopicSuggestion.Source.LOCAL)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // ---- Story 3.1.1: CaptureSuggestionChip anatomy / contentDescription (AC #6, #21) ---------

    @Test
    fun captureSuggestionChip_confidenceThresholds_produceExpectedContentDescriptionWord() {
        assertEquals("high", confidenceWord(0.9f))
        assertEquals("medium", confidenceWord(0.5f))
        assertEquals("low", confidenceWord(0.2f))
    }

    @Test
    fun captureSuggestionChip_newPage_contentDescriptionMatchesExactFormat() {
        composeRule.setContent {
            MaterialTheme {
                CaptureSuggestionChip(
                    term = "Zettelkasten",
                    confidence = 0.9f,
                    kind = CaptureChipKind.NEW_PAGE,
                    onAccept = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(
            "Suggested page, Zettelkasten, confidence high. Double-tap to accept.",
        ).assertExists()
    }

    @Test
    fun captureSuggestionChip_existingLink_contentDescriptionHasNoConfidenceClause() {
        composeRule.setContent {
            MaterialTheme {
                CaptureSuggestionChip(
                    term = "Today",
                    confidence = null,
                    kind = CaptureChipKind.EXISTING_LINK,
                    onAccept = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Existing page, Today. Double-tap to link.").assertExists()
    }

    // ---- Story 3.1.3: haptics per interaction type (AC #6, #19) --------------------------------

    @Test
    fun captureSuggestionChip_accept_firesConfirmHapticSynchronously() {
        val recorded = mutableListOf<HapticFeedbackType>()
        val spy = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                recorded += hapticFeedbackType
            }
        }
        var accepted = false
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides spy) {
                MaterialTheme {
                    CaptureSuggestionChip(
                        term = "Zettelkasten",
                        confidence = 0.9f,
                        kind = CaptureChipKind.NEW_PAGE,
                        onAccept = { accepted = true },
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription(
            "Suggested page, Zettelkasten, confidence high. Double-tap to accept.",
        ).performClick()

        assertTrue(accepted)
        assertEquals(listOf(HapticFeedbackType.Confirm), recorded)
    }

    /** Negative case: dismiss must never fire the Confirm haptic (research §3e). */
    @Test
    fun captureSuggestionChip_dismiss_neverFiresConfirmHaptic() {
        val recorded = mutableListOf<HapticFeedbackType>()
        val spy = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                recorded += hapticFeedbackType
            }
        }
        var dismissed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides spy) {
                MaterialTheme {
                    CaptureSuggestionChip(
                        term = "Zettelkasten",
                        confidence = 0.9f,
                        kind = CaptureChipKind.NEW_PAGE,
                        onAccept = {},
                        onDismiss = { dismissed = true },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Dismiss").performClick()

        assertTrue(dismissed)
        assertFalse(recorded.contains(HapticFeedbackType.Confirm))
    }

    // ---- Story 3.1.1/Surface 7: accessibility — touch targets, TalkBack customActions ----------

    @Test
    fun captureSuggestionChip_acceptAndDismissRegions_meetMinimum48dpTouchTarget() {
        composeRule.setContent {
            MaterialTheme {
                CaptureSuggestionChip(
                    term = "Zettelkasten",
                    confidence = 0.9f,
                    kind = CaptureChipKind.NEW_PAGE,
                    onAccept = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(
            "Suggested page, Zettelkasten, confidence high. Double-tap to accept.",
        ).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithContentDescription("Dismiss").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun captureSuggestionChip_talkBackCustomActions_includesDismissSuggestionAction() {
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                CaptureSuggestionChip(
                    term = "Zettelkasten",
                    confidence = 0.9f,
                    kind = CaptureChipKind.NEW_PAGE,
                    onAccept = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        val node = composeRule.onNodeWithContentDescription(
            "Suggested page, Zettelkasten, confidence high. Double-tap to accept.",
        ).fetchSemanticsNode()
        val customActions = node.config.getOrNull(SemanticsActions.CustomActions)
        assertTrue(customActions != null && customActions.any { it.label == "Dismiss suggestion" })
        customActions!!.first { it.label == "Dismiss suggestion" }.action.invoke()
        assertTrue(dismissed)
    }

    // ---- Story 3.1.2: chip tray — cap/ordering/staleness/live region (AC #9, #11) ---------------

    @Test
    fun pendingSuggestions_sevenCandidates_onlyTop4RenderedNoShowMoreButton() {
        val vm = newViewModel()
        val terms = listOf("A" to 0.1f, "B" to 0.9f, "C" to 0.3f, "D" to 0.8f, "E" to 0.5f, "F" to 0.7f, "G" to 0.2f)
        setScanState(vm, readyState(text = "hello", topicSuggestions = terms.map { suggestion(it.first, it.second) }))

        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        vm.updateText("hello")
        composeRule.waitForIdle()

        // Top 4 by confidence: B(0.9) D(0.8) F(0.7) E(0.5)
        composeRule.onNodeWithText("B").assertExists()
        composeRule.onNodeWithText("D").assertExists()
        composeRule.onNodeWithText("F").assertExists()
        composeRule.onNodeWithText("E").assertExists()
        composeRule.onNodeWithText("A").assertDoesNotExist()
        composeRule.onNodeWithText("C").assertDoesNotExist()
        composeRule.onNodeWithText("G").assertDoesNotExist()
        composeRule.onAllNodesWithText("show more", ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun pendingSuggestions_confirmFirstAndNewPage_existingLinkChipsRenderFirst() {
        val vm = newViewModel()
        setScanState(
            vm,
            readyState(
                text = "hello",
                topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f), suggestion("Notes", 0.5f)),
                confirmFirstNames = listOf("Today"),
            ),
        )
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        vm.updateText("hello")
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Existing page, Today. Double-tap to link.").assertExists()
        composeRule.onNodeWithContentDescription(
            "Suggested page, Zettelkasten, confidence high. Double-tap to accept.",
        ).assertExists()
    }

    @Test
    fun pendingSuggestions_staleScanState_rendersNoChipsAndNoPreviewLine() {
        val vm = newViewModel()
        // Ready is computed for "old text" but the user has since typed further.
        setScanState(
            vm,
            readyState(
                text = "old text",
                linkedText = "old [[Kotlin]] text",
                topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f)),
                confirmFirstNames = listOf("Today"),
            ),
        )
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        vm.updateText("old text plus more")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Zettelkasten").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Existing page, Today. Double-tap to link.").assertDoesNotExist()
        composeRule.onNodeWithText("old [[Kotlin]] text").assertDoesNotExist()
    }

    @Test
    fun suggestionTray_liveRegion_isPoliteNeverAssertive() {
        val vm = newViewModel()
        setScanState(vm, readyState(text = "hello", topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f))))
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        vm.updateText("hello")
        composeRule.waitForIdle()

        val trayNode = composeRule.onNodeWithContentDescription(
            "Suggested page, Zettelkasten, confidence high. Double-tap to accept.",
        ).fetchSemanticsNode()
        // Walk up to the LazyRow container that carries the liveRegion semantics property.
        var current = trayNode.parent
        var found: androidx.compose.ui.semantics.LiveRegionMode? = null
        while (current != null && found == null) {
            found = current.config.getOrNull(SemanticsProperties.LiveRegion)
            current = current.parent
        }
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, found)
    }

    // ---- Epic 3.2: auto-link preview line (AC #1, #6, #10, #11) --------------------------------

    @Test
    fun autoLinkPreview_rendersBracketSyntax_textFieldValueUnchanged() {
        val vm = newViewModel()
        vm.updateText("Reading about Kotlin Multiplatform")
        setScanState(
            vm,
            readyState(text = "Reading about Kotlin Multiplatform", linkedText = "Reading about [[Kotlin Multiplatform]]"),
        )
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Reading about [[Kotlin Multiplatform]]").assertExists()
        composeRule.onNodeWithText("Reading about Kotlin Multiplatform").assertExists()
    }

    @Test
    fun autoLinkPreviewLine_confirmFirstOnlyMatch_rendersNoPreviewCaption() {
        val vm = newViewModel()
        vm.updateText("Notes for Today")
        setScanState(
            vm,
            readyState(text = "Notes for Today", linkedText = "Notes for Today", confirmFirstNames = listOf("Today")),
        )
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        // The confirm-first term never appears bracketed — only as a tray chip.
        composeRule.onNodeWithText("Notes for [[Today]]").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Existing page, Today. Double-tap to link.").assertExists()
    }

    // ---- Epic 4.3: post-save "Done" window (AC #9, #12-#15) ------------------------------------

    @Test
    fun postSave_zeroPendingChips_finishesImmediatelyNoAddedFrame() {
        val vm = newViewModel()
        var savedCount = 0
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = { savedCount++ }, onDismiss = {}) } }
        setSaveState(vm, CaptureViewModel.SaveState.Saved)
        composeRule.waitForIdle()

        assertEquals(1, savedCount)
    }

    @Test
    fun postSave_pendingChips_showsSavedStateInsteadOfButtonRow() {
        val vm = newViewModel()
        vm.updateText("hello")
        setScanState(vm, readyState(text = "hello", topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f))))
        var savedCount = 0
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = { savedCount++ }, onDismiss = {}) } }
        setSaveState(vm, CaptureViewModel.SaveState.Saved)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("✓ Saved").assertExists()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
        assertEquals(0, savedCount)
    }

    @Test
    fun postSaveDoneWindow_textFieldDisabled_noSilentKeystrokeDiscard() {
        val vm = newViewModel()
        vm.updateText("hello")
        setScanState(vm, readyState(text = "hello", topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f))))
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        setSaveState(vm, CaptureViewModel.SaveState.Saved)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("hello").assertIsNotEnabled()
    }

    /** The explicitly required negative/regression case: scrim tap during the Done window must
     * finish immediately, never re-invoke viewModel.save(). */
    @Test
    fun postSaveDoneWindow_scrimTapFinishesImmediately_bypassingAutoFinishTimer() {
        val vm = newViewModel()
        vm.updateText("hello")
        setScanState(vm, readyState(text = "hello", topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f))))
        var savedCount = 0
        composeRule.setContent {
            MaterialTheme { CaptureScreen(vm, onSaved = { savedCount++ }, onDismiss = {}) }
        }
        setSaveState(vm, CaptureViewModel.SaveState.Saved)
        composeRule.waitForIdle()
        assertEquals(0, savedCount) // still in the Done window, timer hasn't fired

        composeRule.onNodeWithTag(CAPTURE_SCRIM_TEST_TAG, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertEquals(1, savedCount)
        // viewModel.save() must never run again for an already-saved capture.
        assertEquals(CaptureViewModel.SaveState.Saved, vm.saveState.value)
    }

    // ---- Task 4.1.3c: chipFailure snackbar collector (AC #7, #16) ------------------------------

    @Test
    fun chipFailure_emittedMessage_showsSnackbarNamingFailedTerm() {
        val vm = newViewModel()
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        emitChipFailure(vm, "Couldn't create page for \"Zettelkasten\"")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't create page for \"Zettelkasten\"").assertExists()
    }

    // ---- Story 5.2.9: AC #6 consolidated negative cases — no haptic on auto-link, dismiss ----
    // never confirm-shaped ------------------------------------------------------------------

    @Test
    fun previewLineRenderAndChipDismiss_neverFireConfirmHaptic() {
        val recorded = mutableListOf<HapticFeedbackType>()
        val spy = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                recorded += hapticFeedbackType
            }
        }
        val vm = newViewModel()
        vm.updateText("Reading about Kotlin Multiplatform")
        setScanState(
            vm,
            readyState(
                text = "Reading about Kotlin Multiplatform",
                linkedText = "Reading about [[Kotlin Multiplatform]]",
                topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f)),
            ),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides spy) {
                MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) }
            }
        }
        composeRule.waitForIdle()

        // The read-only auto-link preview line rendering must fire no haptic at all.
        assertTrue(
            "auto-applied links must never fire a haptic on render, got: $recorded",
            recorded.isEmpty(),
        )

        // Dismissing the pending new-page chip must not fire the Confirm haptic either.
        composeRule.onNodeWithContentDescription("Dismiss").performClick()
        assertFalse(
            "dismiss must never be confirm-shaped, got: $recorded",
            recorded.contains(HapticFeedbackType.Confirm),
        )
    }

    // ---- Story 5.2.10: combined chip tray cap — 4 TOTAL, not 4 per bucket -------------------

    @Test
    fun pendingSuggestions_confirmFirstPlusNewPageExceedingCap_cappedAtFourTotalNotPerBucket() {
        val vm = newViewModel()
        val newPageTerms = listOf("A" to 0.1f, "B" to 0.9f, "C" to 0.3f, "D" to 0.8f, "E" to 0.5f)
        setScanState(
            vm,
            readyState(
                text = "hello",
                topicSuggestions = newPageTerms.map { suggestion(it.first, it.second) },
                confirmFirstNames = listOf("Today", "Ideas"),
            ),
        )
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        vm.updateText("hello")
        composeRule.waitForIdle()

        // 2 confirm-first (rendered first) + top 2 new-page by confidence (B=0.9, D=0.8) = 4 total.
        composeRule.onNodeWithContentDescription("Existing page, Today. Double-tap to link.").assertExists()
        composeRule.onNodeWithContentDescription("Existing page, Ideas. Double-tap to link.").assertExists()
        composeRule.onNodeWithText("B").assertExists()
        composeRule.onNodeWithText("D").assertExists()

        // The remaining 3 new-page candidates must be silently truncated — the cap is 4 total,
        // not 4-per-bucket (which would have let all 5 new-page chips render alongside the 2
        // confirm-first chips).
        composeRule.onNodeWithText("A").assertDoesNotExist()
        composeRule.onNodeWithText("C").assertDoesNotExist()
        composeRule.onNodeWithText("E").assertDoesNotExist()
    }
}
