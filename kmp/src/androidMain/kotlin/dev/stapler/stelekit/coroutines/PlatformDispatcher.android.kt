package dev.stapler.stelekit.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object PlatformDispatcher {
    actual val IO: CoroutineDispatcher = Dispatchers.IO
    actual val Main: CoroutineDispatcher = Dispatchers.Main
    actual val Default: CoroutineDispatcher = Dispatchers.Default
    // Android driver manages its own connection pool natively; no JDBC connection-per-thread issue.
    actual val DB: CoroutineDispatcher = Dispatchers.IO
}
