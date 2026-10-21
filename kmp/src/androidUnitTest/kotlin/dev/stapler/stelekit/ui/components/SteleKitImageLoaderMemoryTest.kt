// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every image block / asset card used to build its own ImageLoader with its own default-sized
 * memory cache, so image memory scaled with the number of loaders instead of being bounded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SteleKitImageLoaderMemoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

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
        val memoryClassBytes = Runtime.getRuntime().maxMemory()
        assertTrue(max in 1 until memoryClassBytes / 4, "cache max=$max heap=$memoryClassBytes")
    }
}
