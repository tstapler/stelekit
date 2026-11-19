// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Encodes a rendered [ImageBitmap] as PNG (Story 5.2.2). Each platform supplies its own `actual`:
 * `ImageIO` on the JVM, `Bitmap.compress` on Android, skiko's `Image.encodeToData` on wasmJs and iOS (Spike 0.1.3c showed the
 * wasmJs skiko path works; it does not use the `<canvas>` seam of BUG-003's JPEG encoder).
 */
expect class PngEncoder() {
    /** The PNG bytes of [bitmap], or an empty array when encoding failed (including running out of memory). */
    fun encode(bitmap: ImageBitmap): ByteArray
}
