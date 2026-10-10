package dev.stapler.stelekit.ui.transfer

import dev.stapler.stelekit.error.DomainError

/**
 * Sealed state for [QrEncodeViewModel] (Story 3.1.2), mirroring the
 * [dev.stapler.stelekit.ui.annotate.DepthModelUiState] sealed-state-with-payload precedent.
 *
 * [Displaying] and [Paused] intentionally carry identical payload fields so a lifecycle-driven
 * pause/resume (or reduce-motion tap-to-advance) preserves [frameIndex] exactly rather than
 * resetting to 0. [Complete] is reached only by an explicit user "Done sending" action — it means
 * "finished broadcasting," not "confirmed received" (QR has no back-channel; UX gap G2).
 */
sealed interface QrEncodeUiState {

    /** Not yet started. */
    data object Idle : QrEncodeUiState

    /** Loading the page/blocks and serializing — expected sub-second for the ~2KB target page. */
    data object Serializing : QrEncodeUiState

    /**
     * Actively cycling QR frames.
     *
     * @property frameIndex Current position within [chunkCount] (wraps as the fountain encoder
     *   loops indefinitely).
     * @property totalCycled Total frames shown so far this session, never wraps.
     * @property chunkCount Estimated frame count for one full cycle (pre-flight estimate).
     * @property estBytes Serialized payload size in bytes.
     */
    data class Displaying(
        val frameIndex: Int,
        val totalCycled: Int,
        val chunkCount: Int,
        val estBytes: Int,
    ) : QrEncodeUiState

    /**
     * Frame-advance loop suspended — no WCAG flash risk while paused. Carries the same payload
     * shape as [Displaying] so [frameIndex] survives the pause verbatim.
     */
    data class Paused(
        val frameIndex: Int,
        val totalCycled: Int,
        val chunkCount: Int,
        val estBytes: Int,
    ) : QrEncodeUiState

    /** User tapped "Done sending" — broadcasting finished, receipt is NOT confirmed. */
    data object Complete : QrEncodeUiState

    /** User cancelled; nothing was ever written to disk on the sender side. */
    data object Cancelled : QrEncodeUiState

    /** Serialization or pre-flight failure — never a broken/partial QR (UX gap G1). */
    data class Failed(val error: DomainError.QrTransferError) : QrEncodeUiState
}
