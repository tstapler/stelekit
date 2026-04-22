// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.cache

/**
 * Returns the total byte budget available for in-process caches on this platform.
 *
 * Sized at 10% of the JVM max heap (capped at 32 MB) on JVM/Android — half of the
 * 20% that Coil and Glide use for image caches, adjusted down for text/metadata entries
 * which are orders of magnitude smaller per entry. Fixed conservative values are used
 * on platforms where the runtime heap size is not queryable from commonMain.
 *
 * Callers should split this budget across caches according to their relative hotness.
 * The default split used by the repositories is 60% blocks / 20% pages-by-uuid /
 * 20% pages-by-name.
 */
expect fun platformCacheBytes(): Long

/** Pre-computed cache weights for the three repository caches. */
data class RepoCacheConfig(
    val blockCacheBytes: Long,
    val pageByUuidCacheBytes: Long,
    val pageByNameCacheBytes: Long,
) {
    companion object {
        fun fromPlatform(): RepoCacheConfig {
            val total = platformCacheBytes()
            return RepoCacheConfig(
                blockCacheBytes     = (total * 0.60).toLong(),
                pageByUuidCacheBytes = (total * 0.20).toLong(),
                pageByNameCacheBytes = (total * 0.20).toLong(),
            )
        }
    }
}
