package dev.stapler.stelekit.transfer.qrcode

/**
 * Differentiated scan guidance shown by [dev.stapler.stelekit.ui.transfer.QrDecodeScreen] (Story
 * 3.2.2, UX gap G5 in `design/ux.md`). Derived by [QrTransferCoordinator] from its
 * directly-injected `scan` diagnostics function — never from the
 * [dev.stapler.stelekit.transfer.FrameTransportReceiver] data path.
 *
 * Lives in `transfer.qrcode` (not `ui.transfer`) because [QrTransferCoordinator] — a non-UI class —
 * needs this type; UI depends on transfer, never the reverse (CLAUDE.md layering).
 */
enum class ScanHint {
    /** A QR was found but failed magic/version/CRC — [ScanResult.NotSteleKitCode]. */
    WrongCode,

    /** Mean luminance of the camera frame is below a usable threshold. */
    LowLight,

    /** No new fragment admitted for several seconds despite active scanning. */
    Stalled,
}
