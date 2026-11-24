// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes encoded image bytes (PNG, JPEG, WebP, GIF) to an [ImageBitmap] at the pixel size they encode. Each platform supplies its
 * own `actual`: skiko on the JVM, iOS and wasmJs, `BitmapFactory` on Android. It does not sub-sample: callers hand it bytes that were
 * already reduced (see [dev.stapler.stelekit.platform.image.PlatformImageDownscaler]).
 */
expect class ImageBitmapDecoder() {
    /** The bitmap, or null when the bytes do not decode or memory ran out. */
    fun decode(bytes: ByteArray): ImageBitmap?
}
