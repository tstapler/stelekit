// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation

// Regex compiled once at file level — prevents RegexInLambda detekt warning.
private val LABEL_SANITIZE_REGEX = Regex("[^a-zA-Z0-9-_]")

/**
 * Derives a [Map] of Logseq-compatible block properties from an [ImageAnnotation] and its
 * list of [MeasurementAnnotation]s.
 *
 * Callers should merge the returned map into the parent block's existing properties and persist
 * it via [BlockRepository.saveBlock] or [DatabaseWriteActor.saveBlock].
 *
 * Property conventions:
 * - Named annotations (non-blank [MeasurementAnnotation.label]) per annotation type:
 *   - DISTANCE → `measure-<label>:: <valueDisplay>`
 *   - AREA     → `area-<label>:: <valueDisplay>`
 *   - ANGLE    → `angle-<label>:: <valueDisplay>`
 * - Summary properties always present:
 *   - `image-measurement-count:: <n>`
 *   - `image-calibration:: <method>`
 *   - `image-unit:: <unit symbol>`
 *
 * Tags are persisted via [ImageAnnotationRepository.saveImageAnnotation]; this syncer
 * does NOT produce a `tags::` property because Logseq tag format requires `[[bracketed]]`
 * values which must be managed separately.
 */
object MeasurementPropertySyncer {

    /**
     * Build the properties map for a block that represents [image].
     *
     * [measurements] must be the complete, current list of measurements for [image].
     * Measurements with a blank [MeasurementAnnotation.label] are counted in
     * `image-measurement-count` but are not individually exported as named properties.
     */
    fun buildProperties(
        image: ImageAnnotation,
        measurements: List<MeasurementAnnotation>,
    ): Map<String, String> {
        val props = mutableMapOf<String, String>()

        // Summary properties
        props["image-measurement-count"] = measurements.size.toString()
        props["image-calibration"] = image.calibration.method.name
        props["image-unit"] = image.unit.symbol()

        // Named measurement properties
        for (m in measurements) {
            val label = m.label?.trim() ?: continue
            if (label.isBlank()) continue
            val displayValue = m.valueDisplay ?: continue
            val sanitizedLabel = label.replace(LABEL_SANITIZE_REGEX, "-").lowercase()
            val key = when (m.annotationType) {
                AnnotationType.DISTANCE, AnnotationType.GRID_REF -> "measure-$sanitizedLabel"
                AnnotationType.AREA -> "area-$sanitizedLabel"
                AnnotationType.ANGLE -> "angle-$sanitizedLabel"
                AnnotationType.LABEL -> continue // text labels are not numeric — skip
            }
            props[key] = displayValue
        }

        return props
    }

    /**
     * Merge [newProps] into [existingProps], overwriting only the keys produced by
     * [buildProperties]. Existing keys not touched by this syncer are preserved.
     */
    fun merge(existingProps: Map<String, String>, newProps: Map<String, String>): Map<String, String> {
        return existingProps + newProps
    }
}

/**
 * Resolves `{{measure: <image-ref>.<label>}}` template expressions in a block content string.
 *
 * Resolution is purely string-based and requires a pre-built lookup table keyed by
 * `"<imageAnnotationUuid>.<sanitizedLabel>"`. The caller is responsible for building this
 * table from [MeasurementAnnotation] lists at the appropriate scope.
 *
 * Returns the input [content] with all resolved expressions replaced. Unresolved expressions
 * are left as-is so missing data surfaces visibly to the user.
 *
 * Example:
 * ```
 * "Wall A = {{measure: img123.wall-a}}"
 * // → "Wall A = 3.2 m"
 * ```
 */
object MeasureTemplateResolver {

    private val MEASURE_PATTERN = Regex("""\{\{measure:\s*([^.}]+)\.([^}]+)}}""")

    /**
     * Replace all `{{measure: <uuid>.<label>}}` tokens in [content].
     *
     * [lookupTable] maps `"<imageAnnotationUuid>.<sanitizedLabel>"` → `"<valueDisplay>"`.
     */
    fun resolve(content: String, lookupTable: Map<String, String>): String {
        if (!content.contains("{{measure:")) return content
        return MEASURE_PATTERN.replace(content) { match ->
            val uuid = match.groupValues[1].trim()
            val label = match.groupValues[2].trim()
                .replace(LABEL_SANITIZE_REGEX, "-")
                .lowercase()
            lookupTable["$uuid.$label"] ?: match.value // leave unchanged when not found
        }
    }

    /**
     * Build a lookup table from a list of (imageAnnotationUuid, measurements) pairs.
     *
     * Suitable for resolving a page's entire content in one pass.
     */
    fun buildLookupTable(
        entries: List<Pair<String, List<MeasurementAnnotation>>>,
    ): Map<String, String> {
        val table = mutableMapOf<String, String>()
        for ((uuid, measurements) in entries) {
            for (m in measurements) {
                val label = m.label?.trim() ?: continue
                if (label.isBlank()) continue
                val sanitized = label.replace(LABEL_SANITIZE_REGEX, "-").lowercase()
                val displayValue = m.valueDisplay ?: continue
                table["$uuid.$sanitized"] = displayValue
            }
        }
        return table
    }
}
