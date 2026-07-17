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

    private val UNSAFE_CHARS = Regex("[^A-Za-z0-9.]+")
    private val DASH_RUN = Regex("-{2,}")

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
     */
    fun writeLockNameFor(graphId: String, repoRelativePath: String): String {
        val sanitized = UNSAFE_CHARS.replace(repoRelativePath, "-")
            .let { DASH_RUN.replace(it, "-") }
            .trim('-')

        return "stele-folder-sync-write-$graphId-$sanitized"
    }
}
