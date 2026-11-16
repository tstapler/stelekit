package dev.stapler.stelekit.ui.annotate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EditorChromePolicyTest {
    private val bools = listOf(false, true)

    @Test
    fun stripPriority_should_PickHighestTrueLevel_ForEveryCombination() {
        var count = 0
        for (e in bools) for (t in bools) for (h in bools) for (s in bools) for (v in bools) {
            val expected = listOfNotNull(
                StripPriority.ERROR.takeIf { e }, StripPriority.TOOL_PROMPT.takeIf { t },
                StripPriority.FIRST_TIME_HINT.takeIf { h }, StripPriority.STATUS_TEXT.takeIf { s },
                StripPriority.VOICE_HINT.takeIf { v },
            ).firstOrNull() ?: StripPriority.IDLE
            assertEquals(expected, StripPriority.resolve(e, t, h, s, v), "$e $t $h $s $v")
            count++
        }
        assertEquals(32, count)
    }

    @Test
    fun stripPriority_should_HaveSixLevelsInUxOrder() {
        assertEquals(
            listOf("ERROR", "TOOL_PROMPT", "FIRST_TIME_HINT", "STATUS_TEXT", "VOICE_HINT", "IDLE"),
            StripPriority.entries.map { it.name },
        )
    }

    @Test
    fun backPolicy_should_FollowFixedOrder_ForEveryCombination() {
        var count = 0
        for (modal in bools) for (nudge in bools) for (armed in bools) for (pts in 0..4) {
            val expected = when {
                modal -> BackAction.DismissModal
                nudge -> BackAction.CancelNudge
                armed && pts >= 2 -> BackAction.ConfirmDiscardThenDisarm
                armed -> BackAction.DisarmSilently
                else -> BackAction.ResolveLeave
            }
            assertEquals(expected, EditorBackPolicy.resolve(modal, nudge, armed, pts), "$modal $nudge $armed $pts")
            count++
        }
        assertEquals(40, count)
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
