// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens.git

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.MergeSide
import dev.stapler.stelekit.git.model.ConflictFile
import dev.stapler.stelekit.git.model.ConflictHunk
import dev.stapler.stelekit.git.model.HunkResolution
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The block-picking merge UI: pick per block, see the editable result, resolve with exact content. */
@RunWith(RobolectricTestRunner::class)
class ConflictResolutionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val conflict = ConflictFile(
        filePath = "/wiki/journals/2026_10_10.md",
        wikiRelativePath = "journals/2026_10_10.md",
        hunks = listOf(ConflictHunk(id = "h1", localLines = listOf("- mine"), remoteLines = listOf("- theirs"))),
    )

    private var resolvedHunks: Map<String, List<ConflictHunk>>? = null
    private var resolveResult: Either<DomainError.GitError, Unit> = Unit.right()

    private fun show() {
        composeTestRule.setContent {
            MaterialTheme {
                ConflictResolutionScreen(
                    conflicts = listOf(conflict),
                    onResolve = { _, hunks ->
                        resolvedHunks = hunks
                        resolveResult
                    },
                    onDismiss = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Resolve line by line").performClick()
    }

    @Test
    fun `finish is disabled until a section is resolved`() {
        show()
        composeTestRule.onNodeWithText("Finish Merge").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Keep both").performClick()
        composeTestRule.onNodeWithText("Finish Merge").assertIsEnabled()
    }

    @Test
    fun `keep both resolves with both sides in order and ticks every block`() {
        show()
        composeTestRule.onNodeWithText("Keep both").performClick()
        composeTestRule.onNodeWithTag("keep-Yours-0").performScrollTo().assertIsOn()
        composeTestRule.onNodeWithTag("keep-Theirs-0").performScrollTo().assertIsOn()
        composeTestRule.onNodeWithText("- mine\n- theirs").assertIsEnabled()
        composeTestRule.onNodeWithText("Finish Merge").performClick()
        composeTestRule.waitForIdle()

        val hunk = assertNotNull(resolvedHunks)[conflict.filePath]!!.single()
        assertEquals(HunkResolution.Manual, hunk.resolution)
        assertEquals("- mine\n- theirs", hunk.manualContent)
    }

    @Test
    fun `unticking a block removes it from the result`() {
        show()
        composeTestRule.onNodeWithText("Keep both").performClick()
        composeTestRule.onNodeWithTag("keep-Yours-0").assertIsOn().performScrollTo().performClick()
        composeTestRule.onNodeWithTag("keep-Yours-0").assertIsOff()
        composeTestRule.onNodeWithText("Finish Merge").performClick()
        composeTestRule.waitForIdle()

        assertEquals("- theirs", assertNotNull(resolvedHunks)[conflict.filePath]!!.single().manualContent)
    }

    @Test
    fun `keep theirs resolves to the remote side`() {
        show()
        composeTestRule.onNodeWithText("Keep theirs").performClick()
        composeTestRule.onNodeWithText("Finish Merge").performClick()
        composeTestRule.waitForIdle()

        assertEquals(HunkResolution.AcceptRemote, assertNotNull(resolvedHunks)[conflict.filePath]!!.single().resolution)
    }

    @Test
    fun `reopening a section restores the ticks that produced its result`() {
        show()
        composeTestRule.onNodeWithText("Keep both").performClick()
        composeTestRule.onNodeWithText("Use one version").performClick()
        composeTestRule.onNodeWithText("Resolve line by line").performClick()

        composeTestRule.onNodeWithTag("keep-Yours-0").performScrollTo().assertIsOn().assertIsEnabled()
        composeTestRule.onNodeWithTag("keep-Theirs-0").performScrollTo().assertIsOn()
    }

    @Test
    fun `hand-edited result is never overwritten by a toggle until reset`() {
        show()
        composeTestRule.onNodeWithText("Keep both").performClick()
        composeTestRule.onNodeWithText("Result (editable)").performTextReplacement("my own words")

        composeTestRule.onNodeWithTag("keep-Yours-0").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Keep mine").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Reset to picks").performScrollTo().assertIsEnabled()
        composeTestRule.onNodeWithText("Finish Merge").performClick()
        composeTestRule.waitForIdle()
        assertEquals("my own words", assertNotNull(resolvedHunks)[conflict.filePath]!!.single().manualContent)
    }

    @Test
    fun `reset to picks replaces hand edits with the ticked blocks and re-enables toggles`() {
        show()
        composeTestRule.onNodeWithText("Keep both").performClick()
        composeTestRule.onNodeWithText("Result (editable)").performTextReplacement("my own words")
        composeTestRule.onNodeWithText("Reset to picks").performScrollTo().performClick()

        composeTestRule.onNodeWithText("- mine\n- theirs").assertIsEnabled()
        composeTestRule.onNodeWithTag("keep-Yours-0").performScrollTo().assertIsEnabled().assertIsOn()
    }

    @Test
    fun `a failed resolve surfaces the error and keeps the screen open`() {
        resolveResult = DomainError.GitError.CommitFailed("boom").left()
        var dismissed = false
        composeTestRule.setContent {
            MaterialTheme {
                ConflictResolutionScreen(
                    conflicts = listOf(conflict),
                    onResolve = { _, _ -> resolveResult },
                    onDismiss = { dismissed = true },
                )
            }
        }
        composeTestRule.onNodeWithText("Finish Merge").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Resolution failed: boom", substring = true).assertIsEnabled()
        assertEquals(false, dismissed)
    }
}
