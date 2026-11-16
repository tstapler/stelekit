// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.codec

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral

/** Why a value cannot be written or read as canonical JSON. */
sealed interface CanonicalJsonError {
    /** NaN or an infinity; JSON has no representation and the scene model rejects it (ADR-002). */
    data class NonFinite(val text: String) : CanonicalJsonError
    data class TooDeep(val depth: Int) : CanonicalJsonError
    data class Malformed(val message: String) : CanonicalJsonError
}

/**
 * The one JSON text format of the v2 sheet (Story 1.1.2, ADR-001 item 5, ADR-002): compact (no whitespace), keys sorted
 * by UTF-16 code unit, numbers in the ECMAScript `Number::toString` layout over the shortest round-trip digits
 * ([ShortestDouble]), so equal data gives equal bytes on every target and git diffs stay stable.
 *
 * Doubles must enter a tree through [number], never `JsonPrimitive(Double)`: that would capture the platform's own
 * `toString`, which on wasmJs is not shortest round-trip (Spike 0.1.3a).
 */
object CanonicalJson {
    private const val HEX = 16
    private const val FIRST_PRINTABLE = 0x20
    private const val PLAIN_EXP_LAST = 21
    private const val SMALL_EXP_FIRST = -5
    private const val TRIVIAL_INT_DIGITS = 15

    /** A canonical number primitive for [d], or [CanonicalJsonError.NonFinite]. */
    @OptIn(ExperimentalSerializationApi::class)
    fun number(d: Double): Either<CanonicalJsonError, JsonPrimitive> = formatDouble(d).map { JsonUnquotedLiteral(it) }

    /** ECMAScript `Number::toString` of [d] over shortest round-trip digits; `-0.0` is `0`. */
    fun formatDouble(d: Double): Either<CanonicalJsonError, String> {
        if (!d.isFinite()) return CanonicalJsonError.NonFinite(d.toString()).left()
        if (d == 0.0) return "0".right()
        val neg = d < 0
        val digits = ShortestDouble.digitsOf(if (neg) -d else d)
        val body = layout(digits.digits, digits.pointPos)
        return (if (neg) "-$body" else body).right()
    }

    /**
     * Parses a JSON number text into the nearest double (correctly rounded, identical on every target); null when it is
     * not a finite number. The inverse of [formatDouble]. Use this, never `String.toDouble` or `JsonPrimitive.double`,
     * on a decoded literal: wasmJs does not round-trip shortest digits through them.
     */
    fun parseDouble(text: String): Double? = DecimalParser.parse(text)

    /** The double of a decoded number primitive, or null for a string, boolean, null or non-finite literal. */
    fun doubleOf(p: JsonPrimitive): Double? = if (p.isString || p is JsonNull) null else DecimalParser.parse(p.content)

    /**
     * Encodes [element]. [headerFirstKeys] names top-level keys written first, in that order (the v2 sheet writes
     * `schema` then `sheetUuid`, so the 64-byte header probe sees the schema); every other key and every nested
     * object is sorted.
     */
    fun encode(
        element: JsonElement,
        headerFirstKeys: List<String> = emptyList(),
        limits: SceneLimits = SceneLimits.Default,
    ): Either<CanonicalJsonError, ByteArray> {
        val sb = StringBuilder()
        return write(element, headerFirstKeys, 1, limits.maxJsonDepth, sb).map { sb.toString().encodeToByteArray() }
    }

