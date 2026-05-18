// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeasurementPropertySyncerTest {

    private fun makeImage(
        uuid: String = "img-1",
        method: CalibrationMethod = CalibrationMethod.BLE_LASER,
        unit: MeasurementUnit = MeasurementUnit.METERS,
    ): ImageAnnotation = ImageAnnotation(
        uuid = uuid,
        blockUuid = "blk-1",
        pageUuid = "page-1",
        graphPath = "/graphs/test",
        filePath = "/graphs/test/assets/images/test.jpg",
        calibration = Calibration(method = method, pixelsPerMeter = 100.0),
        unit = unit,
    )

    private fun makeMeasurement(
        uuid: String,
        imageUuid: String = "img-1",
        type: AnnotationType = AnnotationType.DISTANCE,
        label: String? = null,
        display: String? = "3.0 m",
    ): MeasurementAnnotation = MeasurementAnnotation(
        uuid = uuid,
        imageUuid = imageUuid,
        annotationType = type,
        normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.5, 0.5)),
        valueMeters = 3.0,
        valueDisplay = display,
        label = label,
    )

    // ── 1. Summary properties always present ─────────────────────────────────

    @Test
    fun buildProperties_alwaysIncludesSummaryKeys() {
        val image = makeImage()
        val props = MeasurementPropertySyncer.buildProperties(image, emptyList())

        assertTrue("image-measurement-count" in props)
        assertTrue("image-calibration" in props)
        assertTrue("image-unit" in props)
        assertEquals("0", props["image-measurement-count"])
        assertEquals(CalibrationMethod.BLE_LASER.name, props["image-calibration"])
        assertEquals(MeasurementUnit.METERS.symbol(), props["image-unit"])
    }

    // ── 2. Named DISTANCE measurement exported as measure-<label> ────────────

    @Test
    fun buildProperties_namedDistance_createsMeasureKey() {
        val image = makeImage()
        val m = makeMeasurement(uuid = "m-1", label = "wall-a", display = "3.2 m")
        val props = MeasurementPropertySyncer.buildProperties(image, listOf(m))

        assertTrue("measure-wall-a" in props, "Expected 'measure-wall-a' key")
        assertEquals("3.2 m", props["measure-wall-a"])
    }

    // ── 3. Named AREA measurement exported as area-<label> ───────────────────

    @Test
    fun buildProperties_namedArea_createsAreaKey() {
        val image = makeImage()
        val m = makeMeasurement(
            uuid = "m-2",
            type = AnnotationType.AREA,
            label = "floor",
            display = "12.5 m2",
        )
        val props = MeasurementPropertySyncer.buildProperties(image, listOf(m))

        assertTrue("area-floor" in props, "Expected 'area-floor' key")
        assertEquals("12.5 m2", props["area-floor"])
    }

    // ── 4. Named ANGLE measurement exported as angle-<label> ─────────────────

    @Test
    fun buildProperties_namedAngle_createsAngleKey() {
        val image = makeImage()
        val m = makeMeasurement(
            uuid = "m-3",
            type = AnnotationType.ANGLE,
            label = "corner",
            display = "90.0 deg",
        )
        val props = MeasurementPropertySyncer.buildProperties(image, listOf(m))

        assertTrue("angle-corner" in props, "Expected 'angle-corner' key")
        assertEquals("90.0 deg", props["angle-corner"])
    }

    // ── 5. Unnamed measurement counted but not exported as named key ──────────

    @Test
    fun buildProperties_unnamedMeasurement_countedButNoKey() {
        val image = makeImage()
        val m = makeMeasurement(uuid = "m-4", label = null)
        val props = MeasurementPropertySyncer.buildProperties(image, listOf(m))

        assertEquals("1", props["image-measurement-count"])
        assertFalse(props.keys.any { it.startsWith("measure-") })
    }

    // ── 6. Label with special characters sanitized to dashes ─────────────────

    @Test
    fun buildProperties_specialCharLabel_sanitized() {
        val image = makeImage()
        val m = makeMeasurement(uuid = "m-5", label = "Wall A (North)", display = "5.0 m")
        val props = MeasurementPropertySyncer.buildProperties(image, listOf(m))

        // "Wall A (North)" → "wall-a--north-"
        val key = props.keys.firstOrNull { it.startsWith("measure-") }
        assertTrue(key != null, "Expected a measure- key from sanitized label")
    }

    // ── 7. LABEL annotation type is skipped ───────────────────────────────────

    @Test
    fun buildProperties_textLabel_skipped() {
        val image = makeImage()
        val m = makeMeasurement(uuid = "m-6", type = AnnotationType.LABEL, label = "note", display = "text")
        val props = MeasurementPropertySyncer.buildProperties(image, listOf(m))

        assertFalse(props.keys.any { it.startsWith("measure-") || it.startsWith("angle-") || it.startsWith("area-") })
    }

    // ── 8. merge preserves existing keys not touched by syncer ───────────────

    @Test
    fun merge_preservesExistingKeys() {
        val existing = mapOf("title" to "My Page", "tags" to "building")
        val newProps = mapOf("image-measurement-count" to "2", "image-calibration" to "MANUAL_REFERENCE")
        val merged = MeasurementPropertySyncer.merge(existing, newProps)

        assertEquals("My Page", merged["title"])
        assertEquals("building", merged["tags"])
        assertEquals("2", merged["image-measurement-count"])
    }

    // ── 9. merge overwrites syncer keys ──────────────────────────────────────

    @Test
    fun merge_overwritesSyncerKeys() {
        val existing = mapOf("image-measurement-count" to "0", "image-calibration" to "NONE")
        val newProps = mapOf("image-measurement-count" to "5", "image-calibration" to "BLE_LASER")
        val merged = MeasurementPropertySyncer.merge(existing, newProps)

        assertEquals("5", merged["image-measurement-count"])
        assertEquals("BLE_LASER", merged["image-calibration"])
    }
}

