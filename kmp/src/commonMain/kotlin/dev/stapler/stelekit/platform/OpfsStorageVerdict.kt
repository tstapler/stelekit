package dev.stapler.stelekit.platform

/** The five probe facts of one browser (D-125). The two `*Verified` facts are the two replace paths. */
data class OpfsProbeFacts(
    val rootPresent: Boolean,
    val createWritable: Boolean,
    val moveVerified: Boolean,
    val copyVerifyVerified: Boolean,
    val sliceRead: Boolean,
)

/** How an Available browser replaces a file: `FileSystemFileHandle.move()` or write-temp-then-verified-copy. */
enum class ReplacePath { Move, CopyVerify }

/** The per-browser storage decision. Persistence is not a fact of it: a denied `persist()` is only a warning. */
sealed interface OpfsStorageVerdict {
    data class Available(val replacePath: ReplacePath) : OpfsStorageVerdict

    /** No probe has run yet; the status stays `Checking`. A missing fact never yields Available. */
    data object NotProbed : OpfsStorageVerdict

    data class Unavailable(val reason: StorageReason) : OpfsStorageVerdict

    companion object {
        /** Rules apply in order; [facts] null means no probe has run. */
        fun decide(facts: OpfsProbeFacts?): OpfsStorageVerdict = when {
            facts == null -> NotProbed
            !facts.rootPresent -> Unavailable(StorageReason.NoOpfs)
            !facts.createWritable -> Unavailable(StorageReason.NoCreateWritable)
            !facts.moveVerified && !facts.copyVerifyVerified -> Unavailable(StorageReason.NoAtomicReplace)
            !facts.sliceRead -> Unavailable(StorageReason.NoSliceRead)
            facts.moveVerified -> Available(ReplacePath.Move)
            else -> Available(ReplacePath.CopyVerify)
        }
    }
}

/**
 * State of the wasm sidecar-root preload (D-124). Terminal states carry [atElapsedMs], the time since the check
 * started when they were reached, so the pure reducer can tell an on-time result from a late one.
 */
sealed interface PreloadState {
    data object NotStarted : PreloadState
    data object Loading : PreloadState
    data class Ok(val atElapsedMs: Long = 0) : PreloadState

    /** [reason] is [StorageReason.PreloadFailed] or [StorageReason.PreloadTooLarge]. */
    data class Failed(val reason: StorageReason, val atElapsedMs: Long = 0) : PreloadState
}

/** Combines verdict, preload state and elapsed time into the [V2StorageStatus] the UI reads (D-124, D-128, D-131). */
object V2StorageStatusReducer {
    const val CHECK_TIMEOUT_MS: Long = 10_000
    const val LATE_OK_RECOVERY_MS: Long = 60_000

    /**
     * - An Unavailable verdict is final at once.
     * - Until the verdict is Available and the preload is finished, the status is Checking for [CHECK_TIMEOUT_MS],
     *   then Unavailable(CheckTimedOut).
     * - An Ok preload that finishes within [LATE_OK_RECOVERY_MS] after the timeout recovers to Ready (once: a Failed
     *   that finishes after the timeout is ignored); a later Ok stays CheckTimedOut.
     */
    fun reduce(
        verdict: OpfsStorageVerdict,
        preload: PreloadState,
        elapsedMs: Long,
        sessionOnly: Boolean = false,
        privateSuspected: Boolean = false,
    ): V2StorageStatus {
        if (verdict is OpfsStorageVerdict.Unavailable) return V2StorageStatus.Unavailable(verdict.reason)
        val timedOut = V2StorageStatus.Unavailable(StorageReason.CheckTimedOut)
        val pending = if (elapsedMs >= CHECK_TIMEOUT_MS) timedOut else V2StorageStatus.Checking
        if (verdict !is OpfsStorageVerdict.Available) return pending
        return when (preload) {
            PreloadState.NotStarted, PreloadState.Loading -> pending
            is PreloadState.Ok ->
                if (preload.atElapsedMs <= CHECK_TIMEOUT_MS + LATE_OK_RECOVERY_MS) {
                    V2StorageStatus.Ready(sessionOnly, privateSuspected)
                } else {
                    timedOut
                }
            is PreloadState.Failed ->
                if (preload.atElapsedMs <= CHECK_TIMEOUT_MS) V2StorageStatus.Unavailable(preload.reason) else timedOut
        }
    }
}
