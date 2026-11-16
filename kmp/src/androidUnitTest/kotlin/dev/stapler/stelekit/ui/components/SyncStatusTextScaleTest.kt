// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.LockVerdict
import dev.stapler.stelekit.git.PausedRetryInfo
import dev.stapler.stelekit.git.SYNC_FAILED_TEXT
import dev.stapler.stelekit.git.SYNC_PAUSED_ROW_TEXT
import dev.stapler.stelekit.git.SyncSheetState
import dev.stapler.stelekit.git.SyncStaleness
import dev.stapler.stelekit.git.UnknownReason
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.git.syncStatusSheetModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/** X-68b / UX-68: the sync row, sheet and buttons at 360 dp, 200 percent font and RTL: text wraps, nothing overflows sideways. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp")
class SyncStatusTextScaleTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val now = 10_000_000L
    private val lock = SyncState.Error(
        DomainError.GitError.StaleLockFile("p", LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)),
    )

    private fun actions() = SyncStatusSheetActions(onSyncNow = {}, onReviewFirstSync = {}, onOpenSettings = {}, onDismiss = {})

    private fun setContent(rtl: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) { MaterialTheme { content() } }
        }
    }

    private fun assertWithinWidth(text: String) {
        val bounds = composeTestRule.onNodeWithText(text).getUnclippedBoundsInRoot()
        assertTrue(bounds.left >= 0.dp && bounds.right <= 360.dp, "'$text' spans ${bounds.left}..${bounds.right} of a 360 dp screen")
    }

    private fun pausedSheet() {
        val model = syncStatusSheetModel(
            SyncStaleness(), lock, now, 15, false, pausedRetry = PausedRetryInfo(now + 30_000, 1), retryNow = {},
        )
        setContent(rtl = false) { SyncStatusSheetContent(model, actions()) }
    }

    @Test
    fun pausedSheet_should_KeepRetryNowReachableWithinTheScreenWidth_At200PercentFont() {
        pausedSheet()

        composeTestRule.onNodeWithText("Retry now").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertWithinWidth("Retry now")
    }

    @Test
    fun pausedSheet_should_WrapTheBody_AtRtl() {
        val model = syncStatusSheetModel(
            SyncStaleness(), lock, now, 15, false, pausedRetry = PausedRetryInfo(now + 30_000, 1), retryNow = {},
        )
        setContent(rtl = true) { SyncStatusSheetContent(model, actions()) }

        composeTestRule.onNodeWithText("We couldn't confirm", substring = true).assertExists()
        composeTestRule.onNodeWithText("Retry now").assertIsDisplayed()
        assertWithinWidth("Retry now")
    }

    @Test
    fun failedSheet_should_KeepSyncNowWithinTheScreenWidth_At200PercentFont() {
        val model = syncStatusSheetModel(SyncStaleness(), lock, now, 15, true, pausedRetry = null)
        setContent(rtl = false) { SyncStatusSheetContent(model, actions()) }

        composeTestRule.onNodeWithText("Sync now").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertWithinWidth("Sync now")
        assertWithinWidth("Review first sync")
    }

    @Test
    fun rows_should_WrapAndKeepTheirActionsWithinTheWidth_At200PercentFont() {
        val paused = SyncSheetState.Paused(SYNC_PAUSED_ROW_TEXT, "body", "details", canRemoveLock = false)
        setContent(rtl = false) {
            SyncStatusBadge(GitSyncStatus(lock, sheetState = paused), onSyncClick = {}, onOpenSyncStatus = {})
        }
        composeTestRule.onNodeWithText(SYNC_PAUSED_ROW_TEXT).assertHeightIsAtLeast(48.dp)
        assertWithinWidth(SYNC_PAUSED_ROW_TEXT)
    }

    @Test
    fun failedRow_should_WrapAndKeepSyncNowAndDetailsWithinTheWidth_At200PercentFont() {
        val failed = SyncSheetState.Failed(SYNC_FAILED_TEXT, canRemoveLock = false)
        setContent(rtl = true) {
            SyncStatusBadge(GitSyncStatus(lock, sheetState = failed), onSyncClick = {}, onOpenSyncStatus = {})
        }
        composeTestRule.onNodeWithText("Sync now").assertHeightIsAtLeast(48.dp)
        assertWithinWidth("Sync now")
        assertWithinWidth("Details")
    }
}
