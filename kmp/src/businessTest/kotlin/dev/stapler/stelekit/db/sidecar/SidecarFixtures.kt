// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db.sidecar

/**
 * Golden sidecars as compact JSON in the exact key order and default-omitting style the shipped writer
 * produces. Embedded rather than loaded from resources so the audit gates need no resource bundling.
 */
object SidecarFixtures {
    const val IMAGE_UUID = "goldenimg1"
    const val GRAPH = "/g"

    /** `sidecar-v0113.json`: what v0.113.3 writes (no `schemaVersion`, no constraints, no value source). */
    const val V0113: String =
        """{"imageAnnotation":{"uuid":"goldenimg1","blockUuid":"goldenblk1","pageUuid":"goldenpg1","graphPath":"/g",""" +
            """"filePath":"/g/assets/images/golden.jpg","importedAtMs":1700000000000,"calibrationMethod":"MANUAL_REFERENCE",""" +
            """"pixelsPerMeter":1843.7512345,"calibrationConfidencePct":100,"calibrationBasisVersion":2,""" +
            """"calibrationLens":{"k":-0.012,"ticks":[{"x":0.1,"y":0.9,"positionMeters":0.0},{"x":0.9,"y":0.9,"positionMeters":0.3}],""" +
            """"residualPercent":0.4},"calibrationGeometry":{"heightOffsetMeters":0.002,"cameraDistanceMeters":0.61,""" +
            """"plane":{"homography":[0.001,0.0,0.0,0.0,0.001,0.0,0.0,0.0,1.0],"referenceName":"A4 paper (297 x 210 mm)",""" +
            """"widthMeters":0.297,"heightMeters":0.21,"checkResidualMm":1.5}},"unit":"CENTIMETERS"},""" +
            """"measurements":[{"uuid":"goldenms1","imageUuid":"goldenimg1","annotationType":"DISTANCE",""" +
            """"normalizedPoints":[{"x":0.1,"y":0.2},{"x":0.6,"y":0.25}],"valueMeters":0.8741234567891,"valueDisplay":"87.4 cm"},""" +
            """{"uuid":"goldenms2","imageUuid":"goldenimg1","annotationType":"DISTANCE",""" +
            """"normalizedPoints":[{"x":0.3,"y":0.7},{"x":0.35,"y":0.1}],"valueMeters":0.5512345678901,"label":"shelf"}]}"""

    /** A SCALE-tier fit: no plane, `MANUAL_REFERENCE`, constraints and fit present, one override. */
    const val V2_SCALE: String =
        """{"schemaVersion":2,"imageAnnotation":{"uuid":"goldenimg1","blockUuid":"goldenblk1","pageUuid":"goldenpg1","graphPath":"/g",""" +
            """"filePath":"/g/assets/images/golden.jpg","importedAtMs":1700000000000,"calibrationMethod":"MANUAL_REFERENCE",""" +
            """"pixelsPerMeter":1843.7512345,"calibrationConfidencePct":100,"calibrationBasisVersion":1,""" +
            """"calibrationGeometry":{"constraints":[{"id":"c1","p1":{"x":0.1,"y":0.2},"p2":{"x":0.6,"y":0.25},"distanceMeters":0.9}],""" +
            """"fit":{"tier":"SCALE","n":3,"residualMeanPct":1.2,"looPct":1.8}}},""" +
            """"measurements":[{"uuid":"goldenms1","imageUuid":"goldenimg1","annotationType":"DISTANCE",""" +
            """"normalizedPoints":[{"x":0.1,"y":0.2},{"x":0.6,"y":0.25}],"valueMeters":0.9,""" +
            """"valueSource":"USER_OVERRIDE","originalValueMeters":0.8741234567891}]}"""

    /** A v2 sidecar: a constraint fit with a CONSTRAINT_FIT plane, one constraint and one overridden value. */
    const val V2: String =
        """{"schemaVersion":2,"imageAnnotation":{"uuid":"goldenimg1","blockUuid":"goldenblk1","pageUuid":"goldenpg1","graphPath":"/g",""" +
            """"filePath":"/g/assets/images/golden.jpg","importedAtMs":1700000000000,"calibrationMethod":"MANUAL_REFERENCE",""" +
            """"pixelsPerMeter":1843.7512345,"calibrationConfidencePct":100,"calibrationBasisVersion":1,""" +
            """"calibrationGeometry":{"heightOffsetMeters":0.002,"plane":{"homography":[0.001,0.0,0.0,0.0,0.001,0.0,0.0,0.0,1.0],""" +
            """"referenceName":"Known distances","widthMeters":1.2,"heightMeters":0.9,"kind":"CONSTRAINT_FIT"},""" +
            """"constraints":[{"id":"c1","p1":{"x":0.1,"y":0.2},"p2":{"x":0.6,"y":0.25},"distanceMeters":0.9,"label":"door",""" +
            """"source":"FROM_MEASUREMENT","measurementUuid":"goldenms1"}],""" +
            """"fit":{"tier":"GRADIENT","n":6,"residualMeanPct":1.2,"looPct":1.8,"worstId":"c1"}},"unit":"CENTIMETERS"},""" +
            """"measurements":[{"uuid":"goldenms1","imageUuid":"goldenimg1","annotationType":"DISTANCE",""" +
            """"normalizedPoints":[{"x":0.1,"y":0.2},{"x":0.6,"y":0.25}],"valueMeters":0.9,"valueDisplay":"90 cm",""" +
            """"valueSource":"USER_OVERRIDE","originalValueMeters":0.8741234567891}]}"""
}
