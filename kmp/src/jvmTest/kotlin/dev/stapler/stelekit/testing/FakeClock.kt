package dev.stapler.stelekit.testing

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class FakeClock(private var instant: Instant) : Clock {
    override fun now(): Instant = instant
    fun advance(duration: Duration) { instant += duration }
}
