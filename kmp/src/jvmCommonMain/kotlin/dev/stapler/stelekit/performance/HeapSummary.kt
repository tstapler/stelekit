package dev.stapler.stelekit.performance

actual fun heapSummary(): String {
    val rt = Runtime.getRuntime()
    val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
    val maxMb = rt.maxMemory() / 1_048_576L
    return "heap:${usedMb}/${maxMb}MB"
}
