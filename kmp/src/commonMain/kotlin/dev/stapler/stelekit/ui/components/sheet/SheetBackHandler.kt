// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components.sheet

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Feeds the Back and Esc ladder what only the composition knows: whether the soft keyboard is up (rung 3) and how
 * to hide it. The ladder itself is `TableBackLadder` run by [SheetState.onBack] under the session's banner rung;
 * Android Back reaches it through the host `Dialog`'s dismiss request and Esc through the host's bubbling key
 * handler, so there is exactly one path to it.
 */
@Composable
internal fun SheetBackHandler(state: SheetState) {
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val keyboard = LocalSoftwareKeyboardController.current
    SideEffect {
        state.imeVisible = imeBottom > 0
        state.hideIme = { keyboard?.hide() }
    }
}
