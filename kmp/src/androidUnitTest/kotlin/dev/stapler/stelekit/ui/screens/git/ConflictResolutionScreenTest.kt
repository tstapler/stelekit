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
import arrow.core.Either
import arrow.core.right
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

    private fun show() {
        composeTestRule.setContent {
            MaterialTheme {
                ConflictResolutionScreen(
                    conflicts = listOf(conflict),
                    onResolve = { _, hunks ->
                        resolvedHunks = hunks
                        Unit.right() as Either<dev.stapler.stelekit.error.DomainError.GitError, Unit>
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
}
