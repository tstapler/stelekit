// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import coil3.PlatformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/**
 * The probe's `loaderConstructions` metric. The counter is the seam that lets the benchmark gate
 * `loaderConstructions == 1` (it was 100 per 100 presses before the loader was hoisted). The
 * 100-builds case is the `loaderPerPress` mutation proof: it shows the gate value goes red.
 */
class ImageLoaderConstructionCountTest {
    private val context = PlatformContext.INSTANCE

    @Test
    fun counter_should_CountEveryBuild_AsPerPressLoaderMutationWould() {
        resetImageLoaderConstructionsForTest()
        repeat(100) { buildSteleKitImageLoader(context, null, null) }
        assertEquals(100, imageLoaderConstructions())
    }

    @Test
    fun counter_should_StayAtOne_When_LoaderIsBuiltOnceAndReused() {
        resetImageLoaderConstructionsForTest()
        val loader = buildSteleKitImageLoader(context, null, null)
        repeat(100) { assertNotNull(loader.memoryCache) }
        assertEquals(1, imageLoaderConstructions())
    }

    @Test
    fun cacheReset_should_EmptyAndRecreateSharedCache() {
        val before = sharedImageMemoryCache(context)
        resetSharedImageMemoryCacheForTest()
        assertNull(sharedImageMemoryCacheSizeBytes())
        val after = sharedImageMemoryCache(context)
        assertEquals(0L, after.size)
        assertNotSame(before, after)
    }
}
