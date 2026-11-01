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
import dev.stapler.stelekit.console.ConsoleBuildFlags
import dev.stapler.stelekit.console.ConsoleWriteLog
import dev.stapler.stelekit.console.DbOnlyRowsGuard
import dev.stapler.stelekit.console.JournalDbOnlyRowsSource
import dev.stapler.stelekit.console.commands.BackupsCommand
import dev.stapler.stelekit.console.commands.GraphRestoreCommand
import dev.stapler.stelekit.console.commands.RestoreRunner
import dev.stapler.stelekit.console.commands.RestoreToolkit
import dev.stapler.stelekit.db.DbRestore
import dev.stapler.stelekit.db.GraphManagerRestoreHost
import dev.stapler.stelekit.db.ReadHandleRestoreInspector
import dev.stapler.stelekit.db.RestoreCatalog
import dev.stapler.stelekit.db.platformBackupFiles
import dev.stapler.stelekit.console.commands.SqlCommand
import dev.stapler.stelekit.console.commands.SqlWriteCommand
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
        val writeLog = ConsoleWriteLog()
        val restoreToolkit = { restoreToolkitFor(deps) }
        val restoreRunner = RestoreRunner(restoreToolkit, writeLog, writesEnabled = { ConsoleBuildFlags.WRITES_ENABLED })
        ConsoleSession(
            developerMode = developerMode,
            registry = ConsoleRegistry(
                listOf(
                    HelpCommand(), DiagCommand(writeLog, collector::collect), LogsCommand(),
                    SqlCommand(sqlConsole = sqlConsole), TablesCommand(sqlConsole), SchemaCommand(sqlConsole),
                    SqlWriteCommand(writer = { deps.repos.sqlWriter }, backup = { deps.repos.consoleBackup }, writeLog = writeLog),
                    GraphRestoreCommand(restoreRunner), BackupsCommand(restoreToolkit, restoreRunner),
                ),
            ),
        )
    }
    DisposableEffect(session) { onDispose { session.close() } }
    return remember(developerMode, session) { GraphContentConsoleStack(developerMode, session) }
}

/** The restore tools for the open graph's database, or null where there is no database file or no active graph. */
private fun restoreToolkitFor(deps: GraphContentDeps): RestoreToolkit? {
    val backup = deps.repos.consoleBackup ?: return null
    val files = platformBackupFiles() ?: return null
    val graphId = deps.graphManager.getActiveGraphId() ?: return null
    val inspector = ReadHandleRestoreInspector()
    val dbDirectory = backup.dbPath.substringBeforeLast('/', "")
    return RestoreToolkit(
        dbPath = backup.dbPath,
        backupsDirectory = backup.directory,
        catalog = RestoreCatalog(backup.dbPath, files, backup.directory),
        inspector = inspector,
        restore = DbRestore(backup.dbPath, files, inspector, GraphManagerRestoreHost(deps.graphManager, graphId), backup.directory),
        guard = DbOnlyRowsGuard(
            source = JournalDbOnlyRowsSource(backup.dbPath) { deps.fileSystem.fileExists(it) },
            files = files,
            recoveryDirectory = "$dbDirectory/console-recovery",
        ),
    )
}
