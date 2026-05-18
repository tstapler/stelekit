// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Wasm/JS JPEG encoder stub.
 *
 * Full implementation would use a Canvas 2D API or wasm-based encoder.
 * Returns an empty [ByteArray] for now so the expect/actual contract is satisfied.
 */
actual object ImageEncoder {
    actual fun encodeToJpeg(bitmap: ImageBitmap, quality: Int): ByteArray = ByteArray(0)
}
