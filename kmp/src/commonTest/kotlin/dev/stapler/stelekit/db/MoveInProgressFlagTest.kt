package dev.stapler.stelekit.db

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoveInProgressFlagTest {

    // MoveInProgressFlag is a process-wide singleton — clear any graph ids a previous test
    // left set so tests don't leak state into each other.
    @AfterTest
    fun clearAllFlags() {
        MoveInProgressFlag.graphIdsWithMoveInProgress.value.forEach {
            MoveInProgressFlag.setMoveInProgress(it, false)
        }
    }

    @Test
    fun isMoveInProgress_should_ReturnFalse_When_NeverSet() {
        assertFalse(MoveInProgressFlag.isMoveInProgress("never-set-graph"))
    }

    @Test
    fun isMoveInProgress_should_ReturnTrue_When_SetMoveInProgressCalledWithTrue() {
        MoveInProgressFlag.setMoveInProgress("g1", true)
        assertTrue(MoveInProgressFlag.isMoveInProgress("g1"))
    }

    @Test
    fun isMoveInProgress_should_ReturnFalse_When_SetMoveInProgressCalledWithFalseAfterTrue() {
        MoveInProgressFlag.setMoveInProgress("g1", true)
        MoveInProgressFlag.setMoveInProgress("g1", false)
        assertFalse(MoveInProgressFlag.isMoveInProgress("g1"))
    }

    @Test
    fun isMoveInProgress_should_BeIndependentPerGraphId_When_OnlyOneGraphFlagged() {
        MoveInProgressFlag.setMoveInProgress("g1", true)
        assertTrue(MoveInProgressFlag.isMoveInProgress("g1"))
        assertFalse(MoveInProgressFlag.isMoveInProgress("g2"))
    }
}
