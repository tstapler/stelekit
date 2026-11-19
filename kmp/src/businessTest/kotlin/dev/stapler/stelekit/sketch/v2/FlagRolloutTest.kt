// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.platform.SketchWriterLeaseState
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.ui.sketch.ConversionState
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Task 6.2.5a: the rollout behaviours of the flag that need no device. Flag-off v2 sheets are read-only with the reason
 * text, opening never converts, and a stored v1 sheet keeps its legacy editor in every mode (metric 6: nothing a
 * current user has changes by default). The comparison against the pre-change baseline is
 * `scripts/sketch-baseline/check-sketch-baseline.sh` (CI job `sketch-regression-baseline`).
 */
class FlagRolloutTest {
    private val capabilities = V2Capabilities(conversionReady = true, conversionOffered = true, storage = V2Storage.Available)

    private fun route(mode: V2Mode, schema: SheetSchema, conversion: ConversionState = ConversionState.NotConverted) =
        SketchEditorRouter.route(RouteInput(mode, capabilities, schema, conversion, null, SketchWriterLeaseState.Held))

    @Test
    fun flagOffV2Sheet_should_OpenReadOnlyWithTheReasonText() {
        val outcome = route(V2Mode.Off, SheetSchema.V2)

        assertEquals(RouteOutcome.V2ReadOnly(SketchReasonText.READ_ONLY_TURN_ON), outcome)
        assertTrue(SketchReasonText.READ_ONLY_TURN_ON.isNotBlank())
    }

    @Test
    fun opening_should_NeverConvertAV1Sheet_InAnyMode() {
        for (mode in V2Mode.entries) {
            for (state in listOf(ConversionState.NotConverted, ConversionState.Declined)) {
                assertNotEquals(RouteOutcome.ConvertThenV2, route(mode, SheetSchema.V1, state), "$mode $state")
            }
        }
    }

    @Test
    fun v1Sheet_should_KeepItsLegacyEditor_InEveryMode() {
        for (mode in V2Mode.entries) {
            val outcome = route(mode, SheetSchema.V1)
            assertTrue(outcome is RouteOutcome.Legacy, "$mode opened $outcome")
        }
    }

    @Test
    fun theDefaultMode_should_NotOfferTheNewEditorToAV1Sheet() {
        val default = route(V2Mode.Off, SheetSchema.V1)

        assertEquals(RouteOutcome.Legacy(dev.stapler.stelekit.ui.sketch.UpgradeAffordance.None), default)
    }
}
