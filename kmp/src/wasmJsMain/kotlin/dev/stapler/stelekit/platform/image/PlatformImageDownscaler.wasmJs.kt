// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

/** The browser decodes and caches images itself; callers use the original. */
actual object PlatformImageDownscaler : ImageDownscalerPort {
    override fun downscale(source: ByteArray, maxEdges: List<Int>, quality: Int): List<ByteArray?>? = null
}
