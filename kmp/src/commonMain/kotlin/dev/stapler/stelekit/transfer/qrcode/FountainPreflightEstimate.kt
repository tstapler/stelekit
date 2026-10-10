package dev.stapler.stelekit.transfer.qrcode

/**
 * Pre-flight estimate of what sending a payload will cost in QR frames, surfaced by the UI
 * *before* any frame is displayed (Story 2.1.3, Task 2.1.3b — fail-fast on oversize).
 *
 * @property fragmentLen Bytes per fountain fragment ([FountainEncoder]'s chosen fragment size).
 * @property pureFragmentCount `ceil(payloadLen / fragmentLen)` — the minimum frames needed with
 *   zero redundancy.
 * @property estimatedFrameCount `ceil(pureFragmentCount * redundancyMultiplier)` — the frame
 *   count actually recommended to send (includes fountain-coded redundancy for lossy scanning).
 * @property estimatedTotalBytes `estimatedFrameCount * (fragmentLen + FRAME_OVERHEAD_BYTES)` —
 *   total bytes that will cross the QR channel, including ADR-001's 24-byte-per-frame header/CRC
 *   overhead.
 * @property isOversize `true` when [estimatedFrameCount] exceeds the caller's threshold — the UI
 *   uses this to reject the transfer before entering the send flow.
 */
data class FountainPreflightEstimate(
    val fragmentLen: Int,
    val pureFragmentCount: Int,
    val estimatedFrameCount: Int,
    val estimatedTotalBytes: Int,
    val isOversize: Boolean,
)