    /** Strict UTF-8, depth-checked before parsing so hostile nesting cannot exhaust the stack. */
    fun decode(bytes: ByteArray, limits: SceneLimits = SceneLimits.Default): Either<CanonicalJsonError, JsonElement> {
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) { // JVM throws a CharacterCodingException, other targets an IllegalArgumentException
            return CanonicalJsonError.Malformed(e.message ?: "invalid UTF-8").left()
        }
        val depth = nestingDepth(text)
        if (depth > limits.maxJsonDepth) return CanonicalJsonError.TooDeep(depth).left()
        return try {
            Json.parseToJsonElement(text).right()
        } catch (e: Exception) {
            CanonicalJsonError.Malformed(e.message ?: "invalid JSON").left()
        }
    }

    private fun layout(digits: String, n: Int): String {
        val k = digits.length
        return when {
            n in k..PLAIN_EXP_LAST -> digits + "0".repeat(n - k)
            n in 1..PLAIN_EXP_LAST -> digits.substring(0, n) + "." + digits.substring(n)
            n in SMALL_EXP_FIRST..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                mantissa + "e" + (if (e < 0) "-" else "+") + kotlin.math.abs(e)
            }
        }
    }

    private fun write(
        el: JsonElement,
        header: List<String>,
        depth: Int,
        depthLimit: Int,
        sb: StringBuilder,
    ): Either<CanonicalJsonError, Unit> {
        when (el) {
            is JsonNull -> sb.append("null")
            is JsonPrimitive -> return writePrimitive(el, sb)
            is JsonArray -> {
                if (depth > depthLimit) return CanonicalJsonError.TooDeep(depth).left()
                sb.append('[')
                el.forEachIndexed { i, child ->
                    if (i > 0) sb.append(',')
                    write(child, emptyList(), depth + 1, depthLimit, sb).onLeft { return it.left() }
                }
                sb.append(']')
            }
            is JsonObject -> {
                if (depth > depthLimit) return CanonicalJsonError.TooDeep(depth).left()
                val first = header.filter { it in el }
                val keys = first + el.keys.filter { it !in first }.sorted()
                sb.append('{')
                keys.forEachIndexed { i, key ->
                    if (i > 0) sb.append(',')
                    writeString(key, sb)
                    sb.append(':')
                    write(el.getValue(key), emptyList(), depth + 1, depthLimit, sb).onLeft { return it.left() }
                }
                sb.append('}')
            }
        }
        return Unit.right()
    }

    private fun writePrimitive(p: JsonPrimitive, sb: StringBuilder): Either<CanonicalJsonError, Unit> {
        if (p.isString) { writeString(p.content, sb); return Unit.right() }
        val c = p.content
        when {
            c == "true" || c == "false" -> sb.append(c)
            isPlainInteger(c) -> sb.append(normalizeInteger(c))
            else -> {
                if (c == "NaN" || c == "Infinity" || c == "-Infinity") return CanonicalJsonError.NonFinite(c).left()
                val d = DecimalParser.parse(c) ?: return CanonicalJsonError.Malformed("not a finite number: $c").left()
                val formatted = formatDouble(d)
                if (formatted is Either.Left) return formatted
                sb.append((formatted as Either.Right).value)
            }
        }
        return Unit.right()
    }

    private fun isPlainInteger(c: String): Boolean {
        val body = c.removePrefix("-")
        return body.isNotEmpty() && body.length <= TRIVIAL_INT_DIGITS && body.all { it in '0'..'9' }
    }

    /** Short integer literals are kept exact (`-0` becomes `0`, a leading `+` or zeros cannot occur in valid input). */
    private fun normalizeInteger(c: String): String = if (c.trimStart('-').all { it == '0' }) "0" else c

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch.code < FIRST_PRINTABLE -> appendEscape(ch, sb)
                ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> { sb.append(ch).append(s[i + 1]); i++ }
                ch.isSurrogate() -> appendEscape(ch, sb)
                else -> sb.append(ch)
            }
            i++
        }
        sb.append('"')
    }

    private fun appendEscape(ch: Char, sb: StringBuilder) {
        sb.append("\\u").append(ch.code.toString(HEX).padStart(4, '0'))
    }

    private fun nestingDepth(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '[', '{' -> { depth++; if (depth > deepest) deepest = depth }
                ']', '}' -> depth--
            }
        }
        return deepest
    }
}
