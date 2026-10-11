// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal const val JPEG_DATA_URL_PREFIX = "data:image/jpeg;base64,"

/**
 * Decodes a `canvas.toDataURL('image/jpeg', ...)` result into raw JPEG bytes.
 *
 * Returns `null` (not a thrown exception) if [dataUrl] isn't JPEG-prefixed — browsers without
 * JPEG `toDataURL` support silently fall back to PNG rather than throwing, so this is the
 * defensive check that turns that silent-wrong-format case into a distinguishable failure
 * instead of attempting to decode a PNG payload as JPEG.
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun parseJpegDataUrl(dataUrl: String): ByteArray? {
    if (!dataUrl.startsWith(JPEG_DATA_URL_PREFIX)) return null
    return try {
        Base64.decode(dataUrl.removePrefix(JPEG_DATA_URL_PREFIX))
    } catch (e: IllegalArgumentException) {
        null
    }
}
