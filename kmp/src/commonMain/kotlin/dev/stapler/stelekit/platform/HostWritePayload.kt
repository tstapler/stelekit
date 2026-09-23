package dev.stapler.stelekit.platform

/**
 * Content payload for a pending write-through to the host filesystem, enqueued when
 * [ReconciliationOutcome.BrowserOnlyNeedsPush] is classified or when a subsequent in-browser edit
 * needs to be flushed to disk. Defined alongside [ReconciliationOutcome] rather than deferred to a
 * Phase 4 footnote so that `flushHostWrite` (Task 4.2.2a) can dispatch on it exhaustively — the
 * compiler enforces that every payload kind is handled, matching the exhaustiveness rationale for
 * [ReconciliationOutcome].
 *
 * See Task 1.4.1d in `project_plans/web-local-folder-livesync/implementation/plan.md`.
 */
sealed interface HostWritePayload {

    /** A plain-text write — the common case for unencrypted Markdown pages. */
    data class Text(val content: String) : HostWritePayload

    /**
     * A raw-bytes write — used for paranoid-mode encrypted `.md.stek` content.
     *
     * Bug fix (code-review repair loop): overrides [equals]/[hashCode] to compare [data] by
     * content ([ByteArray.contentEquals]/[ByteArray.contentHashCode]) rather than Kotlin's default
     * `data class`-generated referential [ByteArray] equality — the same pitfall
     * `HostReconciliation.kt`'s `classifyReconciliationBytes` explicitly guards against elsewhere
     * in this project. Without this override, two [Bytes] payloads with byte-identical content
     * compare unequal.
     */
    data class Bytes(val data: ByteArray) : HostWritePayload {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Bytes && data.contentEquals(other.data))

        override fun hashCode(): Int = data.contentHashCode()
    }

    /** The path should be deleted from the host filesystem. */
    data object Delete : HostWritePayload
}
