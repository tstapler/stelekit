// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.fusion.Derivation
import dev.stapler.stelekit.fusion.DimensionAliases
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.sketchimport.KeyError
import dev.stapler.stelekit.sketchimport.Tagging

/** Why an editor operation was refused; the registry and spec are left exactly as they were. */
sealed interface EditError {
    data class BadKey(val error: KeyError) : EditError

    data class UnknownKey(val key: String) : EditError

    /** A constraint or a derivation still reads [key]. */
    data class KeyInUse(val key: String) : EditError

    data object BlankAlias : EditError

    data class DuplicateAlias(val owner: String) : EditError

    data object BadLabel : EditError

    data object NeedsParts : EditError

    data object RepeatedKey : EditError

    data object BadOffset : EditError

    data object DuplicateConstraint : EditError

    data object TooMany : EditError

    data object InvalidRegistry : EditError
}

/** The registry and spec together, since renaming or deleting a key touches both. */
data class Edited(val registry: DimensionRegistry, val spec: FusionSpec)

/** The alias lists the editor manages; table aliases take a row pattern and a header, so they stay as imported. */
enum class AliasKind(val title: String) {
    YAML("YAML path"),
    SVG_LABEL("Drawing label"),
    SVG_GEOMETRY("Drawing rule"),
    PROSE("Prose phrase"),
}

/** Pure registry editing (Story 5.9e): no UI, no I/O. Every operation validates its result. */
object RegistryEditing {
    const val MAX_LABEL = 80
    const val MAX_ALIAS = 120
    const val MAX_KEYS = 200

    fun addKey(registry: DimensionRegistry, key: String, kind: DimensionKind, label: String): Either<EditError, DimensionRegistry> {
        val trimmed = key.trim()
        Tagging.validateNewKey(registry, trimmed)?.let { return EditError.BadKey(it).left() }
        if (registry.dimensions.size >= MAX_KEYS) return EditError.TooMany.left()
        val text = label.trim().ifEmpty { trimmed }
        if (text.length > MAX_LABEL) return EditError.BadLabel.left()
        return checked(registry.copy(dimensions = registry.dimensions + DimensionEntry(trimmed, kind, text)))
    }

    fun setKind(registry: DimensionRegistry, key: String, kind: DimensionKind): Either<EditError, DimensionRegistry> =
        update(registry, key) { it.copy(kind = kind) }

    fun setLabel(registry: DimensionRegistry, key: String, label: String): Either<EditError, DimensionRegistry> {
        val text = label.trim().ifEmpty { key }
        if (text.length > MAX_LABEL) return EditError.BadLabel.left()
        return update(registry, key) { it.copy(label = text) }
    }

    /** Renames [old] to [new] everywhere the spec names it; refused while another key's derivation reads it. */
    fun renameKey(edited: Edited, old: String, new: String): Either<EditError, Edited> {
        val (registry, spec) = edited
        if (registry.entry(old) == null) return EditError.UnknownKey(old).left()
        val target = new.trim()
        if (target == old) return edited.right()
        Tagging.validateNewKey(registry, target)?.let { return EditError.BadKey(it).left() }
        if (readsByDerivation(registry, old)) return EditError.KeyInUse(old).left()
        val renamed = registry.copy(dimensions = registry.dimensions.map { if (it.key == old) it.copy(key = target) else it })
        return checked(renamed).map { Edited(it, ConstraintEditing.renameKey(spec, old, target)) }
    }

    /** Refused while a constraint or derivation reads [key]; the zone order simply drops it. */
    fun deleteKey(edited: Edited, key: String): Either<EditError, Edited> {
        val (registry, spec) = edited
        if (registry.entry(key) == null) return EditError.UnknownKey(key).left()
        if (ConstraintEditing.reads(spec, key) || readsByDerivation(registry, key)) return EditError.KeyInUse(key).left()
        val without = registry.copy(dimensions = registry.dimensions.filter { it.key != key })
        return checked(without).map { Edited(it, spec.copy(zoneOrder = spec.zoneOrder.filter { zone -> zone != key })) }
    }

