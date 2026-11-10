package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.units.LengthQualifier
import dev.stapler.stelekit.units.Rational
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DimensionKind {
    /** Compared within the rounding tolerance of the written values. */
    @SerialName("length")
    LENGTH,

    /** Compared for equality; precision plays no part. */
    @SerialName("count")
    COUNT,
}

enum class SourceKind { TABLE, YAML, SVG_LABEL, SVG_GEOMETRY, PROSE, DERIVED }

/**
 * Opaque handle for where a value was read: `<kind>:<hash prefix of the locator>`. It names no
 * page, title or path; the human-readable locator lives only in the device-local [RefTable].
 */
data class SourceRef(val kind: SourceKind, val id: String) {
    /** Two observations are independent only if their refs differ after this normalisation. */
    val normalised: String get() = "${kind.name.lowercase()}:${id.trim().lowercase()}"

    override fun toString(): String = normalised
}

/** Where a source sits relative to our own digest output (ADR-003 self-exclusion, both levels). */
data class SourceOrigin(
    val pagePropertyKeys: Set<String> = emptySet(),
    val firstBlockPropertyKeys: Set<String> = emptySet(),
    val blockPropertyKeys: Set<String> = emptySet(),
) {
    /**
     * True when the page properties, the page's first block (the Active writer may demote page
     * properties there) or the containing block carry `stelekit-digest::`.
     */
    val carriesDigestMarker: Boolean
        get() = (pagePropertyKeys + firstBlockPropertyKeys + blockPropertyKeys).any { isDigestKey(it) }

    companion object {
        const val DIGEST_KEY = "stelekit-digest"

        private fun isDigestKey(key: String) = key.trim().removeSuffix("::").trim().lowercase() == DIGEST_KEY
    }
}

data class Source(val kind: SourceKind, val ref: SourceRef, val origin: SourceOrigin = SourceOrigin())

/**
 * One reading of a dimension. [value] is exact; [precision] is the half-width of the rounding the
 * writer applied (zero for a fraction or an exact decimal), so a stored `37.9"` stays 379/10.
 */
data class Observation(
    val dimension: String,
    val value: Rational,
    val precision: Rational,
    val kind: DimensionKind,
    val source: Source,
    /** Writer's hedges (`~24"` is [LengthQualifier.APPROX]); the engine does not read them yet. */
    val qualifiers: Set<LengthQualifier> = emptySet(),
)

/** A drawing label's horizontal position and parsed value, for mirrored-view detection. */
data class SvgLabel(val x: Rational, val value: Rational, val precision: Rational = Rational.ZERO)
