// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tasks 6.2.6b and 6.2.6c (UX-78): the restore and delete-backups confirmations. Cancel is focused first, the loss is
 * stated before anything runs, and only the confirm button acts. Robolectric, so no display is needed.
 * Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class RestoreBackupDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    @Test
    fun restoreDialog_should_StateTheLossFirst_AndFocusCancel() {
        rule.setContent { RestoreBackupDialog(onRestore = { calls += "restore" }, onCancel = { calls += "cancel" }) }
        rule.waitForIdle()

        rule.onNodeWithText(RestoreCopy.TITLE).assertIsDisplayed()
        rule.onNodeWithText(RestoreCopy.BODY).assertIsDisplayed()
        rule.onNodeWithTag(RESTORE_CANCEL_TAG).assertIsFocused()
        assertTrue(calls.isEmpty(), "showing the dialog must not act")
    }

    @Test
    fun restoreDialog_should_CallOnlyTheChosenCallback() {
        rule.setContent { RestoreBackupDialog(onRestore = { calls += "restore" }, onCancel = { calls += "cancel" }) }

        rule.onNodeWithTag(RESTORE_CANCEL_TAG).performClick()
        rule.onNodeWithTag(RESTORE_CONFIRM_TAG).performClick()

        assertEquals(listOf("cancel", "restore"), calls)
    }

    @Test
    fun host_should_RestoreOnlyAfterTheConfirmButton() {
        val action = RestoreBackupAction { slug, sheet -> calls += "restored $slug ${sheet.value}"; RestoreOutcome.Restored(sheet, "backup/s1.v2-before-restore.json") }
        action.request("kitchen", SheetUuid("s1"))
        rule.setContent {
            val state by action.state.collectAsState()
            RestoreBackupHost(action, state)
        }
        rule.waitForIdle()
        assertTrue(calls.isEmpty())

        rule.onNodeWithTag(RESTORE_CONFIRM_TAG).performClick()
        rule.waitForIdle()

        assertEquals(listOf("restored kitchen s1"), calls)
        assertTrue(action.state.value is RestoreBackupState.Done)
    }

    @Test
    fun deleteBackupsDialog_should_FocusCancel_AndCallOnlyTheChosenCallback() {
        rule.setContent { DeleteBackupsDialog(onDelete = { calls += "delete" }, onCancel = { calls += "cancel" }) }
        rule.waitForIdle()

        rule.onNodeWithText(RestoreCopy.DELETE_TITLE).assertIsDisplayed()
        rule.onNodeWithTag(DELETE_BACKUPS_CANCEL_TAG).assertIsFocused()
        rule.onNodeWithTag(DELETE_BACKUPS_CONFIRM_TAG).performClick()

        assertEquals(listOf("delete"), calls)
    }
}
