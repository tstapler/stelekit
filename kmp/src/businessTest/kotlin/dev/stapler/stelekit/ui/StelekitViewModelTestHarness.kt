// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.repository.JournalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Minimal in-memory [StelekitViewModel] for state-machine tests that never touch disk. */
internal class StelekitViewModelTestHarness {

    private class NoopSettings : Settings {
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun putBoolean(key: String, value: Boolean) {}
        override fun getString(key: String, defaultValue: String) = defaultValue
        override fun putString(key: String, value: String) {}
        override fun containsKey(key: String) = false
    }

    private class NoopFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/fake/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    val viewModel: StelekitViewModel = run {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val fs = NoopFileSystem()
        StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fs,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(pageRepo, blockRepo),
                graphLoader = GraphLoader(fs, pageRepo, blockRepo),
                graphWriter = GraphWriter(fs),
                platformSettings = NoopSettings(),
                scope = scope,
                journalService = JournalService(pageRepo, blockRepo),
            )
        )
    }
}
