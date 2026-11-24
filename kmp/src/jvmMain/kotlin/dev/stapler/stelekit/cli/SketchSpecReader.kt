// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "ComplexCondition")

package dev.stapler.stelekit.cli

import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.parseLength
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.hypot
import kotlin.math.round

/** `plan` = real-world sheet (numbers are inches); `diagram` = abstract sheet (numbers are scene units). */
internal enum class SpecMode(val wire: String) {
    Plan("plan"),
    Diagram("diagram"),
    ;

    companion object {
        fun of(wire: String?): SpecMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** Collected problems of one spec; every message starts with the JSON path it is about. */
internal class SpecIssues {
    val errors = ArrayList<String>()
    val warnings = ArrayList<String>()
    val notes = ArrayList<String>()

    fun error(path: String, message: String) {
        errors += "$path: $message"
    }

    fun warn(path: String, message: String) {
        warnings += "$path: $message"
    }
}

/** Scene units are 1/16 inch; helpers shared by the CLI commands. */
internal object SketchLengths {
    const val UNITS_PER_INCH = 16.0
    const val INCHES_PER_FOOT = 12.0
    val UUID_SHAPE = Regex("[A-Za-z0-9-]{8,64}")

    /** Exact inches of [text] (`12' 6"`, `3 1/2"`, `144`), or null when it is not a length. */
    fun parseInches(text: String): Double? {
        val trimmed = text.trim()
        trimmed.toDoubleOrNull()?.let { return it.takeIf(Double::isFinite) }
        val parsed = parseLength(trimmed).getOrNull() as? ParsedLength.Measured ?: return null
        return parsed.value.num.toDouble() / parsed.value.den.toDouble()
    }

    /** Scene units as feet-inches, rounded to a sixteenth (`14' 6 1/2"`). */
    fun feetInches(units: Double): String =
        LengthFormatter.format(Inches(round(units).toLong()), LengthFormat.FEET_INCHES)

    /** Four-decimal rounding so canonical output never carries float noise. */
    fun tidy(v: Double): Double = round(v * 10_000.0) / 10_000.0
}

/**
 * Turns the spec's loose values (`"12' 6\""`, `150`, `[x, y]`) into scene units, honouring the sheet mode and the photo
 * calibration. Errors go to [issues] with their path and the reader returns null, so one pass reports every bad value.
 */
internal class SpecReader(
    private val issues: SpecIssues,
    val mode: SpecMode,
    private val inchesPerPixel: Double?,
    private val snapUnits: Double?,
) {
    private val plan: Boolean get() = mode == SpecMode.Plan

    fun length(el: JsonElement?, path: String): Double? {
        val p = el as? JsonPrimitive
        if (p == null || p is JsonNull) {
            issues.error(path, "expected a length (a number or text like \"12' 6\\\"\"), got ${describeJson(el)}")
            return null
        }
        if (p.isString) {
            val inches = SketchLengths.parseInches(p.content)
            if (inches == null) {
                issues.error(path, "\"${p.content}\" is not a length; write it like 12', 12' 6\", 3 1/2\" or a plain number")
                return null
            }
            val bare = p.content.trim().toDoubleOrNull() != null
            return if (bare) numberUnits(inches) else inches * SketchLengths.UNITS_PER_INCH
        }
        val n = p.content.toDoubleOrNull()
        if (n == null || !n.isFinite()) {
            issues.error(path, "expected a finite number, got ${p.content}")
            return null
        }
        return numberUnits(n)
    }

    /** A bare number: pixels (photo space, snapped), inches (plan) or scene units (diagram). */
    private fun numberUnits(n: Double): Double = when {
        inchesPerPixel != null -> snap(n * inchesPerPixel * SketchLengths.UNITS_PER_INCH)
        plan -> n * SketchLengths.UNITS_PER_INCH
        else -> n
    }

    private fun snap(units: Double): Double = snapUnits?.takeIf { it > 0.0 }?.let { round(units / it) * it } ?: units

    fun lengthOr(obj: JsonObject, key: String, path: String, default: Double): Double =
        if (obj[key] == null) default else length(obj[key], "$path.$key") ?: default

    fun point(el: JsonElement?, path: String): Pair<Double, Double>? {
        val arr = el as? JsonArray
        if (arr == null || arr.size != 2) {
            issues.error(path, "expected a point [x, y], got ${describeJson(el)}")
            return null
        }
        val x = length(arr[0], "$path[0]")
        val y = length(arr[1], "$path[1]")
        return if (x != null && y != null) x to y else null
    }

    fun points(el: JsonElement?, path: String, min: Int): List<Pair<Double, Double>>? {
        val arr = el as? JsonArray
        if (arr == null || arr.size < min) {
            issues.error(path, "expected a list of at least $min points [[x, y], ...], got ${describeJson(el)}")
            return null
        }
        val pts = arr.mapIndexedNotNull { i, p -> point(p, "$path[$i]") }
        return pts.takeIf { it.size == arr.size }
    }

    companion object {
        fun describeJson(el: JsonElement?): String = when (el) {
            null -> "nothing (the key is missing)"
            is JsonNull -> "null"
            is JsonPrimitive -> if (el.isString) "text \"${el.content}\"" else "the value ${el.content}"
            is JsonArray -> "a list"
            is JsonObject -> "an object"
        }

        /** Photo calibration: inches per pixel from reference lengths and the largest disagreement between them. */
        fun calibrate(calibration: JsonObject?, issues: SpecIssues): Pair<Double, Double>? {
            val refs = calibration?.get("refs") as? JsonArray
            if (refs == null || refs.isEmpty()) {
                issues.error("calibration.refs", "photo space needs at least one reference: {\"a\": [px, py], \"b\": [px, py], \"length\": \"3'\"}")
                return null
            }
            val scales = refs.mapIndexedNotNull { i, r -> refScale(r as? JsonObject, "calibration.refs[$i]", issues) }
            if (scales.size != refs.size) return null
            val mean = scales.average()
            val spread = (scales.max() - scales.min()) / mean
            return mean to spread
        }

        private fun refScale(ref: JsonObject?, path: String, issues: SpecIssues): Double? {
            if (ref == null) {
                issues.error(path, "expected an object with a, b and length")
                return null
            }
            val a = pixelPoint(ref["a"], "$path.a", issues)
            val b = pixelPoint(ref["b"], "$path.b", issues)
            val length = (ref["length"] as? JsonPrimitive)?.let { if (it.isString) SketchLengths.parseInches(it.content) else it.content.toDoubleOrNull() }?.takeIf { it.isFinite() }
            if (length == null || length <= 0.0) {
                issues.error("$path.length", "expected the real length between a and b, like \"3'\" or 36 (inches)")
                return null
            }
            if (a == null || b == null) return null
            val pixels = hypot(b.first - a.first, b.second - a.second)
            if (pixels <= 0.0) {
                issues.error(path, "a and b are the same pixel; pick two distinct points on the reference object")
                return null
            }
            return length / pixels
        }

        private fun pixelPoint(el: JsonElement?, path: String, issues: SpecIssues): Pair<Double, Double>? {
            val arr = el as? JsonArray
            val x = (arr?.getOrNull(0) as? JsonPrimitive)?.content?.toDoubleOrNull()
            val y = (arr?.getOrNull(1) as? JsonPrimitive)?.content?.toDoubleOrNull()
            if (arr == null || arr.size != 2 || x == null || y == null) {
                issues.error(path, "expected photo pixel coordinates [px, py], got ${describeJson(el)}")
                return null
            }
            return x to y
        }
    }
}
