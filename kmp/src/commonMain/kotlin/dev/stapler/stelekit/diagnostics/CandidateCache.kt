// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.diagnostics

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cache for wiki subdirectory candidates to optimize scanning performance.
 *
 * Implements a simple TTL-based cache that stores the results of directory scans
 * for a specified duration. The cache helps reduce redundant scanning operations
 * during warm reconciliation and improves overall performance when checking for
 * wiki content locations.
 *
 * The cache respects the performance constraint of 50-500ms per directory listing
 * by limiting scan frequency based on configured thresholds.
 *
 * @param ttl Cache time-to-live as a [Duration]
 * @param pageThreshold Page count threshold for triggering scans on SAF platforms
 * @param localThreshold Page count threshold for triggering scans on local platforms
 * @param maxTotalScans Maximum total scans before cache is invalidated
 */
class CandidateCache(
    private val ttl: Duration = 60.seconds,
    private val pageThreshold: Int = 50,
    private val localThreshold: Int = 1000,
    private val maxTotalScans: Int = 100,
) {
    private var cachedResult: Pair<Long, List<DirectoryScanResult>>? = null
    private var cachedTimestamp: Long = 0L
    private var totalScans: Int = 0

    /**
     * Checks if a scan should be performed based on cache state and thresholds.
     */
    fun shouldScan(
        currentPageCount: Int,
        isSafPlatform: Boolean,
        currentTime: Long,
    ): Boolean {
        // Check if cache is expired
        if (cachedTimestamp > 0L) {
            if ((currentTime - cachedTimestamp) < ttl.inWholeMilliseconds) {
                return false
            } else {
                clear()
            }
        }

        // Check if maximum scans reached
        if (totalScans >= maxTotalScans) {
            return false
        }

        // Check platform-specific thresholds
        val threshold = if (isSafPlatform) pageThreshold else localThreshold
        return currentPageCount < threshold
    }

    /**
     * Stores scan results in the cache.
     */
    fun cacheResult(
        candidates: List<DirectoryScanResult>,
        currentTime: Long,
    ) {
        cachedResult = currentTime to candidates
        cachedTimestamp = currentTime
        totalScans++
    }

    /**
     * Retrieves cached scan results if available and not expired.
     */
    fun getCached(
        currentTime: Long,
    ): List<DirectoryScanResult>? {
        if (cachedTimestamp == 0L) {
            return null
        }

        // Check if cache is expired
        if ((currentTime - cachedTimestamp) >= ttl.inWholeMilliseconds) {
            clear()
            return null
        }

        return cachedResult?.second
    }

    /**
     * Clears the cache, forcing the next scan to re-run.
     */
    fun clear() {
        cachedResult = null
        cachedTimestamp = 0L
        totalScans = 0
    }

    /**
     * Gets the total number of scans performed.
     */
    fun getTotalScans(): Int = totalScans
}
