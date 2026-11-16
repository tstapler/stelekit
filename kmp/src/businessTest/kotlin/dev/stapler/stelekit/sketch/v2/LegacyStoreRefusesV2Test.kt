@file:OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.platform.SketchWriterLeaseState
import dev.stapler.stelekit.projectbridge.SheetFileSchemaProbe
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarFs
import dev.stapler.stelekit.projectbridge.V2Fixtures
import dev.stapler.stelekit.projectbridge.newStore
import dev.stapler.stelekit.projectbridge.sidecarPath
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseHolder
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLeaseRegistry
import dev.stapler.stelekit.ui.sketch.SketchPhase
import dev.stapler.stelekit.ui.sketch.SketchViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review C5: the classic editor refuses a v2 sheet at the store level, and holds its edit lease through the registry. */
class LegacyStoreRefusesV2Test {
    private suspend fun seeded(mirrorV2: Boolean): SketchRepositories {
        val repos = inMemorySketchRepositories()
        val v2 = SketchTestSupport.sheet(V2Fixtures.V2_SHEET).copy(schemaVersion = SheetPaths.SCHEMA_V2)
        val bundle = SketchTestSupport.bundle(sheets = 1).let { v1 ->
            if (mirrorV2) {
                v1.copy(sheets = listOf(v2), primitives = emptyList(), sheetPayloads = mapOf(v2.uuid to SheetPayload.V2Opaque.of(V2Fixtures.bytes)))
            } else {
                v1
            }
        }
        repos.projects.replaceProjectContents(SketchTestSupport.P, bundle)
        return repos
    }

    private fun TestScope.vm(
        repos: SketchRepositories,
        leases: SheetLeaseRegistry? = null,
        probe: SheetFileSchemaProbe? = null,
    ) = SketchViewModel(
        repos, SketchTestSupport.P, null, StandardTestDispatcher(testScheduler), leases = leases, v2Probe = probe, newId = { "gen" },
    )

    @Test
    fun aV2MirrorRow_should_NotLoadInTheClassicEditor() = runTest {
        val vm = vm(seeded(mirrorV2 = true))
        runCurrent()
        assertEquals(SketchPhase.FAILED, vm.uiState.value.phase)
        assertTrue(vm.uiState.value.error.orEmpty().contains("new editor"), vm.uiState.value.error)
    }

    @Test
    fun aV2File_should_NotLoad_EvenWhenTheMirrorSaysV1() = runTest {
        val repos = seeded(mirrorV2 = false)
        val sheet = SketchTestSupport.sheet("p1-s0")
        val fs = SidecarFs()
        fs.seed(
            sidecarPath("proj-0a1b2c", "project-model.json"),
            "{\"schema\":1,\"sheets\":[{\"uuid\":\"${sheet.uuid.value}\",\"file\":\"sheets/${sheet.uuid.value}.v2.json\"}]}".encodeToByteArray(),
        )
        fs.seed(sidecarPath("proj-0a1b2c", "sheets/${sheet.uuid.value}.v2.json"), V2Fixtures.bytes)
        val vm = vm(repos, probe = SheetFileSchemaProbe(newStore(fs)))
        runCurrent()
        assertEquals(SketchPhase.FAILED, vm.uiState.value.phase)
    }

    @Test
    fun aV1File_should_StillLoad_WithTheProbe() = runTest {
        val repos = seeded(mirrorV2 = false)
        val vm = vm(repos, probe = SheetFileSchemaProbe(newStore(SidecarFs())))
        runCurrent()
        assertEquals(SketchPhase.READY, vm.uiState.value.phase)
    }

    @Test
    fun theClassicEditor_should_HoldItsLease_AndReleaseItWhenForgotten() = runTest {
        val registry = SheetLeaseRegistry(TestSketchWriterLease())
        val vm = vm(seeded(mirrorV2 = false), leases = registry)
        runCurrent()
        assertEquals(SketchPhase.READY, vm.uiState.value.phase)
        val sheet = SketchTestSupport.sheet("p1-s0").uuid
        assertEquals(LeaseHolder.Legacy, registry.holderOf(sheet))
        vm.onForgotten()
        runCurrent()
        assertNull(registry.holderOf(sheet))
    }

    @Test
    fun theClassicEditor_should_BeRefused_WhileTheV2EditorHoldsTheSheet() = runTest {
        val registry = SheetLeaseRegistry(TestSketchWriterLease(SketchWriterLeaseState.Held))
        val sheet = SketchTestSupport.sheet("p1-s0").uuid
        val v2 = registry.acquire(sheet, LeaseHolder.V2).getOrNull()!!
        val vm = vm(seeded(mirrorV2 = false), leases = registry)
        runCurrent()
        assertEquals(SketchPhase.FAILED, vm.uiState.value.phase)
        assertEquals(LeaseHolder.V2, registry.holderOf(sheet))
        v2.release()
    }
}
