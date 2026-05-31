// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.util

/**
 * Round this [Double] to [decimals] decimal places using standard half-up rounding.
 *
 * KMP-safe: uses [kotlin.math.round] (no JVM-specific [java.math.BigDecimal]).
 *
 * Example: `3.14159.roundTo(2)` → `3.14`
 */
internal fun Double.roundTo(decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    return kotlin.math.round(this * factor) / factor
}
