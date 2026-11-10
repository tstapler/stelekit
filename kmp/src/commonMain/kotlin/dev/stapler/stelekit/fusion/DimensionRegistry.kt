package dev.stapler.stelekit.fusion

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class TableAlias(val rowKeyPattern: String, val header: String)

@Serializable
data class DimensionAliases(
    val table: List<TableAlias> = emptyList(),
    val yaml: List<String> = emptyList(),
    val svgLabel: List<String> = emptyList(),
    val svgGeom: List<String> = emptyList(),
    val prose: List<String> = emptyList(),
)

@Serializable
data class DimensionEntry(
    val key: String,
    val kind: DimensionKind,
    val label: String,
    val aliases: DimensionAliases = DimensionAliases(),
    val derivation: String? = null,
)

/** A source-side identity to look up. */
sealed interface Locator {
    data class Table(val rowKey: String, val header: String) : Locator

    data class Yaml(val path: String) : Locator

    data class SvgLabel(val text: String) : Locator

    data class SvgGeom(val ruleId: String) : Locator

    data class Prose(val anchor: String) : Locator
}

sealed interface Resolution {
    data class Resolved(val key: String) : Resolution

    /** No alias matched ([candidates] empty) or several did; the registry never guesses. */
    data class NeedsMapping(val candidates: List<String>) : Resolution
}

sealed interface RegistryError {
    data class UnsupportedSchema(val found: Int) : RegistryError

    data class DuplicateKey(val key: String) : RegistryError

    data class BadDerivation(val key: String, val error: DerivationError) : RegistryError

    data object InvalidJson : RegistryError
}

/**
 * Identity mapping from heterogeneous source keys onto one dimension id (`schema:1`).
 * Aliases: table `(row-key glob, header)`, yaml path, svg label hint (substring), svg geometry
 * rule id, prose anchor phrase (substring). Matching is case-insensitive.
 */
@Serializable
data class DimensionRegistry(val schema: Int = SCHEMA, val dimensions: List<DimensionEntry>) {

    fun entry(key: String): DimensionEntry? = dimensions.firstOrNull { it.key == key }

    fun resolve(locator: Locator): Resolution {
        val matches = dimensions.filter { matches(it.aliases, locator) }.map { it.key }.distinct().sorted()
        return if (matches.size == 1) Resolution.Resolved(matches.single()) else Resolution.NeedsMapping(matches)
    }

    /** Dimensions with a derivation, ordered so each comes after the derived dimensions it reads. */
    fun derivedInOrder(): List<DimensionEntry> {
        val derived = dimensions.filter { it.derivation != null }.associateBy { it.key }
        val ordered = ArrayList<DimensionEntry>()
        val done = HashSet<String>()
        fun visit(entry: DimensionEntry) {
            if (!done.add(entry.key)) return
            val expr = Derivation.parse(entry.derivation.orEmpty()).getOrNull() ?: return
            Derivation.references(expr).sorted().forEach { ref -> derived[ref]?.let(::visit) }
            ordered.add(entry)
        }
        derived.values.sortedBy { it.key }.forEach(::visit)
        return ordered
    }

    /** Schema, unique keys, parseable derivations over known keys, no derivation cycle. */
    fun validate(): Either<RegistryError, DimensionRegistry> {
        if (schema != SCHEMA) return RegistryError.UnsupportedSchema(schema).left()
        dimensions.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }?.let {
            return RegistryError.DuplicateKey(it.key).left()
        }
        val references = HashMap<String, Set<String>>()
        for (entry in dimensions.filter { it.derivation != null }) {
            val expr = Derivation.parse(entry.derivation.orEmpty())
                .fold({ return RegistryError.BadDerivation(entry.key, it).left() }, { it })
            val refs = Derivation.references(expr)
            refs.firstOrNull { entry(it) == null }?.let {
                return RegistryError.BadDerivation(entry.key, DerivationError.UnknownKey(it)).left()
            }
            references[entry.key] = refs
        }
        findCycle(references)?.let { return RegistryError.BadDerivation(it.first(), DerivationError.Cycle(it)).left() }
        return this.right()
    }

    private fun findCycle(references: Map<String, Set<String>>): List<String>? {
        val state = HashMap<String, Int>()
        fun visit(key: String, path: List<String>): List<String>? {
            when (state[key]) {
                DONE -> return null
                IN_PROGRESS -> return path.dropWhile { it != key }
            }
            state[key] = IN_PROGRESS
            for (next in references[key].orEmpty().sorted()) visit(next, path + key)?.let { return it }
            state[key] = DONE
            return null
        }
        return references.keys.sorted().firstNotNullOfOrNull { visit(it, emptyList()) }
    }

    companion object {
        const val SCHEMA = 1
        private const val IN_PROGRESS = 1
        private const val DONE = 2

        private val json = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true // `schema` must always be written
        }

        fun toJson(registry: DimensionRegistry): String = json.encodeToString(serializer(), registry)

        /** Decodes and validates; the error carries no input text. */
        fun fromJson(text: String): Either<RegistryError, DimensionRegistry> {
            val decoded = try {
                json.decodeFromString(serializer(), text)
            } catch (_: SerializationException) {
                return RegistryError.InvalidJson.left()
            } catch (_: IllegalArgumentException) {
                return RegistryError.InvalidJson.left()
            }
            return decoded.validate()
        }

        private fun matches(aliases: DimensionAliases, locator: Locator): Boolean = when (locator) {
            is Locator.Table -> aliases.table.any {
                globMatches(it.rowKeyPattern, locator.rowKey) && it.header.trim().equals(locator.header.trim(), ignoreCase = true)
            }
            is Locator.Yaml -> aliases.yaml.any { it.trim().equals(locator.path.trim(), ignoreCase = true) }
            is Locator.SvgLabel -> aliases.svgLabel.any { it.isNotBlank() && locator.text.contains(it.trim(), ignoreCase = true) }
            is Locator.SvgGeom -> aliases.svgGeom.any { it.trim().equals(locator.ruleId.trim(), ignoreCase = true) }
            is Locator.Prose -> aliases.prose.any { it.isNotBlank() && locator.anchor.contains(it.trim(), ignoreCase = true) }
        }

        /** `*` matches any run of characters; everything else is literal. */
        private fun globMatches(pattern: String, text: String): Boolean {
            val parts = pattern.trim().lowercase().split('*')
            val target = text.trim().lowercase()
            if (parts.size == 1) return target == parts.single()
            if (!target.startsWith(parts.first()) || !target.endsWith(parts.last())) return false
            var index = parts.first().length
            for (middle in parts.subList(1, parts.size - 1)) {
                val found = target.indexOf(middle, index)
                if (found < 0) return false
                index = found + middle.length
            }
            return index <= target.length - parts.last().length
        }
    }
}
