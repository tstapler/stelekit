package dev.stapler.stelekit.transfer.qrcode

/**
 * Encoder tuning knobs for QR transfer (ADR-001 wire format, UQ-1 in
 * `project_plans/camera-qr-export/implementation/plan.md`).
 *
 * A later epic (Story 3.1.1) extends this file with the user-facing feature flag
 * (`enabled`/`framesPerSecond`/`reduceMotion`) — this story only introduces the encoder-sizing
 * defaults needed by [FountainEncoder]/[FountainCodec].
 */
object QrTransferSettings {

    // ponytail: provisional default pending the real-device scan spike (Task 2.1.3a / UQ-1 in
    // plan.md) — not yet empirically tuned. UQ-1 is a manual/hardware spike (render QR at
    // increasing fragLen x EC-level {L,M,Q}, scan from ~30-50cm on a mid-range Android device)
    // that cannot be run in this environment; 512 bytes is the plan's own conservative fallback.
    const val DEFAULT_MAX_FRAGMENT_BYTES: Int = 512

    // ponytail: provisional default pending Task 2.1.3a (UQ-1) — Error Correction level M
    // balances redundancy against data density; not yet validated against a real scan.
    val DEFAULT_ERROR_CORRECTION_LEVEL: QrErrorCorrectionLevel = QrErrorCorrectionLevel.M

    // ponytail: provisional default pending Task 2.1.3a (UQ-1) — caps QR version (module count)
    // at v20 so a single frame stays scannable at arm's length; not yet validated against a real
    // scan.
    const val MAX_QR_VERSION: Int = 20

    /** Redundancy multiplier applied on top of the pure-fragment count for the pre-flight estimate. */
    const val DEFAULT_REDUNDANCY_MULTIPLIER: Double = 1.0
}

/** Mirrors ZXing's `ErrorCorrectionLevel` values without taking a ZXing dependency in `commonMain`. */
enum class QrErrorCorrectionLevel {
    L,
    M,
    Q,
    H,
}
