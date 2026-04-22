package dev.stapler.stelekit.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * iOS implementation of PlatformDispatcher.
 * Uses Grand Central Dispatch for native concurrency.
 */
actual object PlatformDispatcher {
    actual val IO: CoroutineDispatcher = Dispatchers.Default
    actual val Main: CoroutineDispatcher = Dispatchers.Main
    actual val Default: CoroutineDispatcher = Dispatchers.Default
    actual val DB: CoroutineDispatcher = Dispatchers.Default
}
