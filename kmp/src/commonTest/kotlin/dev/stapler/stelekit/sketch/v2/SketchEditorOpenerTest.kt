// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.platform.AlwaysHeldSketchWriterLease
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLeaseRegistry
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.NoEditorReason
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Task 6.2.0b: what a project's first sheet opens in, decided by the router and the sheet's edit lease. */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class SketchEditorOpenerTest {
    private fun TestScope.opener(rig: V2EditorRig) = V2EditorOpener(rig.deps, StandardTestDispatcher(testScheduler))

    private suspend fun V2EditorOpener.openRig(rig: V2EditorRig) = open(rig.project.uuid, FixedAdvanceTextMetricsProvider())

    @Test
    fun v2Sheet_should_OpenEditable_When_TheModeIsOn() = runTest {
        val rig = V2EditorRig().also { it.seed() }

        val opening = assertIs<EditorOpening.Editor>(opener(rig).openRig(rig))

        assertFalse(opening.session.controller.readOnly)
        opening.session.close()
    }

    @Test
    fun v2Sheet_should_OpenReadOnly_When_TheModeIsOff() = runTest {
        val rig = V2EditorRig(mode = V2Mode.Off).also { it.seed() }

        val opening = assertIs<EditorOpening.Editor>(opener(rig).openRig(rig))

        assertTrue(opening.session.controller.readOnly)
        opening.session.close()
    }

    @Test
    fun secondOpen_should_BeReadOnly_When_TheSheetIsAlreadyOpenInAnEditor() = runTest {
        val leases = SheetLeaseRegistry(AlwaysHeldSketchWriterLease())
        val rig = V2EditorRig(leases = leases).also { it.seed() }
        val opener = opener(rig)

        val first = assertIs<EditorOpening.Editor>(opener.openRig(rig))
        val second = assertIs<EditorOpening.Editor>(opener.openRig(rig))

        assertFalse(first.session.controller.readOnly)
        assertTrue(second.session.controller.readOnly, "the sheet is held by the first editor")
        first.session.close()
        val third = assertIs<EditorOpening.Editor>(opener.openRig(rig))
        assertFalse(third.session.controller.readOnly, "closing the first editor released the lease")
        second.session.close()
        third.session.close()
    }

    @Test
    fun classicSheet_should_OpenInTheLegacyEditor() = runTest {
        val rig = V2EditorRig()
        val classic = SketchSheet(rig.sheet, rig.project.uuid, "plan", "top", "in", 16)
        rig.repos.projects.replaceProjectContents(rig.project.uuid, ProjectBundle(rig.project, listOf(classic), emptyList(), emptyList(), emptyMap()))

        assertEquals(EditorOpening.Legacy, opener(rig).openRig(rig))
    }

    @Test
    fun newerSchemaFile_should_ShowTheNoEditorScreen() = runTest {
        val rig = V2EditorRig().also { it.seed() }
        rig.store.files[SheetPaths.pathFor(rig.sheet, SheetPaths.SCHEMA_V2)] = "{\"schema\":3,\"elements\":[]}".encodeToByteArray()

        val opening = assertIs<EditorOpening.NoEditor>(opener(rig).openRig(rig))

        assertEquals(NoEditorReason.NewerSchema, opening.reason)
    }
}
