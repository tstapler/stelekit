// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.git.SYNC_FAILED_TEXT
import dev.stapler.stelekit.git.SYNC_PAUSED_ROW_TEXT
import dev.stapler.stelekit.git.SyncSheetState
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.ui.theme.StelekitTheme
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Task 1.7b: the sync row's Paused and Failed states and the warning token (UX-04, UX-69, F15). */
@RunWith(RobolectricTestRunner::class)
class SyncStatusBadgeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val lockError = SyncState.Error(DomainError.GitError.StaleLockFile("p"))

    private val paused = SyncSheetState.Paused(SYNC_PAUSED_ROW_TEXT, "body", "details", canRemoveLock = false)
    private val failed = SyncSheetState.Failed(SYNC_FAILED_TEXT, canRemoveLock = false)

    @Composable
    private fun Badge(sheet: SyncSheetState, onSync: () -> Unit = {}, onStatus: () -> Unit = {}) {
        MaterialTheme {
            SyncStatusBadge(
                status = GitSyncStatus(lockError, sheetState = sheet),
                onSyncClick = onSync,
                onOpenSyncStatus = onStatus,
            )
        }
    }

    @Test
    fun pausedRow_should_ReadCalmCopy_BeAtLeast48dpTall_AndOpenDetailsNotARetry() {
        var status = 0
        var sync = 0
        composeTestRule.setContent { Badge(paused, onSync = { sync++ }, onStatus = { status++ }) }

        composeTestRule.onNodeWithText("Sync paused - retrying automatically").assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(1, status)
        assertEquals(0, sync)
    }

    @Test
    fun failedRow_should_OfferSyncNowAndDetails_Each48dp_AndNeverTryAgain() {
        var sync = 0
        var status = 0
        composeTestRule.setContent { Badge(failed, onSync = { sync++ }, onStatus = { status++ }) }

        composeTestRule.onNodeWithText("Couldn't sync. Your notes are saved on this device.").assertExists()
        composeTestRule.onNodeWithText("Try again", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Details").assertHeightIsAtLeast(48.dp).performClick()
        composeTestRule.onNodeWithText("Sync now").assertHeightIsAtLeast(48.dp).performClick()

        assertEquals(1, sync)
        assertEquals(1, status)
    }

    @Test
    fun warningToken_should_ReachContrast4point5_InLight() = checkWarningContrast(StelekitThemeMode.LIGHT)

    @Test
    fun warningToken_should_ReachContrast4point5_InDark() = checkWarningContrast(StelekitThemeMode.DARK)

    @Test
    fun warningToken_should_ReachContrast4point5_InStone() = checkWarningContrast(StelekitThemeMode.STONE)

    private fun checkWarningContrast(mode: StelekitThemeMode) {
        var warning = Color.Unspecified
        var onWarning = Color.Unspecified
        var surface = Color.Unspecified
        var error = Color.Unspecified
        composeTestRule.setContent {
            StelekitTheme(mode) {
                warning = StelekitTheme.colors.warning
                onWarning = StelekitTheme.colors.onWarning
                surface = MaterialTheme.colorScheme.surface
                error = MaterialTheme.colorScheme.error
            }
        }
        composeTestRule.waitForIdle()
        assertTrue(contrast(warning, surface) >= 4.5, "$mode warning on surface = ${contrast(warning, surface)}")
        assertTrue(contrast(onWarning, warning) >= 4.5, "$mode onWarning on warning = ${contrast(onWarning, warning)}")
        assertNotEquals(warning, error)
    }

    private fun contrast(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }
}
