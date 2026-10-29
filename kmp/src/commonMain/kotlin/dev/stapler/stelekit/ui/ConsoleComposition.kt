// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import dev.stapler.stelekit.console.ConsoleSession
import dev.stapler.stelekit.console.DeveloperMode

/** The developer-mode flag and the console session built for the active graph (see `rememberGraphContentConsoleSetup`). */
internal class GraphContentConsoleStack(val developerMode: DeveloperMode, val session: ConsoleSession)

/**
 * Provided by `GraphContent`. Null (the default) means no console exists in this composition, so
 * every entry point stays hidden.
 */
internal val LocalConsole: ProvidableCompositionLocal<GraphContentConsoleStack?> = compositionLocalOf { null }

/** True only when a console exists and developer mode is on; entry points use this to show or hide themselves. */
@Composable
internal fun rememberDeveloperModeEnabled(): Boolean {
    val console = LocalConsole.current ?: return false
    val enabled by console.developerMode.enabled.collectAsState()
    return enabled
}
