// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.testsupport

import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CoroutineScope

/**
 * Minimal [FileSystem] stub with safe no-op defaults — the same shape repeated, byte-for-byte,
 * across `jvmTest`/`androidUnitTest`/`businessTest` `GitSyncService`-adjacent tests before this
 * extraction. See [StubGitRepository]'s KDoc for why `commonTest` is the shared location.
 */
open class StubFileSystem(private val writeResult: Boolean = true) : FileSystem {
    override fun getDefaultGraphPath() = "/tmp"
    override fun expandTilde(path: String) = path
    override fun readFile(path: String): String? = null
    override fun writeFile(path: String, content: String): Boolean = writeResult
    override fun listFiles(path: String): List<String> = emptyList()
    override fun listDirectories(path: String): List<String> = emptyList()
    override fun fileExists(path: String) = false
    override fun directoryExists(path: String) = true
    override fun createDirectory(path: String) = true
    override fun deleteFile(path: String) = true
    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = null
    override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
    override fun stopExternalChangeDetection() {}
}
