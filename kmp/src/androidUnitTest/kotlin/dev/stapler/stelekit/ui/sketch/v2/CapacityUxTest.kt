// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 2.2.4, Task 2.2.4a/b (UX-73): the hard-cap message and its three actions, the constraint-cap and low-memory toasts.
 * Robolectric; Gradle-only like the other Compose host tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-mdpi")
class CapacityUxTest {
    @get:Rule
    val rule = createComposeRule()

    private val chosen = mutableListOf<CapacityAction>()

    @Test
    fun hardCap_should_SayTheCapAndOfferThreeActions() {
        val message = CapacityMessages.hardCap(selectionCount = 40)
        assertEquals("This sketch has 5,000 elements, the maximum.", message.text)
        assertEquals(listOf(CapacityAction.DeleteSome, CapacityAction.SplitIntoNewSketch, CapacityAction.Ok), message.actions)
        assertTrue(message.splitEnabled)
    }

    @Test
    fun hardCap_should_Run_EachAction_And_DisableSplitWithAReason_When_NothingIsSelected() {
        val none = CapacityMessages.hardCap(selectionCount = 0)
        rule.setContent { HardCapNotice(none, { chosen += it }) }
        rule.onNodeWithTag(CapacityTags.SPLIT).assertIsNotEnabled()
        assertTrue(none.splitDisabledReason != null)
        rule.onNodeWithTag(CapacityTags.DELETE_SOME).performClick()
        rule.onNodeWithTag(CapacityTags.OK).performClick()
        assertEquals(listOf(CapacityAction.DeleteSome, CapacityAction.Ok), chosen)
    }

    @Test
    fun split_should_BeEnabled_And_Run_When_ElementsAreSelected() {
        rule.setContent { HardCapNotice(CapacityMessages.hardCap(40), { chosen += it }) }
        rule.onNodeWithTag(CapacityTags.SPLIT).assertIsEnabled().performClick()
        assertEquals(listOf(CapacityAction.SplitIntoNewSketch), chosen)
    }

    @Test
    fun onlyACapacityError_should_ProduceTheHardCapMessage() {
        assertNull(CapacityMessages.hardCapFor(CommandError.UnknownElement("x"), 3))
    }

    @Test
    fun constraintLimitAndLowMemory_should_BeToastsWithDetailsAndTheRightActions() {
        val limit = CapacityMessages.constraintLimit()
        assertEquals("Constraint limit reached.", limit.text)
        assertEquals("Review", limit.action)
        assertEquals("200 constraints is the limit for one sketch.", limit.details)
        assertEquals("Dismiss", ToastMenu.items(limit).last())
        val memory = CapacityMessages.lowMemory()
        assertEquals("Low memory.", memory.text)
        assertEquals("Save now", memory.action)
        assertEquals("Save and reopen the sketch.", memory.details)
        assertEquals(listOf("Details", "Dismiss"), ToastMenu.items(memory))
    }
}
