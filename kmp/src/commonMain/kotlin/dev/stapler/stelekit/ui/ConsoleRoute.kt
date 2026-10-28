// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.stapler.stelekit.ui.screens.ConsoleScreen

/**
 * `ScreenRouter`'s entry for `Screen.Console`. Renders nothing and leaves via [onClose] when there is
 * no console or developer mode is off, which covers a restored route and switching the flag off
 * while the screen is open (the session clears itself and cancels its command on the same signal).
 */
@Composable
internal fun ConsoleRoute(onClose: () -> Unit) {
    val console = LocalConsole.current
    val enabled = rememberDeveloperModeEnabled()
    val close by rememberUpdatedState(onClose)
    LaunchedEffect(console, enabled) {
        if (console == null || !enabled) close()
    }
    if (console != null && enabled) ConsoleScreen(console.session)
}
