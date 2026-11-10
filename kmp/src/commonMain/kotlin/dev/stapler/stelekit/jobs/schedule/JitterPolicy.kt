// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import kotlin.random.Random

/** Stable per-key seed: FNV-1a of `kind:scope`, identical across processes. */
class JitterSeed private constructor(val value: Long) {
    override fun equals(other: Any?): Boolean = other is JitterSeed && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "JitterSeed($value)"

    companion object {
        /** `JitterSeed("git-sync:graph-a")`: hashes [text] with the same FNV-1a as [stableHex]. */
        operator fun invoke(text: String): JitterSeed = JitterSeed(stableHex(text).toULong(16).toLong())

        fun of(key: TaskKey): JitterSeed = JitterSeed("${key.kind.id}:${key.scope.value}")

        /** Wraps an already-hashed value (tests, callers with their own stable hash). */
        fun ofHash(value: Long): JitterSeed = JitterSeed(value)
    }
}

/**
 * What the platform actually gets and the UI shows. [flexMinutes] is null when [jitterMinutes] is 0
 * (today's request, no flex window); [offsetMinutes] is the deterministic first-run delay in
 * `0..jitterMinutes`; [clamped] is true when interval or jitter differ from what was asked for.
 */
data class EffectiveSchedule(
    val intervalMinutes: Int,
    val jitterMinutes: Int,
    val flexMinutes: Int?,
    val offsetMinutes: Int,
    val clamped: Boolean,
)

/** Pure jitter math; randomness is always injected or seeded, never `Random.Default`. */
object JitterPolicy {
    /** WorkManager's `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` (15 min). */
    const val DEFAULT_MIN_INTERVAL_MINUTES = 15

    /** WorkManager's `PeriodicWorkRequest.MIN_PERIODIC_FLEX_MILLIS` (5 min). */
    const val DEFAULT_MIN_FLEX_MINUTES = 5

    private const val MS_PER_MINUTE = 60_000L

    /** Deterministic offset in `0..jitterMinutes` for [seed]; 0 when [jitterMinutes] <= 0. */
    fun offsetMinutes(seed: JitterSeed, jitterMinutes: Int): Int =
        if (jitterMinutes <= 0) 0 else Random(seed.value).nextInt(jitterMinutes + 1)

    /**
     * Flex window for a periodic request, or null for zero jitter. Satisfies
     * `minFlex <= flex < interval` when the caller's jitter respects `interval - minFlex`
     * (as [clamp] guarantees) and `interval > minFlex`.
     */
    fun flexMinutes(
        intervalMinutes: Int,
        jitterMinutes: Int,
        minFlexMinutes: Int = DEFAULT_MIN_FLEX_MINUTES,
    ): Int? =
        if (jitterMinutes <= 0) null else maxOf(jitterMinutes, minFlexMinutes).coerceAtMost(maxOf(1, intervalMinutes - 1))

    /** In-process delay: uniform in `[interval, interval + jitter]` minutes (negatives floor at 0), in ms. */
    fun nextDelayMs(intervalMinutes: Int, jitterMinutes: Int, random: Random): Long {
        val intervalMs = maxOf(0, intervalMinutes) * MS_PER_MINUTE
        val jitterMs = maxOf(0, jitterMinutes) * MS_PER_MINUTE
        return intervalMs + random.nextLong(jitterMs + 1)
    }
}

/**
 * Applies [capabilities]: interval floors at `minIntervalMinutes`, jitter is limited to
 * `interval - minFlexMinutes` (and zeroed when jitter is unsupported). A null [seed] gives offset 0.
 * The result is never silent: [EffectiveSchedule.clamped] reports any change.
 */
fun Schedule.clamp(capabilities: SchedulerCapabilities, seed: JitterSeed? = null): EffectiveSchedule {
    val interval = maxOf(intervalMinutes, capabilities.minIntervalMinutes)
    val jitterLimit = if (capabilities.supportsJitter) maxOf(0, interval - capabilities.minFlexMinutes) else 0
    val jitter = minOf(jitterMinutes, jitterLimit)
    return EffectiveSchedule(
        intervalMinutes = interval,
        jitterMinutes = jitter,
        flexMinutes = JitterPolicy.flexMinutes(interval, jitter, capabilities.minFlexMinutes),
        offsetMinutes = if (seed == null) 0 else JitterPolicy.offsetMinutes(seed, jitter),
        clamped = interval != intervalMinutes || jitter != jitterMinutes,
    )
}
