// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stapler.stelekit.diagnostics.DirectoryScanResult
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WikiSubdirFixDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val scan = DirectoryScanResult(path = "/graph/notes", pages = true, journals = true, name = "notes")
    private val preview = DryRunPreview(hasChanges = false, estimatedSize = 42L)

    private class Calls {
        var move: DryRunPreview? = null
        var merge: DryRunPreview? = null
        var leave = 0
        var dismiss = 0
    }

    private fun show(calls: Calls, configuredPath: String = "wiki") {
        composeTestRule.setContent {
            MaterialTheme {
                WikiSubdirFixDialog(
                    onDismiss = { calls.dismiss++ },
                    onMove = { calls.move = it },
                    onMerge = { calls.merge = it },
                    onLeave = { calls.leave++ },
                    mismatchInfo = scan,
                    configuredPath = configuredPath,
                    dryRunPreview = preview,
                )
            }
        }
    }

    @Test
    fun `Move click fires onMove with the dry-run preview`() {
        val calls = Calls()
        show(calls)
        composeTestRule.onNodeWithText("Move").performClick()
        assertEquals(preview, calls.move)
        assertEquals(null, calls.merge)
        assertEquals(0, calls.leave)
    }

    @Test
    fun `Merge click fires onMerge with the dry-run preview`() {
        val calls = Calls()
        show(calls)
        composeTestRule.onNodeWithText("Merge").performClick()
        assertEquals(preview, calls.merge)
        assertEquals(null, calls.move)
    }

    @Test
    fun `Leave as-is click fires onLeave`() {
        val calls = Calls()
        show(calls)
        composeTestRule.onNodeWithText("Leave as-is").performScrollTo().performClick()
        assertEquals(1, calls.leave)
        assertEquals(null, calls.move)
        assertEquals(null, calls.merge)
    }

    @Test
    fun `Cancel click fires onDismiss`() {
        val calls = Calls()
        show(calls)
        composeTestRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, calls.dismiss)
    }

    @Test
    fun `header shows configured and found labels`() {
        show(Calls(), configuredPath = "wiki")
        composeTestRule.onNodeWithText("Configured: wiki | Found: notes").assertIsDisplayed()
    }

    @Test
    fun `blank configured path renders as Root`() {
        show(Calls(), configuredPath = "")
        composeTestRule.onNodeWithText("Configured: (Root) | Found: notes").assertIsDisplayed()
    }
}
