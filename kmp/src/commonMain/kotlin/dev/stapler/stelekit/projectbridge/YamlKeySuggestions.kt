// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.flatMap
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Locator
import dev.stapler.stelekit.fusion.Resolution
import dev.stapler.stelekit.sketchimport.Tagging
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.parseCount
import dev.stapler.stelekit.units.parseLength

/** A numeric YAML path no registry key reads yet, with a ready-made key for it. */
data class KeySuggestion(val path: String, val key: String, val kind: DimensionKind)

/**
 * Registry key suggestions from a YAML snapshot (Story 5.9e). Paths follow the extractor's rule: keys joined by `.`, a list item
 * named by its `short` entry when it has one, else by its index. Only paths that hold a number and match no alias are offered.
 */
object YamlKeySuggestions {
    const val MAX_SUGGESTIONS = 100
    private const val SHORT_KEY = "short"
    private val unsafe = Regex("[^a-z0-9._-]")

    fun suggest(yamlText: String?, registry: DimensionRegistry): List<KeySuggestion> {
        val root = (yamlText?.let(MiniYaml::parse) as? MiniYamlResult.Parsed)?.root ?: return emptyList()
        val taken = registry.dimensions.mapTo(HashSet()) { it.key }
        val out = ArrayList<KeySuggestion>()
        for ((path, scalar) in scalars(root, "")) {
            if (out.size >= MAX_SUGGESTIONS) break
            val kind = numericKind(scalar) ?: continue
            if (registry.resolve(Locator.Yaml(path)) is Resolution.Resolved) continue
            val key = freshKey(path, taken)
            taken += key
            out += KeySuggestion(path, key, kind)
        }
        return out
    }

    /** Adds the suggested key with the path as its yaml alias. */
    fun accept(registry: DimensionRegistry, suggestion: KeySuggestion): Either<EditError, DimensionRegistry> =
        RegistryEditing.addKey(registry, suggestion.key, suggestion.kind, suggestion.key)
            .flatMap { RegistryEditing.addAlias(it, suggestion.key, AliasKind.YAML, suggestion.path) }

    private fun scalars(node: YamlNode, path: String): List<Pair<String, YamlScalar>> = when (node) {
        is YamlMap -> node.entries.flatMap { (key, child) -> scalars(child, join(path, key)) }
        is YamlList -> node.items.flatMapIndexed { index, child -> scalars(child, join(path, shortKey(child) ?: index.toString())) }
        is YamlScalar -> if (path.isEmpty()) emptyList() else listOf(path to node)
    }

    private fun join(path: String, key: String) = if (path.isEmpty()) key else "$path.$key"

    private fun shortKey(item: YamlNode): String? =
        ((item as? YamlMap)?.entries?.firstOrNull { it.first == SHORT_KEY }?.second as? YamlScalar)?.text?.trim()?.takeIf { it.isNotEmpty() }

    private fun numericKind(scalar: YamlScalar): DimensionKind? {
        if (scalar.kind == ScalarKind.NULL || scalar.kind == ScalarKind.BOOL) return null
        val parsed = parseLength(scalar.text).getOrNull()
        return when {
            parsed is ParsedLength.Measured || parsed is ParsedLength.Consistency -> DimensionKind.LENGTH
            parseCount(scalar.text).getOrNull() != null -> DimensionKind.COUNT
            else -> null
        }
    }

    private fun freshKey(path: String, taken: Set<String>): String {
        val base = path.lowercase().replace(unsafe, "-").trimStart('.', '-', '_').take(Tagging.MAX_KEY_LENGTH - SUFFIX_ROOM).ifEmpty { "dimension" }
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
    }

    private const val SUFFIX_ROOM = 8
}
