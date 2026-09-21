package dev.stapler.stelekit.cache

// Public (not internal): lives in the mermaid Bazel module (mermaid_common_srcs)
// but is used by the UI monolith (performance/*) — same visibility widening as
// extractCodeBody for the same module-boundary reason. Behavior-neutral primitive.
expect class PlatformLock() {
    fun lock()
    fun unlock()
}

inline fun <T> PlatformLock.withLock(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
