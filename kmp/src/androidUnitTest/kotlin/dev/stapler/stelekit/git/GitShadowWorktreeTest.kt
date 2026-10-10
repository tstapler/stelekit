// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GitShadowWorktree]: SAF->shadow sync ([GitShadowWorktree.syncFromSafRoot]),
 * staleness detection ([GitShadowWorktree.isFresh]/[GitShadowWorktree.ensureFresh]), and the
 * [GitWorktreePathMapper] round trip. Mirrors the fake-SAF-provider conventions established by
 * `ShadowFileCacheTest` (same `@RunWith(RobolectricTestRunner::class) @Config(sdk = [29])` setup).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitShadowWorktreeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun newWorktree(safRoot: String = "saf://root"): GitShadowWorktree =
        GitShadowWorktree(context, "test-key-${System.nanoTime()}", safRoot)

    // ── Task 8.1.1a: sync copy/skip-fresh/delete/exclude-.git coverage ─────────────────────────

    @Test
    fun `syncFromSafRoot copies missing files skips fresh files and excludes git from the walk`() = runBlocking {
        val worktree = newWorktree()

        val content = mutableMapOf(
            ".gitignore" to "*.tmp",
            "pages/Foo.md" to "foo content",
            "journals/wikiSubdir/Deep/Nested.md" to "nested content",
            ".git/config" to "must never be copied",
            ".git/objects/ab/cd1234" to "must never be copied either",
        )
        val mtimes = mutableMapOf(
            ".gitignore" to 1000L,
            "pages/Foo.md" to 1000L,
            "journals/wikiSubdir/Deep/Nested.md" to 1000L,
            ".git/config" to 1000L,
            ".git/objects/ab/cd1234" to 1000L,
        )
        val readCounts = mutableMapOf<String, Int>()
        val listRecursive: suspend (String) -> List<Pair<String, Long>> = { mtimes.entries.map { it.key to it.value } }
        val readSafFile: suspend (String) -> String? = { path ->
            readCounts[path] = (readCounts[path] ?: 0) + 1
            content[path]
        }

        worktree.syncFromSafRoot(listRecursive, readSafFile)

        // Copies a top-level dotfile and a file nested more than one level deep under a
        // wikiSubdir — the exact cases Blocker 1 found missing from an earlier hardcoded-subdir draft.
        assertEquals("*.tmp", worktree.readShadowFile(".gitignore"))
        assertEquals("foo content", worktree.readShadowFile("pages/Foo.md"))
        assertEquals("nested content", worktree.readShadowFile("journals/wikiSubdir/Deep/Nested.md"))

        // .git/ is excluded from the walk entirely, even though it was present in the listing.
        assertNull(worktree.readShadowFile(".git/config"))
        assertNull(worktree.readShadowFile(".git/objects/ab/cd1234"))
        assertFalse(File(worktree.worktreeRootPath, ".git").exists(), "no .git dir should ever be materialized in the shadow tree by sync")

        // A second sync with identical mtimes must skip re-reading fresh files.
        readCounts.clear()
        worktree.syncFromSafRoot(listRecursive, readSafFile)
        assertEquals(0, readCounts[".gitignore"] ?: 0, "fresh top-level file should not be re-read")
        assertEquals(0, readCounts["pages/Foo.md"] ?: 0, "fresh file should not be re-read")
        assertEquals(0, readCounts["journals/wikiSubdir/Deep/Nested.md"] ?: 0, "fresh nested file should not be re-read")

        // Bumping one entry's mtime must cause exactly that entry to be re-read and re-written.
        mtimes["pages/Foo.md"] = 2000L
        content["pages/Foo.md"] = "updated foo content"
        readCounts.clear()
        worktree.syncFromSafRoot(listRecursive, readSafFile)
        assertEquals(1, readCounts["pages/Foo.md"], "stale file must be re-read exactly once")
        assertEquals("updated foo content", worktree.readShadowFile("pages/Foo.md"))
    }

    @Test
    fun `syncFromSafRoot deletes shadow-tracked files no longer present in the recursive SAF listing`() = runBlocking {
        val worktree = newWorktree()

        val content = mutableMapOf("pages/Foo.md" to "foo", "pages/Bar.md" to "bar")
        val mtimes = mutableMapOf("pages/Foo.md" to 1000L, "pages/Bar.md" to 1000L)
        val listRecursive: suspend (String) -> List<Pair<String, Long>> = { mtimes.entries.map { it.key to it.value } }
        val readSafFile: suspend (String) -> String? = { path -> content[path] }

        worktree.syncFromSafRoot(listRecursive, readSafFile)
        assertNotNull(worktree.readShadowFile("pages/Foo.md"))
        assertNotNull(worktree.readShadowFile("pages/Bar.md"))

        // SAF-side deletion: Bar.md disappears from the recursive listing entirely.
        mtimes.remove("pages/Bar.md")
        content.remove("pages/Bar.md")
        worktree.syncFromSafRoot(listRecursive, readSafFile)

        assertNotNull(worktree.readShadowFile("pages/Foo.md"), "surviving file must remain")
        assertNull(worktree.readShadowFile("pages/Bar.md"), "file deleted from SAF must be removed from the shadow tree")
    }

    // ── Task 8.1.1b: isFresh/ensureFresh staleness detection ───────────────────────────────────

    @Test
    fun `isFresh returns true and skips resync when manifest matches recursive listing`() = runBlocking {
        val worktree = newWorktree()
        val content = mutableMapOf("pages/Foo.md" to "foo")
        val mtimes = mutableMapOf("pages/Foo.md" to 1000L)
        val listRecursive: suspend (String) -> List<Pair<String, Long>> = { mtimes.entries.map { it.key to it.value } }
        var readCount = 0
        val readSafFile: suspend (String) -> String? = { path -> readCount++; content[path] }

        worktree.syncFromSafRoot(listRecursive, readSafFile)
        assertTrue(worktree.isFresh(listRecursive))

        readCount = 0
        worktree.ensureFresh(listRecursive, readSafFile)
        assertEquals(0, readCount, "ensureFresh must not trigger a resync when the manifest already matches")
    }

    @Test
    fun `isFresh returns false and ensureFresh resyncs when manifest mtime is stale`() = runBlocking {
        val worktree = newWorktree()
        val content = mutableMapOf("pages/Foo.md" to "foo")
        val mtimes = mutableMapOf("pages/Foo.md" to 1000L)
        val listRecursive: suspend (String) -> List<Pair<String, Long>> = { mtimes.entries.map { it.key to it.value } }
        val readSafFile: suspend (String) -> String? = { path -> content[path] }

        worktree.syncFromSafRoot(listRecursive, readSafFile)

        mtimes["pages/Foo.md"] = 2000L
        content["pages/Foo.md"] = "updated foo"
        assertFalse(worktree.isFresh(listRecursive), "a newer SAF mtime than the manifest must be detected as stale")

        worktree.ensureFresh(listRecursive, readSafFile)
        assertEquals("updated foo", worktree.readShadowFile("pages/Foo.md"), "ensureFresh must resync stale content")
        assertTrue(worktree.isFresh(listRecursive), "must be fresh again immediately after ensureFresh resyncs")
    }

    @Test
    fun `isFresh returns false when entry count differs even if present entries mtimes match`() = runBlocking {
        val worktree = newWorktree()
        val content = mutableMapOf("pages/Foo.md" to "foo", "pages/Bar.md" to "bar")
        val mtimes = mutableMapOf("pages/Foo.md" to 1000L, "pages/Bar.md" to 1000L)
        val listRecursive: suspend (String) -> List<Pair<String, Long>> = { mtimes.entries.map { it.key to it.value } }
        val readSafFile: suspend (String) -> String? = { path -> content[path] }

        worktree.syncFromSafRoot(listRecursive, readSafFile)
        assertTrue(worktree.isFresh(listRecursive))

        // Addition: a new SAF entry appears while every existing entry's mtime is unchanged.
        mtimes["pages/NewFile.md"] = 1000L
        content["pages/NewFile.md"] = "new"
        assertFalse(
            worktree.isFresh(listRecursive),
            "an added entry must be detected as stale even though existing entries' mtimes are unchanged",
        )

        // Resync, then check the deletion direction the same way.
        worktree.syncFromSafRoot(listRecursive, readSafFile)
        assertTrue(worktree.isFresh(listRecursive))
        mtimes.remove("pages/Bar.md")
        content.remove("pages/Bar.md")
        assertFalse(
            worktree.isFresh(listRecursive),
            "a removed entry must be detected as stale even though remaining entries' mtimes are unchanged",
        )
    }

    // ── Task 8.1.1c: path-mapper round trip ─────────────────────────────────────────────────────

    @Test
    fun `toUserFacingPath and toGitRelativePath round trip for a repoRoot at the SAF tree root and for a repoRoot nested under a wikiSubdir`() {
        // repoRoot at the top of the SAF tree.
        val rootSafRoot = "saf://root"
        val rootWorktree = newWorktree(rootSafRoot)
        val rootShadowAbsolute = "${rootWorktree.worktreeRootPath}/pages/Foo.md"
        val rootUserFacing = rootWorktree.toUserFacingPath(rootShadowAbsolute)
        assertEquals("$rootSafRoot/pages/Foo.md", rootUserFacing)
        assertEquals("pages/Foo.md", rootWorktree.toGitRelativePath(rootUserFacing))

        // repoRoot nested under a wikiSubdir within the SAF tree (the "detected repo root above a
        // nested wiki folder" case from GitSetupScreen.kt's doc comment).
        val nestedSafRoot = "saf://root/wikiSubdir/nested"
        val nestedWorktree = newWorktree(nestedSafRoot)
        val nestedShadowAbsolute = "${nestedWorktree.worktreeRootPath}/pages/Bar.md"
        val nestedUserFacing = nestedWorktree.toUserFacingPath(nestedShadowAbsolute)
        assertEquals("$nestedSafRoot/pages/Bar.md", nestedUserFacing)
        assertEquals("pages/Bar.md", nestedWorktree.toGitRelativePath(nestedUserFacing))

        // Regression guard for the merge() bug fixed in commit 61b689fa61: toUserFacingPath()
        // requires a shadow-tree-absolute input (starting with worktreeRootPath). Feeding it a
        // repoRoot-prefixed path instead (as the buggy merge() code did) is NOT a no-op — the
        // prefix strip silently fails, and the SAF root gets prepended a second time.
        val repoRootPrefixedInput = "$nestedSafRoot/pages/Bar.md" // NOT shadow-tree-absolute
        val doubledResult = nestedWorktree.toUserFacingPath(repoRootPrefixedInput)
        assertEquals(
            "$nestedSafRoot/$nestedSafRoot/pages/Bar.md",
            doubledResult,
            "feeding a repoRoot-prefixed (non shadow-absolute) path must silently double the SAF root — " +
                "this is the exact contract violation fixed in merge() by commit 61b689fa61",
        )
        assertTrue(
            doubledResult != nestedUserFacing,
            "the buggy doubled-root result must differ from the correct shadow-absolute round trip",
        )
    }
}
