// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SvgTransformTest {
    private fun ok(chain: List<String>): Affine = (composeTransforms(chain) as Either.Right).value

    private fun assertNear(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected got $actual")

    @Test
    fun listAppliesRightmostFirst() {
        // scale(2) translate(10,0): translate first, then scale
        val p = ok(listOf("scale(2, 2)   translate(10,0)")).apply(1.0, 1.0)
        assertNear(22.0, p.first)
        assertNear(2.0, p.second)
    }

    @Test
    fun nestedGroupsComposeOuterFirst() {
        val p = ok(listOf("translate(10,0)", "scale(2)")).apply(1.0, 1.0)
        assertNear(12.0, p.first)
        assertNear(2.0, p.second)
    }

    @Test
    fun matrixAndNegativeScale() {
        val p = ok(listOf("matrix(1 0 0 -1 5 5)")).apply(2.0, 3.0)
        assertNear(7.0, p.first)
        assertNear(2.0, p.second)
        assertNear(-4.0, ok(listOf("scale(2,-2)")).d * 2)
    }

    @Test
    fun skewIsATypedError() {
        assertIs<TransformError.Skew>((composeTransforms(listOf("skewX(10)")) as Either.Left).value)
        assertIs<TransformError.Skew>((composeTransforms(listOf("skewY(10)")) as Either.Left).value)
    }

    @Test
    fun malformedAndUnknownAreTypedErrors() {
        assertIs<TransformError.Malformed>((composeTransforms(listOf("scale(a)")) as Either.Left).value)
        assertIs<TransformError.Malformed>((composeTransforms(listOf("scale(1")) as Either.Left).value)
        assertIs<TransformError.Unsupported>((composeTransforms(listOf("rotate(5)")) as Either.Left).value)
    }

    @Test
    fun emptyChainIsIdentity() {
        assertEquals(Affine.IDENTITY, ok(emptyList()))
    }
}
