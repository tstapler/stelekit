package dev.stapler.stelekit.transfer.qrcode

import kotlin.jvm.JvmInline

/**
 * A page's markdown that has passed [ChunkBuffer.reassemble]'s whole-payload CRC32 proof gate.
 *
 * The `internal` constructor makes "verified" a type-level fact (Parse-Don't-Validate, per the
 * camera-qr-export implementation plan): no code outside this module can construct one directly,
 * and `reassemble()`'s success branch is the only path that does — so holding a
 * [VerifiedTransferPayload] is itself proof the CRC32 check passed, not a claim a caller has to
 * separately verify.
 */
@JvmInline
value class VerifiedTransferPayload internal constructor(val markdown: String)
