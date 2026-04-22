package dev.stapler.stelekit.cache

// WASM/JS: fixed 4 MB — single-threaded runtime with no reliable heap-size API.
actual fun platformCacheBytes(): Long = 4_000_000L
