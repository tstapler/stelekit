package dev.stapler.stelekit.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual object PlatformDispatcher {
    actual val IO: CoroutineDispatcher = Dispatchers.IO
    actual val Main: CoroutineDispatcher = Dispatchers.Main
    actual val Default: CoroutineDispatcher = Dispatchers.Default
    // PooledJdbcSqliteDriver pre-creates 8 connections at startup and reuses them.
    // No parallelism cap is needed — the pool bounds connection count; unlimited read
    // concurrency is permitted because SQLite WAL allows many simultaneous readers.
    actual val DB: CoroutineDispatcher = Dispatchers.IO
}
