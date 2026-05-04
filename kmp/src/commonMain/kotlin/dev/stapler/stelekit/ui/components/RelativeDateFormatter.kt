package dev.stapler.stelekit.ui.components

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Formats an [Instant] as a human-readable relative date string.
 *
 * - Same day    → "today"
 * - Yesterday   → "yesterday"
 * - 2–6 days    → "N days ago"
 * - 7–30 days   → "MMM d"  (e.g. "Apr 12")
 * - > 30 days   → "MMM yyyy" (e.g. "Jan 2024")
 * - Future      → "today" (clock drift guard)
 *
 * The [now] parameter is injectable for testing.
 */
fun formatRelativeDate(instant: Instant, now: Instant = Clock.System.now()): String {
    val diff = now - instant
    val days = diff.inWholeDays
    return when {
        diff.isNegative() || days == 0L -> "today"
        days == 1L -> "yesterday"
        days in 2..6 -> "$days days ago"
        days in 7..30 -> {
            val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            "${localDate.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${localDate.day}"
        }
        else -> {
            val localDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
            "${localDate.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} ${localDate.year}"
        }
    }
}
