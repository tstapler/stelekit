// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

actual class PngEncoder actual constructor() {
    actual fun encode(bitmap: ImageBitmap): ByteArray = try {
        Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)?.bytes ?: ByteArray(0)
    } catch (e: Throwable) {
        // Throwable: an out-of-memory condition must yield the empty-array failure contract, not crash.
        ByteArray(0)
    }
}
