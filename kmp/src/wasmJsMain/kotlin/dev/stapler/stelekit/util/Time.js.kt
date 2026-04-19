package dev.stapler.stelekit.util

// performance.now() is monotonic and has sub-ms precision; Date.now() is not monotonic
@JsFun("() => performance.now()")
private external fun jsPerformanceNow(): Double

actual object PlatformTime {
    actual fun now(): Long = (jsPerformanceNow() * 1_000_000).toLong()
    actual fun currentThreadName(): String = "WasmJs-Main"
}
