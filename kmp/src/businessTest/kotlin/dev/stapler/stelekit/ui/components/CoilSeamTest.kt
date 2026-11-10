// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import coil3.EventListener
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `eventListenerFactory` seam on [buildSteleKitImageLoader] (plan B.2b). Coil 3.2.0 members
 * used (javap, 2026-11-09): `EventListener.onSuccess(ImageRequest, SuccessResult)`,
 * `EventListener.Factory`, `ImageLoader.Builder.eventListenerFactory`, `MemoryCache.size/maxSize`.
 * The listener must fire at least once so a `<=` decode gate cannot pass vacuously on zero decodes.
 */
class CoilSeamTest {
    private val context = PlatformContext.INSTANCE

    private class CountingListener(val successes: AtomicInteger) : EventListener() {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) { successes.incrementAndGet() }
    }

    // 1x1 opaque PNG.
    private val tinyPng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    @Test
    fun nullFactory_should_LeaveCacheSizingUnchanged() {
        val loader = buildSteleKitImageLoader(context, null, null)
        assertNotNull(loader.memoryCache)
        assertEquals(sharedImageMemoryCache(context).maxSize, loader.memoryCache?.maxSize)
    }

    @Test
    fun factory_should_ObserveAtLeastOneSuccessfulDecode_WithCoil320() {
        val successes = AtomicInteger()
        val loader = buildSteleKitImageLoader(context, null, null) { CountingListener(successes) }
        val file = File.createTempFile("synthetic-", ".png").apply { writeBytes(tinyPng); deleteOnExit() }

        runBlocking { loader.execute(ImageRequest.Builder(context).data(file).build()) }

        assertTrue(successes.get() >= 1, "Coil 3.2.0: EventListener.onSuccess never fired; a decode gate would pass vacuously")
    }
}
