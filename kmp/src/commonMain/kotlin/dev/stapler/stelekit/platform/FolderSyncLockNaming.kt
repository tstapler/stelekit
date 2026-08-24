package dev.stapler.stelekit.platform

/**
 * Pure derivation of deterministic, collision-safe Web Locks API names for the folder-sync
 * feature's poll and write locks. Lives in commonMain — despite the consumer (`HostDirectorySync`,
 * Phase 6) being wasmJs-only — because it is pure string logic with no wasmJs dependency, mirroring
 * the [dev.stapler.stelekit.git.GitWriteLockNaming] convention of keeping pure logic directly
 * unit-testable from `commonTest` rather than behind a platform boundary.
 *
 * Names use a distinct `"stele-folder-sync-poll-"` / `"stele-folder-sync-write-"` prefix so they
 * never collide with [dev.stapler.stelekit.git.GitWriteLockNaming.lockNameFor]'s `"stele-write-"`
 * prefix, per `research/architecture.md` §3.2 point 1 ("do not share `GitWriteLock`'s lock name").
 *
 * See Story 1.2.1 in project_plans/web-local-folder-livesync/implementation/plan.md.
 */
object FolderSyncLockNaming {

    /**
     * Matches a literal `-` in the raw input. [writeLockNameFor] doubles every literal `-` to
     * `--` *before* [SEPARATOR_CHARS] collapses genuine separators (`/`, etc.) to a single `-` —
     * see that function's doc comment for why this ordering is what makes the two character
     * classes unambiguous.
     */
    private val LITERAL_DASH = Regex("-")

    /**
     * Matches a run of one-or-more characters that are neither alphanumeric, `.`, nor `-`. `-` is
     * deliberately excluded from this class: by the time this runs (always *after*
     * [LITERAL_DASH]'s replace), every `-` in the string is either part of an already-escaped
     * literal-dash pair (`--`) or about to be introduced by this very replace as a single-`-`
     * separator marker — never an unescaped original character. If `-` were included here, it
     * would re-collapse those escaped pairs and undo the escaping.
     */
    private val SEPARATOR_CHARS = Regex("[^A-Za-z0-9.-]+")

    /**
     * Deterministic poll-lock name for a graph. The poll lock coordinates cross-tab polling for a
     * given `graphId` — no sanitization is needed since `graphId` is already a safe identifier.
     */
    fun pollLockNameFor(graphId: String): String = "stele-folder-sync-poll-$graphId"

    /**
     * Deterministic write-lock name for a specific file within a graph's synced folder.
     * `repoRelativePath` is sanitized using the same idiom as
     * [dev.stapler.stelekit.git.GitWriteLockNaming.lockNameFor] — unsafe separator characters
     * (`/`, etc.) are collapsed into a single `-` so two different paths never sanitize to the
     * same string, and two calls for the same path always produce the same name.
     *
     * Bug fix (code-review repair loop): a literal `-` already present in [repoRelativePath] is
     * escaped to `--` *before* the separator-collapsing step runs, so it can never be confused
     * with a `-` the collapsing step introduces for an actual separator like `/`. Without this
     * escape, `"a/b"` (one `/` separator) and `"a-b"` (one literal `-`) both sanitized to the
     * identical `"a-b"` — a real lock-name collision between two different files, since a literal
     * `-` was itself treated as an "unsafe" separator character and collapsed the same way `/`
     * was. See `FolderSyncLockNamingTest.writeLockNameFor_should_ReturnDifferentNames_When_OneInputHasLiteralDashAndOtherHasPathSeparator`.
     */
    fun writeLockNameFor(graphId: String, repoRelativePath: String): String {
        val sanitized = LITERAL_DASH.replace(repoRelativePath, "--")
            .let { SEPARATOR_CHARS.replace(it, "-") }

        return "stele-folder-sync-write-$graphId-$sanitized"
    }
}
