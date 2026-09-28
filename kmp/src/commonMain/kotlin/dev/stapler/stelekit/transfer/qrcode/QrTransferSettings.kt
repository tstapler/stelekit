package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.Settings

/**
 * Encoder tuning knobs + user-facing feature flag for QR transfer (ADR-001 wire format, ADR-004
 * fps/WCAG, UQ-1 in `project_plans/camera-qr-export/implementation/plan.md`).
 *
 * Story 3.1.1 extends the Story 2.1.3 encoder-sizing-only file with the `platform.Settings`-backed
 * feature flag and tunables, following the [dev.stapler.stelekit.tags.TagSettings] pattern —
 * instance properties backed by [Settings], namespaced under `qr_transfer.*`. The static defaults
 * ([DEFAULT_MAX_FRAGMENT_BYTES] etc.) stay as companion members so [FountainEncoder]/[FountainCodec]
 * call sites (`QrTransferSettings.DEFAULT_MAX_FRAGMENT_BYTES`) are unaffected by the object→class
 * conversion.
 */
class QrTransferSettings(private val platformSettings: Settings) {

    /** Feature flag gating both entry points (Story 3.1.4 "Send via QR", Epic 3.2 "Import via camera") and the receive pre-flight. Default off (dogfood-gated). */
    var enabled: Boolean
        get() = platformSettings.getBoolean(KEY_ENABLED, false)
        set(value) = platformSettings.putBoolean(KEY_ENABLED, value)

    /**
     * Display frame-advance rate in fps. ADR-004's ≤3fps WCAG 2.3.1 hard ceiling is a
     * **compile-time-visible reject, not a clamp**: a request above [MAX_FRAMES_PER_SECOND]
     * throws [IllegalArgumentException] and stores nothing, so a misconfiguration is visible in
     * logs/support rather than silently rewritten to a safe value.
     */
    var framesPerSecond: Double
        get() = platformSettings.getString(KEY_FRAMES_PER_SECOND, DEFAULT_FRAMES_PER_SECOND.toString())
            .toDoubleOrNull() ?: DEFAULT_FRAMES_PER_SECOND
        set(value) {
            require(value > 0.0) { "framesPerSecond must be positive, was $value" }
            require(value <= MAX_FRAMES_PER_SECOND) {
                "framesPerSecond=$value exceeds the WCAG 2.3.1 ceiling of $MAX_FRAMES_PER_SECOND fps " +
                    "(ADR-004) — rejected, not clamped"
            }
            platformSettings.putString(KEY_FRAMES_PER_SECOND, value.toString())
        }

    /** Accessible transfer mode (S5): drops to 1-2fps tap-to-advance, always below the ≤3fps ceiling. */
    var reduceMotion: Boolean
        get() = platformSettings.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = platformSettings.putBoolean(KEY_REDUCE_MOTION, value)

    var ecLevel: QrErrorCorrectionLevel
        get() = platformSettings.getString(KEY_EC_LEVEL, DEFAULT_ERROR_CORRECTION_LEVEL.name)
            .let { stored -> QrErrorCorrectionLevel.entries.find { it.name == stored } }
            ?: DEFAULT_ERROR_CORRECTION_LEVEL
        set(value) = platformSettings.putString(KEY_EC_LEVEL, value.name)

    var maxFragmentBytes: Int
        get() = platformSettings.getString(KEY_MAX_FRAGMENT_BYTES, DEFAULT_MAX_FRAGMENT_BYTES.toString())
            .toIntOrNull() ?: DEFAULT_MAX_FRAGMENT_BYTES
        set(value) = platformSettings.putString(KEY_MAX_FRAGMENT_BYTES, value.toString())

    /**
     * One-time-ever, per-device dismissible first-use explainer banner shown on the sender's
     * first [dev.stapler.stelekit.ui.transfer.QrEncodeScreen] `Displaying` render (Task 3.1.3c,
     * S3). Never re-shown once set.
     */
    var seenEncoderExplainer: Boolean
        get() = platformSettings.getBoolean(KEY_SEEN_ENCODER_EXPLAINER, false)
        set(value) = platformSettings.putBoolean(KEY_SEEN_ENCODER_EXPLAINER, value)

    /**
     * One-time-ever, per-device dismissible first-use explainer shown on the receiver's first
     * [dev.stapler.stelekit.ui.transfer.QrDecodeScreen] `Scanning` render (Task 3.2.3c, S8/S9).
     * Mirrors [seenEncoderExplainer]'s pattern on the sender side. Never re-shown once set.
     */
    var seenDecoderExplainer: Boolean
        get() = platformSettings.getBoolean(KEY_SEEN_DECODER_EXPLAINER, false)
        set(value) = platformSettings.putBoolean(KEY_SEEN_DECODER_EXPLAINER, value)

    companion object {
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

        // ponytail: Task 3.3.5a (hardware-in-the-loop reliability pass, resolves UQ-2 — the
        // empirical steady-state frame-interval floor at which the Android receiver reliably
        // locks on) is OUTSTANDING — real hardware and two physical devices, cannot be run in
        // this environment. DEFAULT_FRAMES_PER_SECOND below is the plan's own provisional 2.5fps
        // default (plan.md UQ-2), not yet tuned against measured lock-on/frame-loss rates at
        // 2.0/2.5/3.0 fps. Do not treat this default as validated until Task 3.3.5a records real
        // transfer results and updates this KDoc + the constant.
        /** ADR-004 default: 2.5fps (400ms/frame) — within the ≤3fps WCAG 2.3.1 hard ceiling. */
        const val DEFAULT_FRAMES_PER_SECOND: Double = 2.5

        /** ADR-004 hard ceiling: [framesPerSecond] rejects (does not clamp) any value above this. */
        const val MAX_FRAMES_PER_SECOND: Double = 3.0

        private const val KEY_ENABLED = "qr_transfer.enabled"
        private const val KEY_FRAMES_PER_SECOND = "qr_transfer.frames_per_second"
        private const val KEY_REDUCE_MOTION = "qr_transfer.reduce_motion"
        private const val KEY_EC_LEVEL = "qr_transfer.ec_level"
        private const val KEY_MAX_FRAGMENT_BYTES = "qr_transfer.max_fragment_bytes"
        private const val KEY_SEEN_ENCODER_EXPLAINER = "qr_transfer.seen_encoder_explainer"
        private const val KEY_SEEN_DECODER_EXPLAINER = "qr_transfer.seen_decoder_explainer"
    }
}

/** Mirrors ZXing's `ErrorCorrectionLevel` values without taking a ZXing dependency in `commonMain`. */
enum class QrErrorCorrectionLevel {
    L,
    M,
    Q,
    H,
}
