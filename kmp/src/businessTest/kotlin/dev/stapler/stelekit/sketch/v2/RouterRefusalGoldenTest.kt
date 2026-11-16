// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionPolicy
import dev.stapler.stelekit.sketch.v2.runtime.convert.HasTechnicalData
import dev.stapler.stelekit.sketch.v2.runtime.convert.ProjectAcknowledgements
import dev.stapler.stelekit.sketch.v2.runtime.convert.SheetConverter
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.UpgradeAffordance
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(DirectRepositoryWrite::class)
/** Task 1.4.3c: the refusal for technical data reaches the user through the router, and nothing is written. */
class RouterRefusalGoldenTest {
    private val sheet = SheetUuid("p1-s0")

    @Test
    fun technicalDataProject_should_OpenLegacyWithTheReason_WriteNothingAndCreateNoBackup() = runBlocking<Unit> {
        val bundle = SketchTestSupport.bundle().let { it.copy(dimensions = listOf(SketchTestSupport.dimension("d1"))) }
        val device = Device()
        device.store.write("proj-0a1b2c", SidecarCodec.encode(bundle))
        device.repos.projects.replaceProjectContents(SketchTestSupport.P, bundle)
        val writesBefore = device.fs.writeCount
        val refusal = ConversionPolicy.refusal(bundle, bundle.sheets.first { it.uuid == sheet })
        assertEquals(HasTechnicalData, refusal)

        val converter = SheetConverter(device.repos, device.store, SidecarSlugLock(), ProjectAcknowledgements.Always, { true })
        val outcome = converter.convert(SketchTestSupport.P, sheet)

        assertEquals(ConversionOutcome.Failed(ConversionFailure.HasTechnicalData), outcome)
        assertEquals(writesBefore, device.fs.writeCount, "no file was written")
        assertTrue(device.fs.allFilePaths().none { it.contains("/backup/") }, "no backup was created")
        val caps = V2Capabilities.of(V2Storage.Available)
        val routed = SketchEditorRouter.route(RouteInput(V2Mode.On, caps, SheetSchema.V1, refusal = refusal))
        assertEquals(RouteOutcome.Legacy(UpgradeAffordance.Disabled(HasTechnicalData)), routed)
        assertEquals("This sketch has zones or dimensions the new editor can't edit yet.", SketchReasonText.HAS_TECHNICAL_DATA)
    }
}
