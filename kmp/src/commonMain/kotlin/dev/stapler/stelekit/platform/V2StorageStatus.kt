package dev.stapler.stelekit.platform

/**
 * Whether this platform can store new-editor (v2) sketch files right now. Pure data: the OPFS probing that
 * decides it on the web lives in `OpfsStorageVerdict` and the wasm file system, never here.
 */
sealed interface V2StorageStatus {
    /** The verdict is not probed or the preload is unfinished; never shown as a failure (at most 10 s). */
    data object Checking : V2StorageStatus

    /**
     * Storage works. [sessionOnly]: the graph is ephemeral and its sketches live for the session only.
     * [privateSuspected]: the window looks private (see [PrivateWindowHint]), so sketches may vanish when it closes.
     */
    data class Ready(val sessionOnly: Boolean, val privateSuspected: Boolean) : V2StorageStatus

    data class Unavailable(val reason: StorageReason) : V2StorageStatus
}

/** True only for [V2StorageStatus.Ready]; `Checking` is a pending state, not availability. */
val V2StorageStatus.isReady: Boolean get() = this is V2StorageStatus.Ready

/** Why v2 sheets cannot be stored; one id per row of the web reason table (ux.md 14.1). */
enum class StorageReason {
    NoOpfs,
    NoCreateWritable,
    NoAtomicReplace,
    NoSliceRead,
    PreloadFailed,
    PreloadTooLarge,
    CheckTimedOut,
}

/** Result of `navigator.storage.persist()`. A denial is a warning, never a [StorageReason]. */
enum class PersistenceState { Granted, Denied, NotRequested, Unsupported }

/** Port to the browser's persistence request; every non-web platform uses [UnsupportedBrowserPersistence]. */
interface BrowserPersistence {
    suspend fun request(): PersistenceState
}

object UnsupportedBrowserPersistence : BrowserPersistence {
    override suspend fun request(): PersistenceState = PersistenceState.Unsupported
}

/**
 * Heuristic (INFERRED, D-136): a browser quota below 256 MiB suggests a private window. The caller also
 * requires that persistence was not granted before it shows the hint.
 */
object PrivateWindowHint {
    const val QUOTA_THRESHOLD_BYTES: Long = 256L * 1024 * 1024

    /** True when [estimatedQuotaBytes] is known and below the threshold; an unknown estimate is never suspect. */
    fun from(estimatedQuotaBytes: Long?): Boolean =
        estimatedQuotaBytes != null && estimatedQuotaBytes < QUOTA_THRESHOLD_BYTES
}
