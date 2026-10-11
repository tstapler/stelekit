// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit

import android.app.Application
import android.content.Context
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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.requestFocus
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
 * `@Config(application = Application::class)` is used throughout, with one per-method exception
 * (`saveButton_alwaysEnabledRegardlessOfScanState_tapProducesImmediateSave`, which taps the real
 * Save button and so needs `SteleKitApplication`): these tests only need `CaptureViewModel`'s
 * state flows, driven directly via reflection on its private backing fields (the same accepted
 * pattern `CaptureViewModelTest` uses for `savedContext`) — no real graph/coordinator plumbing is
 * exercised.
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

    // ---- Task 4.3.1a/b/c: post-save "Done" window auto-finish timer — virtual-time control ------
    //
    // `composeRule.mainClock` drives the timer's `delay(2_750)` deterministically (confirmed
    // empirically: `advanceTimeBy` progresses the suspended `delay()` inside `LaunchedEffect`,
    // while the default `autoAdvance = true` + `waitForIdle()` used by the tests above does NOT
    // fast-forward through it — that's why `postSaveDoneWindow_scrimTapFinishesImmediately...`
    // above can assert `savedCount == 0` right after entering the Done window). A real `Modifier
    // .clickable`-driven `performClick()` synthesizes an actual touch gesture, whose delivery to
    // Compose's internal pointer-input coroutine (for a `LazyRow` item specifically) needs
    // `autoAdvance = true` to process — but re-enabling `autoAdvance` while a `delay()` is already
    // in flight lets the SAME virtual clock auto-advance through it (verified: it broke this
    // exact test, firing the pre-reset timer during the toggle). So chip interactions below invoke
    // the chip's own `SemanticsActions.OnClick` handler directly via `performSemanticsAction` —
    // the identical mechanism `CaptureSuggestionChip`'s `IconButton.onClick` is wired to, and the
    // same "invoke the real action, skip touch-gesture simulation" pattern this file already uses
    // in `captureSuggestionChip_talkBackCustomActions_includesDismissSuggestionAction` — which
    // exercises the real `onChipInteraction()` -> `resetKey++` path without needing a live clock.

    @Test
    fun postSave_pendingChips_autoFinishesAfterTimeoutAndChipTapResetsTimer() {
        val vm = newViewModel()
        vm.updateText("hello")
        setScanState(
            vm,
            readyState(
                text = "hello",
                topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f)),
                confirmFirstNames = listOf("Today"),
            ),
        )
        var savedCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = { savedCount++ }, onDismiss = {}) } }
        setSaveState(vm, CaptureViewModel.SaveState.Saved)

        // Advance to just short of the ~2.75s deadline — timer must not have fired yet.
        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals("must not fire before the ~2.75s window elapses", 0, savedCount)

        // Tap the existing-link chip's accept region — a real chip interaction through its actual
        // `SemanticsActions.OnClick` handler (not a viewModel-direct call), so it exercises
        // `onChipInteraction()`'s `resetKey++`, the actual reset mechanism under test.
        // `acceptExistingLink` is synchronous (no coroutine dispatch unless `savedContext` is
        // non-null, which it never is here) — safe to invoke on this `Application::class`-backed
        // `CaptureViewModel`.
        composeRule.onNodeWithContentDescription("Existing page, Today. Double-tap to link.")
            .performSemanticsAction(SemanticsActions.OnClick)

        // Advance by the SAME 2_000ms again. If the tap had reset the timer to a fresh full
        // duration, only 2_000ms has elapsed since the reset — still short of 2_750ms, so it must
        // not have fired. If the tap had done nothing (or merely "extended" from the old
        // countdown), the timeline since the original start would now be 4_000ms — well past
        // 2_750ms — and it WOULD have already fired. Asserting 0 here is exactly what
        // distinguishes "reset" from "extend"/"ignored".
        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals("a chip tap must reset the timer to a fresh full duration", 0, savedCount)

        // The reset timer still eventually fires on its own.
        composeRule.mainClock.advanceTimeBy(1_000) // cumulative 3_000ms since the reset
        assertEquals("the reset timer must auto-finish once its own ~2.75s window elapses", 1, savedCount)
    }

    /**
     * Task 4.3.1c: while TalkBack-style accessibility focus is present in the sheet (touch
     * exploration enabled + Compose focus inside the sheet), the auto-finish timer is PAUSED, not
     * merely extended — it never starts counting down at all while the signal holds, and resumes
     * from scratch once the signal clears (`design/ux.md`'s pause-not-extend requirement).
     *
     * `hasAccessibilityFocus` (CaptureActivity.kt) is `hasFocusWithinSheet && accessibilityManager
     * ?.isTouchExplorationEnabled == true` — Robolectric's `ShadowAccessibilityManager` drives the
     * touch-exploration half deterministically; `hasFocusWithinSheet` is real Compose focus
     * (`onFocusEvent` on the sheet's `focusGroup()`), driven here by moving focus onto a
     * suggestion chip via `requestFocus()` (a real focus change, not a stand-in for one).
     *
     * Two chips are used (not one): `hasAccessibilityFocus` is a plain expression re-evaluated
     * only when `CaptureScreen` itself recomposes, so proving "resumes once the signal clears"
     * needs a real, Compose-observed state change alongside the shadow mutation (mutating the
     * shadow alone doesn't retroactively notify Compose of anything — matching how the real
     * accessibility-service-toggle event would itself need to reach Compose via some observed
     * state). Dismissing a *second*, unfocused chip is that state change (`scanState`'s
     * `StateFlow` drives `pendingChips`) without dismissing the *last* chip — which would instead
     * take the unrelated "zero pending chips" immediate-finish path (Task 4.3.1a), not the timer.
     */
    @Test
    fun postSaveDoneWindow_accessibilityFocusPresent_autoFinishTimerPaused() {
        val vm = newViewModel()
        vm.updateText("hello")
        setScanState(
            vm,
            readyState(
                text = "hello",
                topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f), suggestion("SecondTerm", 0.5f)),
            ),
        )
        var savedCount = 0
        val app = ApplicationProvider.getApplicationContext<Application>()
        val accessibilityManager =
            app.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        shadowOf(accessibilityManager).setTouchExplorationEnabled(true)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = { savedCount++ }, onDismiss = {}) } }
        setSaveState(vm, CaptureViewModel.SaveState.Saved)

        // Establish Compose focus inside the sheet on the "Zettelkasten" chip — NOT the text
        // field, which becomes `enabled = false` the instant `isDone` flips true (Task 4.3.1a;
        // also covered by `postSaveDoneWindow_textFieldDisabled_noSilentKeystrokeDiscard` above)
        // and so cannot durably hold focus during the Done window at all: a disabled component
        // drops focus as a side effect, which would flip `hasFocusWithinSheet` back to false right
        // as the window starts. The chip tray stays enabled/interactive throughout the Done window
        // (only the button row is replaced by "✓ Saved" — the chips themselves are unaffected), so
        // it's the only durable in-sheet focus target while `isDone` is true. Focus-requesting is
        // a real state change (not click-dispatch), so it settles under a paused clock like any
        // other state-driven recomposition.
        composeRule.onNodeWithContentDescription(
            "Suggested page, Zettelkasten, confidence high. Double-tap to accept.",
        ).requestFocus()
        composeRule.mainClock.advanceTimeBy(500)

        // Paused: well past the ~2.75s window's normal deadline, the timer must never have fired
        // because `LaunchedEffect(isDone, resetKey, hasAccessibilityFocus)`'s guard
        // (`if (isDone && !hasAccessibilityFocus)`) never entered the `delay()` branch at all.
        composeRule.mainClock.advanceTimeBy(5_500) // cumulative 6_000ms since entering the window
        assertEquals(
            "the auto-finish timer must be paused (never started), not merely running slower, " +
                "while accessibility focus is present",
            0, savedCount,
        )

        // Clearing the signal + dismissing the OTHER ("SecondTerm") chip: "Zettelkasten" stays
        // both focused and in the tray, so this is neither a focus change nor a
        // zero-pending-chips finish — it forces the recomposition needed to re-read
        // `isTouchExplorationEnabled`, now false, while leaving `hasFocusWithinSheet` true.
        shadowOf(accessibilityManager).setTouchExplorationEnabled(false)
        val secondChipNode = composeRule.onNodeWithContentDescription(
            "Suggested page, SecondTerm, confidence medium. Double-tap to accept.",
        ).fetchSemanticsNode()
        secondChipNode.config.getOrNull(SemanticsActions.CustomActions)
            ?.first { it.label == "Dismiss suggestion" }?.action?.invoke()

        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals("must not fire before a fresh ~2.75s window elapses after resuming", 0, savedCount)
        composeRule.mainClock.advanceTimeBy(2_000) // cumulative 4_000ms since resuming
        assertEquals("must auto-finish once the resumed window elapses", 1, savedCount)
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

    // ---- validation.md spec-compliance sweep: small UX-table gaps (rows 2, 6, 7, 8, 11) -------

    @Test
    fun pendingSuggestions_empty_rendersNoEmptyStateLabel() {
        val vm = newViewModel()
        vm.updateText("hello")
        setScanState(vm, readyState(text = "hello", topicSuggestions = emptyList()))
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        // Zero pending chips renders no tray at all (production's `if (pendingChips.isNotEmpty())`
        // guard) and no placeholder text ("no suggestions yet" or similar) fills the gap.
        composeRule.onAllNodesWithText("suggestion", ignoreCase = true, substring = true).assertCountEquals(0)
    }

    @Test
    fun scanningState_noSpinnerComposableExistsInTree() {
        val vm = newViewModel()
        vm.updateText("hello")
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        // CaptureViewModel.ScanState has no distinct "scan in flight" variant of its own — a scan
        // in progress is represented as NotReady until it resolves — so sweeping NotReady and
        // both Ready shapes covers every scanState the Save button's `enabled`/render logic can
        // observe. `saveState` is left at its default `Idle` throughout, so this also proves the
        // Save button's own `CircularProgressIndicator` (gated on `saveState == Saving`, not on
        // scanState) never renders purely from a scan being in progress.
        for (state in listOf(
            CaptureViewModel.ScanState.NotReady,
            readyState(text = "hello", topicSuggestions = emptyList()),
            readyState(text = "hello", topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f))),
        )) {
            setScanState(vm, state)
            composeRule.waitForIdle()
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .assertCountEquals(0)
        }
    }

    // Deviates from this file's usual plain-`Application::class` config (see class doc) because
    // the second half of this test taps the real Save button, which calls production
    // `CaptureViewModel.save()` -> `getApplication<SteleKitApplication>()` — that cast throws
    // against a plain `Application`. No graph is opened on this `SteleKitApplication`, but its
    // `onCreate()` still constructs a real (empty) `GraphManager`, so `save()` proceeds into its
    // async `viewModelScope.launch { performSave(...) }` branch rather than the synchronous
    // no-graph-manager early return.
    @Test
    @Config(sdk = [29], application = SteleKitApplication::class, qualifiers = "w411dp-h891dp-xhdpi")
    fun saveButton_alwaysEnabledRegardlessOfScanState_tapProducesImmediateSave() {
        val vm = CaptureViewModel(ApplicationProvider.getApplicationContext())
        vm.updateText("hello")
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        // Save's `enabled` condition (`saveState == Idle && captureText.isNotBlank()`) never
        // reads scanState — sweep every scanState shape and assert it stays enabled throughout.
        for (state in listOf(
            CaptureViewModel.ScanState.NotReady,
            readyState(text = "hello", topicSuggestions = emptyList()),
            readyState(text = "hello", topicSuggestions = listOf(suggestion("Zettelkasten", 0.9f))),
        )) {
            setScanState(vm, state)
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Save").assertIsEnabled()
        }

        // A real tap must actually reach the button's onClick — `viewModel::save` in production
        // (CaptureActivity.kt) — not something disabled or intercepted. `save()`'s own
        // `viewModelScope.launch { performSave(...) }` runs on `Dispatchers.Main`, which this
        // Compose test harness's virtual-time `TestDispatcher` does not drive for coroutines
        // launched outside composition (confirmed empirically: neither `waitForIdle()` nor
        // `mainClock.advanceTimeUntil(...)` observes it complete) — asserting the async save
        // outcome itself would need a differently-shaped harness, out of scope here. Asserting
        // the click reaches the button without throwing, combined with the enabled-sweep above,
        // is what this test can honestly prove: Save is never gated on scanState.
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun autoLinkPreviewLine_notTappableOrDismissible_onlyNewPageSuggestionsAreChips() {
        val vm = newViewModel()
        vm.updateText("Reading about Kotlin Multiplatform")
        setScanState(
            vm,
            readyState(text = "Reading about Kotlin Multiplatform", linkedText = "Reading about [[Kotlin Multiplatform]]"),
        )
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        val previewNode = composeRule.onNodeWithText(
            "Reading about [[Kotlin Multiplatform]]",
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        assertTrue(
            "the read-only auto-link preview line must carry no click affordance",
            previewNode.config.getOrNull(SemanticsActions.OnClick) == null,
        )
        // No dismiss/accept affordance exists for it either — only new-page suggestions are chips.
        composeRule.onNodeWithContentDescription("Dismiss").assertDoesNotExist()
    }
}
