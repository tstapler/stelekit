// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.units.Rational

/** A label the binder could not place; the user picks or creates its dimension key. */
data class TaggingItem(
    val ref: String,
    val text: String,
    val value: Rational,
    val precision: Rational,
    val candidates: List<String>,
)

sealed interface TagChoice {
    data class Existing(val key: String) : TagChoice

    data class New(val key: String, val label: String) : TagChoice

    data object Skip : TagChoice
}

sealed interface KeyError {
    data object Blank : KeyError

    data object BadFormat : KeyError

    data object TooLong : KeyError

    data class Duplicate(val key: String) : KeyError

    data class Unknown(val key: String) : KeyError
}

/** The registry after any new keys, and the ref-to-key assignments the user confirmed. */
data class TaggingOutcome(val registry: DimensionRegistry, val assignments: Map<String, String>)

/** Pure tagging rules (Story 4.4): no UI, no I/O. */
object Tagging {
    const val MAX_KEY_LENGTH = 64
    private val KEY_FORMAT = Regex("[a-z0-9][a-z0-9._-]*")

    fun items(result: LabelBindingResult): List<TaggingItem> =
        result.unbound.map { TaggingItem(it.ref, it.text, it.value, it.precision, it.candidates) }

    /** Null when [key] is a usable new registry key. */
    fun validateNewKey(registry: DimensionRegistry, key: String): KeyError? {
        val trimmed = key.trim()
        return when {
            trimmed.isEmpty() -> KeyError.Blank
            trimmed.length > MAX_KEY_LENGTH -> KeyError.TooLong
            !KEY_FORMAT.matches(trimmed) -> KeyError.BadFormat
            registry.entry(trimmed) != null -> KeyError.Duplicate(trimmed)
            else -> null
        }
    }

    /**
     * Applies [choices] (keyed by label ref). A [TagChoice.New] key is added to the registry as a
     * length dimension; [TagChoice.Skip] and refs without a choice are left untagged. The first
     * invalid choice is returned as the left and nothing is applied.
     */
    fun apply(registry: DimensionRegistry, choices: Map<String, TagChoice>): Either<KeyError, TaggingOutcome> {
        var current = registry
        val assignments = LinkedHashMap<String, String>()
        for ((ref, choice) in choices) {
            when (choice) {
                TagChoice.Skip -> Unit
                is TagChoice.Existing -> {
                    if (current.entry(choice.key) == null) return KeyError.Unknown(choice.key).left()
                    assignments[ref] = choice.key
                }
                is TagChoice.New -> {
                    val key = choice.key.trim()
                    val firstUse = assignments.containsValue(key) && current.entry(key) != null
                    if (!firstUse) validateNewKey(current, key)?.let { return it.left() }
                    if (current.entry(key) == null) {
                        val label = choice.label.trim().ifEmpty { key }
                        current = current.copy(dimensions = current.dimensions + DimensionEntry(key, DimensionKind.LENGTH, label))
                    }
                    assignments[ref] = key
                }
            }
        }
        return TaggingOutcome(current, assignments).right()
    }
}
