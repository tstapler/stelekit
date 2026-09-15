package dev.stapler.stelekit.transfer

import kotlinx.coroutines.flow.Flow

/**
 * Minimal pluggable frame-in/frame-out send seam so future transports (WebRTC, BLE, audio) plug
 * in without touching Layer 1 (fountain codec) or Layer 2 (chunk framing).
 *
 * See ADR-006 (`project_plans/camera-qr-export/decisions/ADR-006-frametransport-vs-synctransport-naming.md`)
 * for why this is named `FrameTransport` rather than a bare `Transport` — the repo already has an
 * unrelated `SyncTransport` (ADR-016, file sync) and the names are kept lexically distinct to
 * avoid confusing the two.
 *
 * `send` is `suspend` (not a plain `Sequence` consumer) so the sender can pace frame emission
 * (~400 ms/frame for QR display) in a cancellable way, matching this repo's coroutine-scope-
 * ownership conventions (CLAUDE.md).
 *
 * Paper-validation (KDoc only, no implementation): a hypothetical `AudioTransport` fits this same
 * interface even though audio is the transport most likely to strain a frame-per-image
 * abstraction (much lower bandwidth than QR, and framing would be chunked-tone-bursts rather than
 * one-QR-per-frame):
 * ```kotlin
 * class AudioTransportSender(private val speaker: AudioOutput) : FrameTransportSender {
 *     override suspend fun send(frames: Flow<ByteArray>) {
 *         frames.collect { frame -> speaker.playToneBurst(frame) } // one opaque frame -> one burst
 *     }
 * }
 * class AudioTransportReceiver(private val mic: AudioInput) : FrameTransportReceiver {
 *     override fun frames(): Flow<ByteArray> = mic.toneBurstStream() // demodulated bytes out
 * }
 * ```
 * The seam holds: both sides still only ever produce/consume opaque `ByteArray` frames.
 */
interface FrameTransportSender {
    /** Sends [frames], one opaque frame per emission. Paces emission and is cancellable. */
    suspend fun send(frames: Flow<ByteArray>)
}

/** Receive half of [FrameTransportSender]'s seam. See [FrameTransportSender] KDoc for context. */
interface FrameTransportReceiver {
    /** A cold [Flow] of opaque decoded frames. Collection starts capture; cancellation stops it. */
    fun frames(): Flow<ByteArray>
}
