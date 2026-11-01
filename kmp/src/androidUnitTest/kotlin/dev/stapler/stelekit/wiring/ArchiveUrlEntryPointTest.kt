// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.domain.ArchiveResult
import dev.stapler.stelekit.domain.WaybackMachineService
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.components.BlockList
import dev.stapler.stelekit.ui.components.LocalArchiveUrlAction
import dev.stapler.stelekit.ui.components.rememberArchiveUrlAction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Story 7.1: right-clicking a URL in a rendered block reaches the archive service through the
 * real entry point (`BlockList` -> `BlockRenderer` -> `BlockItem` -> `BlockViewer` pointer handler),
 * with the action supplied the way `GraphContentActiveShell` supplies it (`LocalArchiveUrlAction`).
 * The Ktor request/response mapping itself is covered by `WaybackMachineServiceTest`.
 */
// NATIVE graphics: legacy mode measures every glyph as zero-width, so a click resolves to the end
// offset of the text, outside the half-open URL annotation range.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w480dp-h1600dp")
@RunWith(RobolectricTestRunner::class)
class ArchiveUrlEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val url = "https://example.com/a"
    private val archived = "https://web.archive.org/web/20260101000000/$url"
    private val blockUuid = "00000000-0000-0000-0000-000000000001"

    private fun block() = Block(
        uuid = BlockUuid(blockUuid),
        pageUuid = PageUuid("00000000-0000-0000-0000-000000000002"),
        content = url,
        position = "a0",
        createdAt = Instant.fromEpochSeconds(0),
        updatedAt = Instant.fromEpochSeconds(0),
    )

    private class FakeWayback(private val result: Either<DomainError, ArchiveResult>) : WaybackMachineService {
        val requested = mutableListOf<String>()
        override suspend fun archiveUrl(url: String): Either<DomainError, ArchiveResult> {
            requested += url
            return result
        }
    }

    private fun setContent(service: WaybackMachineService?, updates: MutableList<Pair<String, String>>) {
        composeRule.setContent {
            MaterialTheme {
                val snackbar = remember { SnackbarHostState() }
                val action = rememberArchiveUrlAction(service, snackbar) { uuid, text -> updates += uuid to text }
                CompositionLocalProvider(LocalArchiveUrlAction provides action) {
                    BlockList(
                        blocks = listOf(block()),
                        editingBlockUuid = null,
                        onStartEditing = {},
                        onStopEditing = {},
                        onContentChange = { _, _, _ -> },
                        onLinkClick = {},
                        onNewBlock = {},
                        onSplitBlock = { _, _ -> },
                    )
                    SnackbarHost(snackbar)
                }
            }
        }
    }

    @Test
    fun rightClickOnUrl_archivesAndAppendsLinkToBlock() {
        val service = FakeWayback(ArchiveResult.Success(archived).right())
        val updates = mutableListOf<Pair<String, String>>()
        setContent(service, updates)

        composeRule.onNodeWithText(url).performMouseInput { rightClick(Offset(20f, 10f)) }
        composeRule.waitForIdle()

        assertEquals(listOf(url), service.requested)
        assertEquals(listOf(blockUuid to " [archived]($archived)"), updates)
        composeRule.onNodeWithText("Archived: $archived").assertExists()
    }

    @Test
    fun archiveFailure_showsRetrySnackbarAndLeavesBlockUntouched() {
        val service = FakeWayback(DomainError.NetworkError.RequestFailed("boom").left())
        val updates = mutableListOf<Pair<String, String>>()
        setContent(service, updates)

        composeRule.onNodeWithText(url).performMouseInput { rightClick(Offset(20f, 10f)) }
        composeRule.waitForIdle()

        assertEquals(listOf(url), service.requested)
        assertEquals(emptyList(), updates)
        composeRule.onNodeWithText("Couldn't archive - Retry").assertExists()
    }

    @Test
    fun alreadyArchived_appendsTheExistingArchiveLink() {
        val service = FakeWayback(ArchiveResult.AlreadyArchived(archived).right())
        val updates = mutableListOf<Pair<String, String>>()
        setContent(service, updates)

        composeRule.onNodeWithText(url).performMouseInput { rightClick(Offset(20f, 10f)) }
        composeRule.waitForIdle()

        assertEquals(listOf(blockUuid to " [archived]($archived)"), updates)
    }

    @Test
    fun retryOnTheFailureSnackbar_archivesAgain() {
        val service = FakeWayback(DomainError.NetworkError.RequestFailed("boom").left())
        setContent(service, mutableListOf())

        composeRule.onNodeWithText(url).performMouseInput { rightClick(Offset(20f, 10f)) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Retry").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(url, url), service.requested)
    }

    @Test
    fun noService_rightClickDoesNothing() {
        val updates = mutableListOf<Pair<String, String>>()
        setContent(null, updates)

        composeRule.onNodeWithText(url).performMouseInput { rightClick() }
        composeRule.waitForIdle()

        assertEquals(emptyList(), updates)
    }
}
