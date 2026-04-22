package dev.stapler.stelekit.cache

// 10% of max heap, capped at 32 MB.
// Reference: Coil and Glide use 20% of maxMemory() for image caches by default.
// Text/metadata entries are orders of magnitude smaller per entry, so 10% is appropriate.
actual fun platformCacheBytes(): Long {
    val maxMemory = Runtime.getRuntime().maxMemory()
    val tenPercent = maxMemory / 10
    return minOf(tenPercent, 32_000_000L)
}
