// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import coil3.PlatformContext
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every image block / asset card used to build its own ImageLoader with its own default-sized
 * memory cache, so image memory scaled with the number of loaders instead of being bounded.
 */
class SteleKitImageLoaderMemoryTest {

    private val context = PlatformContext.INSTANCE

    @Test
    fun `all loaders share one memory cache`() {
        val a = buildSteleKitImageLoader(context, null, null)
        val b = buildSteleKitImageLoader(context, null, null)
        assertNotNull(a.memoryCache)
        assertSame(a.memoryCache, b.memoryCache)
    }

    @Test
    fun `shared memory cache is bounded below a quarter of the heap budget`() {
        val max = checkNotNull(sharedImageMemoryCache(context).maxSize)
        val heapBytes = Runtime.getRuntime().maxMemory()
        assertTrue(max in 1 until heapBytes / 4, "cache max=$max heap=$heapBytes")
    }
}
