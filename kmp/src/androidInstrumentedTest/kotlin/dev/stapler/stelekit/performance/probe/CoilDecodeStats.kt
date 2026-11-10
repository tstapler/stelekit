// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.probe

import coil3.EventListener
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts successful Coil decodes and the biggest decoded image, via the `eventListenerFactory`
 * seam on `buildSteleKitImageLoader`. Pass [factory] to the loader under test.
 */
class CoilDecodeStats {
    private val count = AtomicInteger()
    private val maxBytes = AtomicLong()

    val decodeCount: Int get() = count.get()
    val maxDecodeBytes: Long get() = maxBytes.get()

    val factory = EventListener.Factory { CountingListener() }

    private inner class CountingListener : EventListener() {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            count.incrementAndGet()
            maxBytes.accumulateAndGet(result.image.size, ::maxOf)
        }
    }
}
