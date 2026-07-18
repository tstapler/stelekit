package dev.stapler.stelekit.platform

/**
 * Outcome of comparing a single file's content on the host filesystem against the content last
 * known in the browser's local cache (OPFS mirror / SQLDelight-backed page content), during the
 * reconciliation pass that runs when live sync is (re)established for a graph. This is the
 * data-loss-prevention core of the web-local-folder-livesync feature — see
 * `research/architecture.md` §5.2 for the four-row table this type and [classifyReconciliation]
 * implement, and Story 1.4.1 in `project_plans/web-local-folder-livesync/implementation/plan.md`.
 *
 * Each variant maps to exactly one required action in the reconciliation pass (Phase 3):
 */
sealed interface ReconciliationOutcome {

    /**
     * Host content and cache content are byte-identical. **Required action: no-op** — nothing to
     * reconcile, the file is already in sync on both sides.
     */
    data object Identical : ReconciliationOutcome

    /**
     * Both host and cache have content for this path, but it differs. The file changed on disk
     * (e.g. edited outside the app, or by another sync participant) since the browser's cache was
     * last updated. **Required action: `GraphLoader.emitExternalFileChange`** — surface a disk
     * conflict to the user rather than silently overwriting either side, per this project's
     * Critical Finding (no silent data loss on divergence).
     */
    data object HostChangedConflict : ReconciliationOutcome

    /**
     * The file exists on the host but the browser cache has no record of it (never imported —
     * e.g. added via `git pull` before the user opted into live sync). **Required action:
     * import-as-new** — pull the host content into the cache as a newly discovered page.
     */
    data object HostOnlyNew : ReconciliationOutcome

    /**
     * The file exists in the browser cache but not on the host (created in-browser after the
     * original one-time import, never written through to disk). **Required action: enqueue
     * `hostWritePending`** — the browser is the source of truth for this path; queue it for
     * write-through to the host so it is never lost.
     */
    data object BrowserOnlyNeedsPush : ReconciliationOutcome
}

/**
 * Classifies how a single path's host content compares to its cached (browser) content, per the
 * four-row table in `research/architecture.md` §5.2.
 *
 * The both-`null` case (neither side has the file) is **unreachable by construction**: the
 * reconciliation walk that calls this function only does so for paths present on at least one
 * side (host directory listing union cache path set). Callers must uphold this precondition —
 * there is deliberately no fifth variant to represent it.
 *
 * @param hostContent the file's content as read from the host filesystem, or `null` if the file
 *   does not exist on the host.
 * @param cacheContent the file's content as last known in the browser cache, or `null` if the
 *   browser has never imported this path.
 */
fun classifyReconciliation(hostContent: String?, cacheContent: String?): ReconciliationOutcome =
    classifyByEquality(
        hostPresent = hostContent != null,
        cachePresent = cacheContent != null,
        equal = hostContent != null && cacheContent != null && hostContent == cacheContent,
    )

/**
 * Bytes-aware sibling of [classifyReconciliation] for paranoid-mode encrypted `.md.stek` content,
 * where treating ciphertext as UTF-8 text (and comparing with [String.equals]) would either throw
 * or silently corrupt the comparison. Implements the identical four-row table, comparing with
 * [ByteArray.contentEquals] — a structural, not reference, equality check — instead of
 * `String.equals`.
 *
 * The both-`null` case is unreachable by construction, exactly as in [classifyReconciliation].
 *
 * @param hostBytes the file's raw bytes as read from the host filesystem, or `null` if the file
 *   does not exist on the host.
 * @param cacheBytes the file's raw bytes as last known in the browser cache, or `null` if the
 *   browser has never imported this path.
 */
fun classifyReconciliationBytes(hostBytes: ByteArray?, cacheBytes: ByteArray?): ReconciliationOutcome =
    classifyByEquality(
        hostPresent = hostBytes != null,
        cachePresent = cacheBytes != null,
        equal = hostBytes != null && cacheBytes != null && hostBytes.contentEquals(cacheBytes),
    )

/**
 * Shared four-way decision logic for [classifyReconciliation] and [classifyReconciliationBytes],
 * defined exactly once so the two public entry points cannot drift from each other.
 */
private fun classifyByEquality(hostPresent: Boolean, cachePresent: Boolean, equal: Boolean): ReconciliationOutcome =
    when {
        hostPresent && cachePresent && equal -> ReconciliationOutcome.Identical
        hostPresent && cachePresent -> ReconciliationOutcome.HostChangedConflict
        !cachePresent -> ReconciliationOutcome.HostOnlyNew
        else -> ReconciliationOutcome.BrowserOnlyNeedsPush
    }
