package dev.stapler.stelekit.cache

// iOS: fixed 8 MB — no reliable commonMain API to query available heap.
// Sized conservatively for typical device RAM (2–8 GB), focused on foreground use.
actual fun platformCacheBytes(): Long = 8_000_000L
