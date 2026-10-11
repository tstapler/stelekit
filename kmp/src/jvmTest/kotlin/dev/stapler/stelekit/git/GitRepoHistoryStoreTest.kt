// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.git

import dev.stapler.stelekit.git.model.GitRepoHistoryKind
import dev.stapler.stelekit.platform.PlatformSettings
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A remembered repository location must survive independently of any single graph — the whole
 * point is letting the Git Sync wizard offer a second graph's setup a pick instead of retyping a
 * path or re-copying a clone URL (see the UX audit this closes, PR #351 follow-up).
 *
 * Redirects `user.home` to an isolated temp directory, following
 * `GitCredentialConnectionStoreTest`'s pattern, since `PlatformSettings` (JVM actual) is
 * file-backed under it.
 */
class GitRepoHistoryStoreTest {

    private lateinit var originalUserHome: String
    private lateinit var tempHome: java.io.File

    @BeforeTest
    fun setUp() {
        originalUserHome = System.getProperty("user.home")
        tempHome = createTempDirectory("stelekit_git_repo_history_test_").toFile()
        System.setProperty("user.home", tempHome.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalUserHome)
        tempHome.deleteRecursively()
    }

    private fun freshStore() = GitRepoHistoryStore(PlatformSettings())

    @Test
    fun `record persists an entry that a fresh store instance sees`() {
        val store = freshStore()

        store.record("/home/tyler/notes", GitRepoHistoryKind.LOCAL_PATH, wikiSubdir = "pages", now = 1L)

        val reloaded = freshStore().recentEntries(GitRepoHistoryKind.LOCAL_PATH)
        assertEquals(1, reloaded.size)
        assertEquals("/home/tyler/notes", reloaded.single().value)
        assertEquals("pages", reloaded.single().wikiSubdir)
    }

    @Test
    fun `recentEntries filters by kind`() {
        val store = freshStore()
        store.record("/home/tyler/notes", GitRepoHistoryKind.LOCAL_PATH, wikiSubdir = "", now = 1L)
        store.record("https://github.com/tstapler/notes.git", GitRepoHistoryKind.CLONE_URL, wikiSubdir = "", now = 2L)

        assertEquals(1, store.recentEntries(GitRepoHistoryKind.LOCAL_PATH).size)
        assertEquals(1, store.recentEntries(GitRepoHistoryKind.CLONE_URL).size)
    }

    @Test
    fun `recentEntries returns most recently used first`() {
        val store = freshStore()
        store.record("/repo-old", GitRepoHistoryKind.LOCAL_PATH, wikiSubdir = "", now = 1L)
        store.record("/repo-new", GitRepoHistoryKind.LOCAL_PATH, wikiSubdir = "", now = 2L)

        val entries = store.recentEntries(GitRepoHistoryKind.LOCAL_PATH)
        assertEquals(listOf("/repo-new", "/repo-old"), entries.map { it.value })
    }

    @Test
    fun `record on an already-known value updates it in place rather than duplicating`() {
        val store = freshStore()
        store.record("/home/tyler/notes", GitRepoHistoryKind.LOCAL_PATH, wikiSubdir = "", now = 1L)

        store.record("/home/tyler/notes", GitRepoHistoryKind.LOCAL_PATH, wikiSubdir = "pages/logseq", now = 2L)

        val entries = store.recentEntries(GitRepoHistoryKind.LOCAL_PATH)
        assertEquals(1, entries.size, "reusing the same repo must not duplicate the entry")
        assertEquals("pages/logseq", entries.single().wikiSubdir, "wikiSubdir must update to the latest use")
        assertEquals(2L, entries.single().lastUsedAt)
    }

    @Test
    fun `record no-ops on a blank value`() {
        val store = freshStore()

        store.record("", GitRepoHistoryKind.LOCAL_PATH, wikiSubdir = "", now = 1L)

        assertTrue(store.recentEntries(GitRepoHistoryKind.LOCAL_PATH).isEmpty())
    }

    @Test
    fun `recentEntries caps at 5, dropping the oldest`() {
        val store = freshStore()
        repeat(7) { i -> store.record("/repo-$i", GitRepoHistoryKind.LOCAL_PATH, wikiSubdir = "", now = i.toLong()) }

        val entries = store.recentEntries(GitRepoHistoryKind.LOCAL_PATH)
        assertEquals(5, entries.size)
        assertEquals(listOf("/repo-6", "/repo-5", "/repo-4", "/repo-3", "/repo-2"), entries.map { it.value })
    }
}
