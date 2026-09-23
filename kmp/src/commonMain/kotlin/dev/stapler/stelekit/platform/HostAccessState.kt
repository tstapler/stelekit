package dev.stapler.stelekit.platform

/**
 * State of a wasmJs graph's connection to its host directory (via the File System Access API),
 * queried by commonMain UI (e.g. a resume-access banner) without downcasting to the wasmJs
 * `FileSystem` actual. See `project_plans/web-local-folder-livesync/research/architecture.md`
 * §1.3 and `research/ux.md` for the "reconnect vs. conflict" distinction between [Denied] and
 * [Disconnected].
 *
 * Exhaustive by design: a `when (state: HostAccessState)` without an `else` branch fails to
 * compile if any variant is added or removed, forcing every UI branch to be updated in lockstep.
 */
sealed interface HostAccessState {
    /** No host directory has ever been connected for this graph. The default/inert state. */
    data object NotApplicable : HostAccessState

    /** A host directory handle exists and is currently readable/writable without a prompt. */
    data object Granted : HostAccessState

    /**
     * A previously-granted host directory handle exists but the browser requires a fresh
     * user gesture (e.g. a click) to re-confirm permission before it can be used again.
     */
    data object PromptNeeded : HostAccessState

    /** The user explicitly declined permission to access the host directory. */
    data object Denied : HostAccessState

    /**
     * The stored host directory handle went stale — e.g. a `NotFoundError` was thrown because
     * the directory was moved or deleted outside the browser. Distinct from [Denied]: the user
     * never revoked permission, the handle itself no longer resolves. [reason] carries a
     * human-readable diagnostic (e.g. the underlying DOM exception name/message).
     */
    data class Disconnected(val reason: String) : HostAccessState

    /**
     * Epic 4.1 (Task 4.1.2b): the user explicitly detached a previously-linked host directory via
     * `HostDirectorySync.unlinkHostDirectory()` — the graph stays fully usable, now backed only by
     * OPFS. Distinct from [Disconnected] (which implies something went wrong — a stale/missing
     * handle) and from [NotApplicable] (which means "never linked at all"/"no host directory
     * concept on this platform/graph"): this state carries the deliberate-choice history so a
     * future session never silently tries to resume the detached link (see
     * `unlinkHostDirectory`'s own doc comment — it also deletes the persisted IndexedDB handle
     * entry, which is what actually prevents that resurrection; this state is the in-session UI
     * signal). Renders nothing in `FolderSyncStatusBadge`, same as [NotApplicable].
     */
    data object Unlinked : HostAccessState
}
