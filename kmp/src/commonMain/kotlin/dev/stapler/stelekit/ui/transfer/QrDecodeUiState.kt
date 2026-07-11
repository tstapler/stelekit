package dev.stapler.stelekit.ui.transfer

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.transfer.qrcode.ScanHint

/**
 * Sealed state for [QrDecodeViewModel] (Story 3.2.2), mirroring [QrEncodeUiState]'s
 * sealed-state-with-payload shape. Non-linear progress is shown as [Scanning.uniqueFragments], not
 * a percentage — the fountain encoder loops indefinitely, so there is no meaningful "% complete."
 */
sealed interface QrDecodeUiState {

    /** Not yet started — brief, pre-permission-check. */
    data object Idle : QrDecodeUiState

    /**
     * Pre-flight gate rejected before [Scanning] was ever entered — e.g. no streaming camera
     * ([DomainError.SensorError.HardwareUnavailable]) or permission denied
     * ([DomainError.SensorError.PermissionDenied]). Distinct, non-generic copy per reason (UX
     * criterion 11).
     */
    data class PreflightFailed(val reason: DomainError.SensorError) : QrDecodeUiState

    /**
     * Actively scanning. [uniqueFragments] drives the non-linear "Receiving… (N fragments)"
     * progress copy; [stalledSeconds] and [hint] drive differentiated guidance (Story 3.2.3).
     */
    data class Scanning(
        val uniqueFragments: Int,
        val stalledSeconds: Int,
        val hint: ScanHint? = null,
    ) : QrDecodeUiState

    /** Brief: [dev.stapler.stelekit.transfer.qrcode.ChunkBuffer.reassemble] proof-gate check. */
    data object Reassembling : QrDecodeUiState

    /** [dev.stapler.stelekit.transfer.qrcode.QrImportService.import] pipeline running. */
    data object Importing : QrDecodeUiState

    /** Import succeeded; [pageName] is the (possibly disambiguated) name the page was saved under. */
    data class Success(val pageName: PageName) : QrDecodeUiState

    /**
     * Terminal error — never a generic "something went wrong" (every variant has distinct copy
     * via the shared [dev.stapler.stelekit.error.toUiMessage] extension).
     *
     * Deliberately typed as the general [DomainError], not narrowly
     * [DomainError.QrTransferError]: [dev.stapler.stelekit.transfer.qrcode.QrImportService.import]
     * can legitimately fail with a non-QR-specific error (e.g.
     * [DomainError.ValidationError.ConstraintViolation] for a malformed name, or
     * [DomainError.DatabaseError.WriteFailed] from [dev.stapler.stelekit.db.DatabaseWriteActor]) —
     * forcing every possible failure through the six fixed [DomainError.QrTransferError] variants
     * would require fabricating a misleading message for cases that aren't actually chunk/transfer
     * failures. [DomainError]'s own `toUiMessage()` already covers every variant uniformly.
     */
    data class Failed(val error: DomainError) : QrDecodeUiState

    /** User cancelled; no write ever occurred. */
    data object Cancelled : QrDecodeUiState
}
