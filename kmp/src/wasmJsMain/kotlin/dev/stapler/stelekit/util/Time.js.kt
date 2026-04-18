package dev.stapler.stelekit.util

@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

actual object PlatformTime {
    actual fun now(): Long = (jsDateNow() * 1_000_000).toLong()
    actual fun currentThreadName(): String = "WasmJs-Main"
}
