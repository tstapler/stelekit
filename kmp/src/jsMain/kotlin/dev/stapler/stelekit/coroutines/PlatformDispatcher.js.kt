package dev.stapler.stelekit.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * JS implementation of PlatformDispatcher.
 * Note: JS is single-threaded, so IO and Default are equivalent.
 * For true parallelism, you'd need Web Workers.
 */
actual object PlatformDispatcher {
    actual val IO: CoroutineDispatcher = Dispatchers.Default
    actual val Main: CoroutineDispatcher = Dispatchers.Main
    actual val Default: CoroutineDispatcher = Dispatchers.Default
}
