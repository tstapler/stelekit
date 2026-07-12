package dev.stapler.stelekit.transfer

import kotlin.jvm.JvmInline

/** Identifies a single QR transfer session. Distinct from [ChunkIndex] to prevent swapping at call sites. */
@JvmInline
value class TransferId(val value: Int)

/** Zero-based index of a chunk within a transfer's fountain-coded frame sequence. */
@JvmInline
value class ChunkIndex(val value: Int)

/** CRC32 checksum of the full reassembled payload — verifies end-to-end integrity after decode. */
@JvmInline
value class PayloadChecksum(val value: Int)

/** CRC32 checksum of a single chunk's bytes — verifies per-frame integrity before reassembly. */
@JvmInline
value class ChunkChecksum(val value: Int)
