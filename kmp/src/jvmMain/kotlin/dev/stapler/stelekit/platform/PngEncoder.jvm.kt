// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual class PngEncoder actual constructor() {
    actual fun encode(bitmap: ImageBitmap): ByteArray = try {
        val out = ByteArrayOutputStream()
        if (ImageIO.write(bitmap.toAwtImage(), "png", out)) out.toByteArray() else ByteArray(0)
    } catch (e: Throwable) {
        // Throwable: an OutOfMemoryError must yield the empty-array failure contract, not crash (same rule as ImageEncoder).
        ByteArray(0)
    }
}
