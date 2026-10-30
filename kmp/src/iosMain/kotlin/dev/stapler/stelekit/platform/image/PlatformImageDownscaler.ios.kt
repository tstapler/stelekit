// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

/** No iOS decoder yet; callers fall back to the original image. */
actual object PlatformImageDownscaler : ImageDownscalerPort {
    override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? = null
}
