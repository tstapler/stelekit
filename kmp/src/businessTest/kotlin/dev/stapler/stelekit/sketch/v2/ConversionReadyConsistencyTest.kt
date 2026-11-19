// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.AllBusinessTests
import dev.stapler.stelekit.projectbridge.V2Build
import org.junit.runners.Suite
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `V2Build.CONVERSION_READY` cannot lie (review R6): while it is true, every test class of Stories 1.1.1, 1.1.3, 1.1.4,
 * 1.1.5, 1.4.1 and 1.4.5 named below is registered in `AllBusinessTests` (which registers the commonTest classes too) and
 * loads by name on the test classpath. Reflection on the suite annotation reads the same list as its source, under Gradle
 * and under Bazel alike (no BUILD file is readable in the Bazel sandbox).
 */
class ConversionReadyConsistencyTest {
    private val required = listOf(
        "BundleV2PassthroughTest", "ReconcilerV2GoldenTest", "SidecarWriterRaceTest", "SidecarStoreOpaqueContractTest",
        "ReplaceProjectContentsV2Test", "TechnicalDataTest", "ConversionPolicyTest", "SheetCodecV2Test", "SheetCodecV2FuzzTest",
        "MixedProjectFusionGoldenTest", "SketchPreviewV2Test", "SketchSheetWritePathsPreserveSchemaTest", "SheetCodecDispatchTest",
    )

    private fun registered(): Set<String> =
        AllBusinessTests::class.java.getAnnotation(Suite.SuiteClasses::class.java).value.map { it.java.name }.toSet()

    @Test
    fun conversionReady_should_BeTrueOnlyWhileEveryPrerequisiteTestIsRegisteredAndLoads() {
        if (!V2Build.CONVERSION_READY) return
        val names = registered()
        val missing = required.filter { simple -> names.none { it.substringAfterLast('.') == simple } }
        assertTrue(missing.isEmpty(), "CONVERSION_READY is true but these tests are not registered in AllBusinessTests: $missing")
        val unloadable = required.flatMap { simple -> names.filter { it.substringAfterLast('.') == simple } }
            .filter { runCatching { Class.forName(it) }.isFailure }
        assertTrue(unloadable.isEmpty(), "registered but not loadable on the test classpath: $unloadable")
    }

    @Test
    fun theRequiredListHasNoDuplicates() {
        assertTrue(required.toSet().size == required.size)
    }
}