class MeasureTemplateResolverTest {

    // ── 1. Basic resolution ───────────────────────────────────────────────────

    @Test
    fun resolve_basicPattern_replacedCorrectly() {
        val table = mapOf("img-1.wall-a" to "3.2 m")
        val result = MeasureTemplateResolver.resolve("Length = {{measure: img-1.wall-a}}", table)
        assertEquals("Length = 3.2 m", result)
    }

    // ── 2. Unresolved pattern left as-is ─────────────────────────────────────

    @Test
    fun resolve_missingKey_leftUnchanged() {
        val table = emptyMap<String, String>()
        val input = "{{measure: img-1.missing}}"
        val result = MeasureTemplateResolver.resolve(input, table)
        assertEquals(input, result)
    }

    // ── 3. No measure token — fast path (no regex) ────────────────────────────

    @Test
    fun resolve_noToken_returnsSameString() {
        val input = "Just a block with no template tokens"
        val result = MeasureTemplateResolver.resolve(input, emptyMap())
        assertEquals(input, result)
    }

    // ── 4. Multiple tokens in one string ─────────────────────────────────────

    @Test
    fun resolve_multipleTokens_allReplaced() {
        val table = mapOf("img-1.wall-a" to "3.2 m", "img-1.floor" to "12.5 m2")
        val result = MeasureTemplateResolver.resolve(
            "Wall = {{measure: img-1.wall-a}}, Floor = {{measure: img-1.floor}}",
            table,
        )
        assertEquals("Wall = 3.2 m, Floor = 12.5 m2", result)
    }

    // ── 5. buildLookupTable produces correct keys ─────────────────────────────

    @Test
    fun buildLookupTable_namedMeasurements_correctKeys() {
        val m1 = MeasurementAnnotation(
            uuid = "m-1",
            imageUuid = "img-1",
            annotationType = AnnotationType.DISTANCE,
            normalizedPoints = emptyList(),
            valueDisplay = "3.2 m",
            label = "wall-a",
        )
        val m2 = MeasurementAnnotation(
            uuid = "m-2",
            imageUuid = "img-1",
            annotationType = AnnotationType.AREA,
            normalizedPoints = emptyList(),
            valueDisplay = "12.0 m2",
            label = "floor",
        )
        val table = MeasureTemplateResolver.buildLookupTable(listOf("img-1" to listOf(m1, m2)))

        assertEquals("3.2 m", table["img-1.wall-a"])
        assertEquals("12.0 m2", table["img-1.floor"])
    }

    // ── 6. buildLookupTable skips blank labels ────────────────────────────────

    @Test
    fun buildLookupTable_blankLabel_skipped() {
        val m = MeasurementAnnotation(
            uuid = "m-3",
            imageUuid = "img-1",
            annotationType = AnnotationType.DISTANCE,
            normalizedPoints = emptyList(),
            valueDisplay = "1.0 m",
            label = "",
        )
        val table = MeasureTemplateResolver.buildLookupTable(listOf("img-1" to listOf(m)))
        assertTrue(table.isEmpty())
    }
}
