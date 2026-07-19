package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [GitWriteLockNaming.lockNameFor] — the pure sanitizer backing
 * `GitWriteLock.lockNameFor` (wasmJsMain). See Story 2.3.1 in
 * project_plans/web-git-writeback/implementation/plan.md and the Epic 2.3 section of
 * validation.md (`TC-2.3.1-A`, `TC-2.3.1-B`).
 *
 * Note: the corresponding integration tests `IT-2.3.1-A/B/C/D` (wasmJsTest,
 * `GitWriteLockIntegrationTest.kt`) require `WasmGitWriteService.push()` to exist first — that
 * ships in Phase 3 — so they are not written here.
 */
class GitWriteLockNamingTest {

    // ── TC-2.3.1-A ────────────────────────────────────────────────────────────

    @Test
    fun `TC-2_3_1-A lockNameFor sanitizes a remote URL into a Locks-API-safe, deterministic name`() {
        val name = GitWriteLockNaming.lockNameFor("https://github.com/tstapler/steno-wiki.git")

        assertEquals("stele-write-github.com-tstapler-steno-wiki", name)

        // No reserved/unsafe characters — only ASCII letters, digits, '.', and '-'.
        assertTrue(name.matches(Regex("^[A-Za-z0-9.-]+$")), "name '$name' contains unsafe characters")

        // Deterministic: calling it again on the same input produces the same name.
        assertEquals(name, GitWriteLockNaming.lockNameFor("https://github.com/tstapler/steno-wiki.git"))
    }

    // ── TC-2.3.1-B ────────────────────────────────────────────────────────────

    @Test
    fun `TC-2_3_1-B lockNameFor produces distinct names for different remotes so unrelated pushes never contend`() {
        val github = GitWriteLockNaming.lockNameFor("https://github.com/tstapler/steno-wiki.git")
        val gitlab = GitWriteLockNaming.lockNameFor("https://gitlab.com/tstapler/steno-wiki.git")
        val otherRepo = GitWriteLockNaming.lockNameFor("https://github.com/tstapler/other-repo.git")
        val otherOwner = GitWriteLockNaming.lockNameFor("https://github.com/someone-else/steno-wiki.git")

        // Different host (GitHub vs GitLab), same owner/repo — must never collide.
        assertNotEquals(github, gitlab)
        // Different repo, same host/owner — must never collide.
        assertNotEquals(github, otherRepo)
        // Different owner, same host/repo — must never collide.
        assertNotEquals(github, otherOwner)

        // Same URL with vs. without a trailing ".git" normalizes to the SAME name — this is the
        // exact normalization rule Story 2.3.1 requires, made explicit so it can't silently become
        // asymmetric.
        val withDotGit = GitWriteLockNaming.lockNameFor("https://github.com/tstapler/steno-wiki.git")
        val withoutDotGit = GitWriteLockNaming.lockNameFor("https://github.com/tstapler/steno-wiki")
        assertEquals(withDotGit, withoutDotGit)
    }
}
