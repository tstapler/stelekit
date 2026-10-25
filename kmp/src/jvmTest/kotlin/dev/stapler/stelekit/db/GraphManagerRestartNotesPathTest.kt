// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every launch re-adds the active graph by its repo-root path before its database is open. That
 * re-detection must not forget the wiki subdirectory, or the app starts on the repo root (where
 * pages/ and journals/ do not exist) and the view model wipes the cache.
 */
class GraphManagerRestartNotesPathTest {
    private val root = "/graphs/uuid"
    private val notes = "$root/logseq"

    private class MapSettings : Settings {
        val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    /** A git repo at [root] whose notes live in its `logseq` subdirectory. */
    private class WikiSubdirFileSystem(private val dirs: Set<String>) : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = path in dirs
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    private fun newManager(settings: Settings) = GraphManager(
        platformSettings = settings,
        driverFactory = DriverFactory(),
        fileSystem = WikiSubdirFileSystem(setOf("$root/.git", "$notes/pages", "$notes/journals")),
        defaultBackend = GraphBackend.SQLDELIGHT,
    )

    @Test
    fun restart_should_KeepWikiSubdir_When_AddGraphRunsBeforeTheDatabaseIsOpen() = runTest {
        val dataDir = File.createTempFile("stele-restart", "").also { it.delete(); it.mkdirs() }
        val previous = System.getProperty("stelekit.devDataDir")
        System.setProperty("stelekit.devDataDir", dataDir.absolutePath)
        try {
            val settings = MapSettings()
            val first = newManager(settings)
            val id = first.addGraph(root)
            first.awaitBackgroundDetection()
            first.switchGraph(id)
            first.awaitPendingMigration()
            val config = GitConfig(graphId = id.value, repoRoot = root, wikiSubdir = "logseq", authType = GitAuthType.NONE)
            first.createGitConfigRepository()!!.saveConfig(config)
            first.updateEffectivePathFromGitConfig(id, config)
            assertEquals(notes, first.getActiveGraphInfo()?.effectiveNotesPath?.value)
            first.shutdown()

            val restarted = newManager(settings)
            assertEquals(notes, restarted.getActiveGraphInfo()?.effectiveNotesPath?.value, "persisted registry")
            @Suppress("OPT_IN_USAGE_ERROR")
            val persistedRoot = restarted.getActiveGraphInfo()!!.path
            restarted.addGraph(persistedRoot) // what InitializeGraphFromPath does on every launch
            restarted.switchGraph(id)
            restarted.awaitBackgroundDetection()

            assertEquals(notes, restarted.getActiveGraphInfo()?.effectiveNotesPath?.value, "after re-detection")
            assertEquals(notes, settings.store["lastNotesPath"], "mirrored notes path")
            restarted.shutdown()
        } finally {
            if (previous != null) System.setProperty("stelekit.devDataDir", previous) else System.clearProperty("stelekit.devDataDir")
            dataDir.deleteRecursively()
        }
    }
}
