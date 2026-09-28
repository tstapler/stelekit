package dev.stapler.stelekit.git

/**
 * Pure sanitizer for [dev.stapler.stelekit.git.GitWriteLock]'s (wasmJsMain) `navigator.locks.request()`
 * name. Lives in commonMain — despite `GitWriteLock` itself being wasmJs-only — because it is pure
 * string logic with no wasmJs dependency, and this codebase's convention (see `GitHostAdapter`) is to
 * keep pure logic directly unit-testable from `commonTest` rather than behind a platform boundary.
 *
 * See Pattern Decision "Web Lock scope" and Story 2.3.1 in
 * project_plans/web-git-writeback/implementation/plan.md.
 */
object GitWriteLockNaming {

    private val PROTOCOL_PREFIX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    // SCP-like SSH remote syntax, e.g. "git@github.com:tstapler/steno-wiki.git"
    private val SCP_LIKE = Regex("^[A-Za-z0-9_.-]+@([A-Za-z0-9_.-]+):(.+)$")

    private val UNSAFE_CHARS = Regex("[^A-Za-z0-9.]+")
    private val DASH_RUN = Regex("-{2,}")

    /**
     * Strips the protocol (or normalizes SCP-like SSH syntax), strips a trailing `.git` suffix
     * (with or without a trailing slash) so the same remote always normalizes identically, then
     * collapses any remaining unsafe separator (`/`, `:`, or anything else outside
     * `[A-Za-z0-9.]`) into a single `-`. The result is prefixed with `"stele-write-"`.
     *
     * Deterministic: the same URL always produces the same name. Collision-safe across distinct
     * remotes: different hosts/owners/repos never sanitize to the same string, because no
     * information is discarded — only unsafe separator characters are normalized.
     */
    fun lockNameFor(remoteUrl: String): String {
        val trimmed = remoteUrl.trim()

        val scpMatch = SCP_LIKE.matchEntire(trimmed)
        var normalized = if (scpMatch != null) {
            val (host, path) = scpMatch.destructured
            "$host/$path"
        } else {
            PROTOCOL_PREFIX.replace(trimmed, "")
        }

        normalized = normalized.removeSuffix("/")
        if (normalized.endsWith(".git", ignoreCase = true)) {
            normalized = normalized.substring(0, normalized.length - ".git".length)
        }

        val sanitized = UNSAFE_CHARS.replace(normalized, "-")
            .let { DASH_RUN.replace(it, "-") }
            .trim('-')

        return "stele-write-$sanitized"
    }
}
