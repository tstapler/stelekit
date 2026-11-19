// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

actual class PngEncoder actual constructor() {
    actual fun encode(bitmap: ImageBitmap): ByteArray = try {
        val out = ByteArrayOutputStream()
        if (bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, PNG_QUALITY_IGNORED, out)) out.toByteArray() else ByteArray(0)
    } catch (e: Throwable) {
        // Throwable: an OutOfMemoryError must yield the empty-array failure contract, not crash.
        ByteArray(0)
    }

    private companion object {
        /** PNG is lossless; `Bitmap.compress` ignores the quality argument for it. */
        const val PNG_QUALITY_IGNORED = 100
    }
}
