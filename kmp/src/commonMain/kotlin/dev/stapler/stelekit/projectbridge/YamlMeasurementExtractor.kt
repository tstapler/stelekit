// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.getOrElse
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Locator
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.Resolution
import dev.stapler.stelekit.fusion.Source
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.units.LengthContext
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.parseCount
import dev.stapler.stelekit.units.parseLength

/** What one YAML snapshot yielded; [unsupportedLine] is set when the subset parser refused it. */
data class YamlExtractionResult(
    val observations: List<Observation> = emptyList(),
    val unsupportedLine: Int? = null,
)

/**
 * Reads observations from a YAML snapshot (Story 5.3). A scalar's path is its keys joined by `.`,
 * with list items as their `short` key when they have one (`zones.left-long.width`) or else their index (`zones.0.width`); the registry's yaml aliases map paths onto
 * dimensions and unmapped paths are ignored. A trailing comment restating the value is part of the
 * same scalar, so it shares the scalar's single ref and never yields a second observation.
 */
class YamlMeasurementExtractor(
    private val registry: DimensionRegistry,
    private val refTable: RefTable,
    private val lengthContext: LengthContext = LengthContext(),
) {
    /** [locator] is the device-local snapshot locator, kept only in the [RefTable]. */
    fun extract(yaml: String, locator: String): YamlExtractionResult =
        when (val parsed = MiniYaml.parse(yaml)) {
            is MiniYamlResult.Parsed -> {
                val found = ArrayList<Observation>()
                walk(parsed.root, "", "", locator, found)
                YamlExtractionResult(found)
            }
            is MiniYamlResult.Unsupported -> YamlExtractionResult(unsupportedLine = parsed.line)
            is MiniYamlResult.Malformed -> YamlExtractionResult(unsupportedLine = parsed.line)
        }

    /** [path] names list items by their `short` key when they have one; [indexPath] always uses positions. */
    private fun walk(node: YamlNode, path: String, indexPath: String, locator: String, out: MutableList<Observation>) {
        when (node) {
            is YamlMap -> node.entries.forEach { (key, child) -> walk(child, join(path, key), join(indexPath, key), locator, out) }
            is YamlList -> node.items.forEachIndexed { index, child ->
                walk(child, join(path, shortKey(child) ?: index.toString()), join(indexPath, index.toString()), locator, out)
            }
            is YamlScalar -> if (path.isNotEmpty()) (observe(node, path, locator) ?: observe(node, indexPath, locator))?.let(out::add)
        }
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    private fun shortKey(item: YamlNode): String? =
        ((item as? YamlMap)?.entries?.firstOrNull { it.first == SHORT_KEY }?.second as? YamlScalar)?.text?.trim()?.takeIf { it.isNotEmpty() }

    private fun observe(scalar: YamlScalar, path: String, locator: String): Observation? {
        if (scalar.kind == ScalarKind.NULL || scalar.kind == ScalarKind.BOOL) return null
        val resolved = registry.resolve(Locator.Yaml(path)) as? Resolution.Resolved ?: return null
        val entry = registry.entry(resolved.key) ?: return null
        val source = Source(SourceKind.YAML, refTable.refFor(SourceKind.YAML, "$locator|$path"))
        return when (entry.kind) {
            DimensionKind.COUNT -> parseCount(scalar.text).getOrNull()?.let {
                Observation(entry.key, Rational(it), Rational.ZERO, entry.kind, source)
            }
            DimensionKind.LENGTH -> {
                val parsed = parseLength(scalar.text, lengthContext).getOrElse { return null }
                val measured = when (parsed) {
                    is ParsedLength.Measured -> parsed
                    is ParsedLength.Consistency -> parsed.primary
                    else -> return null
                }
                Observation(entry.key, measured.value, measured.roundingPrecision, entry.kind, source, parsed.qualifiers)
            }
        }
    }

    private companion object {
        const val SHORT_KEY = "short"
    }
}
