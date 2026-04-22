package dev.stapler.stelekit.coroutines

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Cross-platform coroutine dispatcher provider.
 * Use this instead of hardcoding Dispatchers.IO which is JVM-only.
 */
expect object PlatformDispatcher {
    val IO: CoroutineDispatcher
    val Main: CoroutineDispatcher
    val Default: CoroutineDispatcher
    /** Limited-parallelism dispatcher for SQLite operations. Caps JDBC connections on JVM. */
    val DB: CoroutineDispatcher
}
