package dev.stapler.stelekit.ui.annotate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EditorChromePolicyTest {

    @Test
    fun stripPriority_should_FollowUxTable() {
        // resolve(error, toolPrompt, firstTimeHint, statusText, voiceHint); rows follow ux.md 3.5.
        assertEquals(StripPriority.IDLE, StripPriority.resolve(false, false, false, false, false))
        assertEquals(StripPriority.VOICE_HINT, StripPriority.resolve(false, false, false, false, true))
        assertEquals(StripPriority.STATUS_TEXT, StripPriority.resolve(false, false, false, true, true))
        assertEquals(StripPriority.FIRST_TIME_HINT, StripPriority.resolve(false, false, true, true, true))
        assertEquals(StripPriority.TOOL_PROMPT, StripPriority.resolve(false, true, true, true, true))
        assertEquals(StripPriority.ERROR, StripPriority.resolve(true, true, true, true, true))
        assertEquals(StripPriority.ERROR, StripPriority.resolve(true, false, false, false, false))
        assertEquals(StripPriority.TOOL_PROMPT, StripPriority.resolve(false, true, false, false, false))
        assertEquals(StripPriority.STATUS_TEXT, StripPriority.resolve(false, false, false, true, false))
    }

    @Test
    fun stripPriority_should_HaveSixLevelsInUxOrder() {
        assertEquals(
            listOf("ERROR", "TOOL_PROMPT", "FIRST_TIME_HINT", "STATUS_TEXT", "VOICE_HINT", "IDLE"),
            StripPriority.entries.map { it.name },
        )
    }

    @Test
    fun backPolicy_should_FollowUxTable() {
        // resolve(modalOpen, nudgeOpen, toolArmed, unfinishedPoints); rows follow ux.md 3.5.
        assertEquals(BackAction.DismissModal, EditorBackPolicy.resolve(true, true, true, 3))
        assertEquals(BackAction.DismissModal, EditorBackPolicy.resolve(true, false, false, 0))
        assertEquals(BackAction.CancelNudge, EditorBackPolicy.resolve(false, true, true, 3))
        assertEquals(BackAction.CancelNudge, EditorBackPolicy.resolve(false, true, false, 0))
        assertEquals(BackAction.ConfirmDiscardThenDisarm, EditorBackPolicy.resolve(false, false, true, 2))
        assertEquals(BackAction.DisarmSilently, EditorBackPolicy.resolve(false, false, true, 1))
        assertEquals(BackAction.DisarmSilently, EditorBackPolicy.resolve(false, false, true, 0))
        assertEquals(BackAction.ResolveLeave, EditorBackPolicy.resolve(false, false, false, 0))
    }

    @Test
    fun backWithToolArmed_should_NeverLeaveEditorOnFirstPress() {
        for (pts in 0..4) {
            assertNotEquals(
                BackAction.ResolveLeave,
                EditorBackPolicy.resolve(modalOpen = false, nudgeOpen = false, toolArmed = true, unfinishedPoints = pts),
            )
        }
    }
}
