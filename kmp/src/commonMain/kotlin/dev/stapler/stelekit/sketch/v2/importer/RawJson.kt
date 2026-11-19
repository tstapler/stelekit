// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.importer

import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

// Typed reads of the raw members the parser kept. Numbers go through `CanonicalJson.doubleOf`, never `String.toDouble`,
// because wasmJs `toDouble` does not round-trip (research/determinism-spike.md).

internal fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.let { CanonicalJson.doubleOf(it) }?.takeIf { it.isFinite() }

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

/** True when the key is present and not JSON null (an explicit null reads as "none", an absent key as "default"). */
internal fun JsonObject.hasValue(key: String): Boolean = this[key].let { it != null && it !is JsonNull }

/** The `[x, y]` pair at [index] of [array], or null when it is not two finite numbers. */
internal fun pairAt(array: JsonArray, index: Int): Pair<Double, Double>? = (array.getOrNull(index) as? JsonArray)?.let { pairOf(it) }

/** [pair] read as two finite numbers, or null. */
internal fun pairOf(pair: JsonArray): Pair<Double, Double>? {
    val x = (pair.getOrNull(0) as? JsonPrimitive)?.let { CanonicalJson.doubleOf(it) }?.takeIf { it.isFinite() } ?: return null
    val y = (pair.getOrNull(1) as? JsonPrimitive)?.let { CanonicalJson.doubleOf(it) }?.takeIf { it.isFinite() } ?: return null
    return x to y
}
