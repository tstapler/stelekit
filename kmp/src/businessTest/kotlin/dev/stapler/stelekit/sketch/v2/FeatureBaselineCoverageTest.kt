// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 3.5.4c, the JVM half: every baseline id names a covering test class, and that class exists on the test classpath with a
 * `@Test` method. (`FeatureBaselineCompletenessTest` runs on every target but cannot look at test classes on wasmJs.)
 */
class FeatureBaselineCoverageTest {
    private val testPackage = "dev.stapler.stelekit.sketch.v2"

    @Test
    fun everyCoveringTest_should_ExistWithAtLeastOneTestMethod() {
        val problems = FeatureBaseline.features.map { it.test }.toSortedSet().mapNotNull { name ->
            val type = runCatching { Class.forName("$testPackage.$name") }.getOrNull()
            when {
                type == null -> "$name: no such test class in $testPackage"
                type.methods.none { m -> m.annotations.any { it.annotationClass.simpleName == "Test" } } -> "$name: no @Test method"
                else -> null
            }
        }
        assertEquals(emptyList(), problems, "baseline ids point at missing tests")
    }

    @Test
    fun theBaseline_should_NotBeEmpty_SoTheCheckCannotPassVacuously() {
        assertTrue(FeatureBaseline.features.size == EXPECTED, "expected $EXPECTED baseline features, found ${FeatureBaseline.features.size}")
    }

    private companion object {
        const val EXPECTED = 41
    }
}
