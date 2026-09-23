// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git.testsupport

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.security.CredentialAccess

/**
 * In-memory, flat (no subdirectories) [FileSystem] fake standing in for a SAF-backed folder,
 * for Epic 8.2's `AndroidGitRepository` regression suite (plan.md
 * `project_plans/android-git-saf-shadow-worktree/implementation/plan.md`). Modeled on
 * [dev.stapler.stelekit.platform.ShadowFlushActorTest]'s `FakeWriteFileSystem` — a plain
 * `Map`-backed double is this codebase's established pattern for `FileSystem` fakes, not a
 * `ContentProvider`-level fake like `FakeExternalStorageProvider` (that one exists to model SAF
 * indexing lag for `PlatformFileSystem`-level tests, which is out of scope here).
 *
 * Every path is tracked with a monotonically increasing "mtime" (a plain counter, not wall-clock
 * time) so [GitShadowWorktree.isFresh]'s dual mtime+size staleness check behaves deterministically
 * under fast test execution.
 */
class FakeSafFileSystem : FileSystem {
    private data class Entry(val content: String, val mtime: Long)

    private val files = LinkedHashMap<String, Entry>()
    private var clock = 1_000L

    /** Seeds (or overwrites) [path] with [content], bumping its tracked mtime. Same effect as [writeFile]. */
    fun seed(path: String, content: String) {
        writeFile(path, content)
    }

    override fun getDefaultGraphPath(): String = "saf://root"
    override fun expandTilde(path: String): String = path

    override fun readFile(path: String): String? = files[path]?.content

    override fun writeFile(path: String, content: String): Boolean {
        clock += 1
        files[path] = Entry(content, clock)
        return true
    }

    override fun listFiles(path: String): List<String> {
        val prefix = "$path/"
        return files.keys
            .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains("/") }
            .map { it.removePrefix(prefix) }
    }

    // Flat model only — no test in this suite needs nested SAF directories.
    override fun listDirectories(path: String): List<String> = emptyList()

    override fun fileExists(path: String): Boolean = files.containsKey(path)
    override fun directoryExists(path: String): Boolean = true
    override fun createDirectory(path: String): Boolean = true

    override fun deleteFile(path: String): Boolean {
        clock += 1
        return files.remove(path) != null
    }

    override fun pickDirectory(): String? = null
    override fun getLastModifiedTime(path: String): Long? = files[path]?.mtime
}

/** No-op [CredentialAccess] fake — none of Epic 8.2's tests exercise authenticated transport. */
class FakeCredentialAccess : CredentialAccess {
    private val store = mutableMapOf<String, String>()
    override fun retrieve(key: String): String? = store[key]
    override fun store(key: String, value: String) {
        store[key] = value
    }
    override fun delete(key: String) {
        store.remove(key)
    }
}
