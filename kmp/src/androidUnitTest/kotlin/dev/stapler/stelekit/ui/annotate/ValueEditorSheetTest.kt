// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** X-61 / UX-61: the Undo snackbar never times out under touch exploration or switch access. */
@RunWith(RobolectricTestRunner::class)
class ValueEditorSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private fun showAndRead(active: Boolean): Pair<SnackbarDuration?, Boolean?> {
        val host = SnackbarHostState()
        rule.setContent {
            SnackbarHost(host)
            LaunchedEffect(Unit) { host.showUndo("Scale updated. 3 measurements changed.", active) }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Scale updated. 3 measurements changed.").assertExists()
        rule.onNodeWithText("Undo").assertExists()
        val visuals = host.currentSnackbarData?.visuals
        return visuals?.duration to visuals?.withDismissAction
    }

    @Test
    fun undoSnackbar_should_BeIndefiniteUnderTouchExplorationAndLongOtherwise() {
        assertEquals(SnackbarDuration.Indefinite, ValueEditorRules.undoDuration(true))
        assertEquals(SnackbarDuration.Long, ValueEditorRules.undoDuration(false))
    }

    @Test
    fun undoSnackbar_should_BeIndefiniteWithDismissWhenAssistiveServiceActive() {
        assertEquals(SnackbarDuration.Indefinite to true, showAndRead(active = true))
    }
}
