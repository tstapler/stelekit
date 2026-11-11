// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Formats run times in [locale]'s clock style and month names ("9:15 PM" in en-US, "21:15" in de-DE). */
class LocaleRunTimeFormatter(private val locale: Locale = Locale.getDefault()) : RunTimeFormatter {
    private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val shortDate = DateTimeFormatter.ofPattern("MMM d", locale)

    override fun timeOfDay(epochMs: Long, zone: TimeZone): String = clock.format(at(epochMs, zone))

    override fun dateAndTime(epochMs: Long, zone: TimeZone): String =
        "${shortDate.format(at(epochMs, zone))}, ${timeOfDay(epochMs, zone)}"

    private fun at(epochMs: Long, zone: TimeZone) = Instant.ofEpochMilli(epochMs).atZone(zone.toJavaZoneId())
}

actual fun platformRunTimeFormatter(): RunTimeFormatter = LocaleRunTimeFormatter()