    fun addAlias(registry: DimensionRegistry, key: String, kind: AliasKind, alias: String): Either<EditError, DimensionRegistry> {
        val text = alias.trim()
        if (text.isEmpty() || text.length > MAX_ALIAS) return EditError.BlankAlias.left()
        val owner = registry.dimensions.firstOrNull { entry ->
            entry.key != key && aliasesOf(entry, kind).any { it.trim().equals(text, ignoreCase = true) }
        }
        if (owner != null) return EditError.DuplicateAlias(owner.key).left()
        return update(registry, key) { entry ->
            val current = aliasesOf(entry, kind)
            if (current.any { it.trim().equals(text, ignoreCase = true) }) entry else entry.copy(aliases = entry.aliases.with(kind, current + text))
        }
    }

    fun removeAlias(registry: DimensionRegistry, key: String, kind: AliasKind, alias: String): Either<EditError, DimensionRegistry> =
        update(registry, key) { entry -> entry.copy(aliases = entry.aliases.with(kind, aliasesOf(entry, kind).filter { it != alias })) }

    fun aliasesOf(entry: DimensionEntry, kind: AliasKind): List<String> = when (kind) {
        AliasKind.YAML -> entry.aliases.yaml
        AliasKind.SVG_LABEL -> entry.aliases.svgLabel
        AliasKind.SVG_GEOMETRY -> entry.aliases.svgGeom
        AliasKind.PROSE -> entry.aliases.prose
    }

    private fun DimensionAliases.with(kind: AliasKind, values: List<String>): DimensionAliases = when (kind) {
        AliasKind.YAML -> copy(yaml = values)
        AliasKind.SVG_LABEL -> copy(svgLabel = values)
        AliasKind.SVG_GEOMETRY -> copy(svgGeom = values)
        AliasKind.PROSE -> copy(prose = values)
    }

    private fun update(registry: DimensionRegistry, key: String, change: (DimensionEntry) -> DimensionEntry): Either<EditError, DimensionRegistry> {
        if (registry.entry(key) == null) return EditError.UnknownKey(key).left()
        return checked(registry.copy(dimensions = registry.dimensions.map { if (it.key == key) change(it) else it }))
    }

    private fun checked(registry: DimensionRegistry): Either<EditError, DimensionRegistry> =
        registry.validate().fold({ EditError.InvalidRegistry.left() }, { it.right() })

    private fun readsByDerivation(registry: DimensionRegistry, key: String): Boolean = registry.dimensions.any { entry ->
        entry.derivation?.let { Derivation.parse(it).getOrNull() }?.let { key in Derivation.references(it) } == true
    }
}

/** Owner-facing text for an [EditError]; none of it repeats input text beyond a key the owner typed. */
fun EditError.message(): String = when (this) {
    is EditError.BadKey -> when (error) {
        KeyError.Blank -> "Enter a key"
        KeyError.BadFormat -> "Use lower-case letters, digits, dots, dashes and underscores"
        KeyError.TooLong -> "That key is too long"
        is KeyError.Duplicate -> "That key already exists"
        is KeyError.Unknown -> "Unknown key"
    }
    is EditError.UnknownKey -> "Unknown key $key"
    is EditError.KeyInUse -> "$key is still used by a constraint or derivation"
    EditError.BlankAlias -> "Enter an alias of up to ${RegistryEditing.MAX_ALIAS} characters"
    is EditError.DuplicateAlias -> "Another key ($owner) already uses that alias"
    EditError.BadLabel -> "Use a label of up to ${RegistryEditing.MAX_LABEL} characters"
    EditError.NeedsParts -> "Pick at least one part"
    EditError.RepeatedKey -> "Each dimension can appear only once in a constraint"
    EditError.BadOffset -> "Enter the offset as a length such as 3/4 or 1.5"
    EditError.DuplicateConstraint -> "That constraint already exists"
    EditError.TooMany -> "Too many entries"
    EditError.InvalidRegistry -> "That change would make the registry invalid"
}
