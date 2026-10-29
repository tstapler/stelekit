// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.stapler.stelekit.console.ConsoleRegistry
import dev.stapler.stelekit.console.ConsoleSession
import dev.stapler.stelekit.console.DeveloperMode
import dev.stapler.stelekit.console.commands.DiagCommand
import dev.stapler.stelekit.console.commands.HelpCommand
import dev.stapler.stelekit.console.commands.LogsCommand
import dev.stapler.stelekit.console.commands.SchemaCommand
import dev.stapler.stelekit.console.commands.SqlCommand
import dev.stapler.stelekit.console.commands.TablesCommand
import dev.stapler.stelekit.diagnostics.GraphDiagnosticsCollector
import dev.stapler.stelekit.git.GitConfigRepository

/**
 * Composition root for the developer console: builds the [DeveloperMode] flag, registers the
 * built-in verbs and owns the [ConsoleSession] (closed when `GraphContent` leaves composition).
 * `GraphContent` is recreated on a graph switch, so scrollback does not survive one.
 *
 * Not wired here: exact-secret redaction (`ConsoleSession.exactSecrets`), because `CredentialStore`
 * cannot enumerate keys; output is still redacted by pattern.
 */
@Composable
internal fun rememberGraphContentConsoleSetup(
    deps: GraphContentDeps,
    gitConfigRepository: GitConfigRepository?,
): GraphContentConsoleStack {
    val developerMode = remember(deps.platformSettings) { DeveloperMode(deps.platformSettings) }
    val session = remember(developerMode, deps.repos, deps.graphManager, gitConfigRepository) {
        val collector = GraphDiagnosticsCollector(
            graphManager = deps.graphManager,
            fileSystem = deps.fileSystem,
            repos = deps.repos,
            settings = deps.platformSettings,
            gitConfigRepository = gitConfigRepository,
            gitRepository = deps.platformIntegrations.gitRepository,
        )
        val sqlConsole = { deps.repos.sqlConsole }
        ConsoleSession(
            developerMode = developerMode,
            registry = ConsoleRegistry(
                listOf(
                    HelpCommand(), DiagCommand(collector::collect), LogsCommand(),
                    SqlCommand(sqlConsole = sqlConsole), TablesCommand(sqlConsole), SchemaCommand(sqlConsole),
                ),
            ),
        )
    }
    DisposableEffect(session) { onDispose { session.close() } }
    return remember(developerMode, session) { GraphContentConsoleStack(developerMode, session) }
}
