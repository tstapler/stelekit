// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.FileSystemSketchDurability
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketch.v2.ScriptedSidecarStore
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerStringSource
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetTarget
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun newVisibilityTarget(): JsAny = js("Object.assign(new EventTarget(), { visibilityState: 'visible' })")

private fun fireVisibilityChange(target: JsAny, state: String): Unit =
    js("(function() { target.visibilityState = state; target.dispatchEvent(new Event('visibilitychange')); })()")

/**
 * Task 1.4.7m (D-141, D-146): the web composition root's pieces behave as the root wires them. The wasm `browser/Main.kt`
 * itself cannot be run here, so its construction of each piece is audited by `WiringCompletenessTest` and
 * `SketchStorageHostSitesTest`, and this class drives the same classes with the same arguments.
 */
@OptIn(ExperimentalCoroutinesApi::class, DirectRepositoryWrite::class)
class WebStorageWiringTest {
    private fun probeResult(available: Boolean) = OpfsProbeResult(
        facts = OpfsProbeFacts(rootPresent = available, createWritable = available, moveVerified = available, copyVerifyVerified = available, sliceRead = available),
        quotaBytes = null,
        directory = "/stelekit-probe-wiring",
        files = emptyList(),
    )

    @Test
    fun probe_should_RunOncePerPageLoad() = runTest {
        var runs = 0
        val recorded = mutableListOf<OpfsProbeResult>()
        val fs = PlatformFileSystem()
        val probe = PageLoadProbe(
            probe = {
                runs++
                probeResult(available = false)
            },
            record = { result ->
                recorded += result
                fs.recordProbe(result)
            },
        )
        val scope = CoroutineScope(Dispatchers.Default)

        val job = assertNotNull(probe.startOnce(scope))
        assertNull(probe.startOnce(scope), "a second start in the same page load is a no-op")
        withContext(Dispatchers.Default) { withTimeout(AWAIT_MS) { job.join() } }

        assertEquals(1, runs)
        assertEquals(1, recorded.size)
        assertEquals(V2StorageStatus.Unavailable(StorageReason.NoOpfs), fs.storageStatus, "the probe result reaches the storage status")
    }

    @Test
    fun realProbe_should_FeedTheStorageStatusOfTheFileSystem() = runTest {
        val fs = PlatformFileSystem()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = assertNotNull(PageLoadProbe({ OpfsCapability().probe() }, fs::recordProbe).startOnce(scope))
        withContext(Dispatchers.Default) { withTimeout(AWAIT_MS) { job.join() } }
        // Checking while the preload has not run; Unavailable only where the browser lacks OPFS. Never a stale NotProbed Checking forever.
        val status = fs.storageStatus
        assertTrue(status is V2StorageStatus.Checking || status is V2StorageStatus.Unavailable, "no preload has run, so it is not Ready: $status")
    }

    @Test
    fun visibilityHidden_should_EmitOnlyForHidden() = runTest {
        val target = newVisibilityTarget()
        var emissions = 0
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { WasmPageLifecycle(target).hidden.collect { emissions++ } }

        fireVisibilityChange(target, "visible")
        assertEquals(0, emissions, "becoming visible is not a flush request")
        fireVisibilityChange(target, "hidden")
        assertEquals(1, emissions)
        collector.cancel()
        fireVisibilityChange(target, "hidden")
        assertEquals(1, emissions, "the listener is removed when the collector stops")
    }

    @Test
    fun visibilityHidden_should_FlushTheSaver() = runTest {
        val target = newVisibilityTarget()
        val sheet = SheetUuid("s2")
        val slug = "proj-0a1b2c"
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ScriptedSidecarStore()
        val repos = inMemorySketchRepositories()
        val project = SketchProject(ProjectUuid("p1"), slug, "Kitchen", 1L)
        val row = SketchSheet(sheet, project.uuid, "plan", "top", "in", 16, schemaVersion = SheetPaths.SCHEMA_V2)
        val loadedBytes = SheetCodecV2.encode(doc(sheet.value, "e0")).getOrNull()!!
        val bundle = ProjectBundle(project, listOf(row), emptyList(), emptyList(), mapOf(sheet to SheetPayload.V2Opaque.of(loadedBytes)))
        repos.projects.replaceProjectContents(project.uuid, bundle)
        store.files.putAll(SidecarCodec.encode(bundle))
        val strings = object : BannerStringSource {
            override fun summary(row: BannerRowId) = "Couldn't save."
            override fun shortTitle(row: BannerRowId) = "Not saved"
            override fun primaryLabel(row: BannerRowId) = "Retry"
            override fun moreItems(row: BannerRowId) = emptyList<String>()
        }
        val saver = SheetSaver(
            SheetTarget(slug, project.uuid, sheet, ContentHasher.sha256(loadedBytes)),
            SheetSaverPorts(repos, store, SidecarSlugLock(), InMemoryDiagnosticsPort(strings), lifecycle = WasmPageLifecycle(target)),
            context = dispatcher,
            encodeContext = dispatcher,
        )

        saver.submit(doc(sheet.value, "e0", "e1"))
        advanceTimeBy(100)
        assertEquals(0, store.writes.size, "inside the debounce window nothing is written")
        fireVisibilityChange(target, "hidden")
        runCurrent()

        assertEquals(1, store.writes.size, "a hidden page skips the debounce")
        advanceUntilIdle()
        saver.close()
    }

    @Test
    fun leaseAndDurabilityAndPersistence_should_BeConstructedInTheWasmRoot() = runTest {
        val graphId = "it-wiring-${Random.nextInt(0, Int.MAX_VALUE)}"
        val graph = "/stelekit/$graphId"
        val fs = PlatformFileSystem()
        fs.recordProbe(probeResult(available = true))
        fs.preload(graph)
        val lease = WasmSketchWriterLease(MutableStateFlow(graphId), fs::reloadSidecarRoot)
        val persistence: BrowserPersistence = WasmBrowserPersistence()
        val durability = FileSystemSketchDurability(fs, graphRoot = { graph })

        assertEquals(SketchWriterLeaseState.Held, lease.acquire())
        assertEquals(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false), fs.storageStatus)
        assertTrue(persistence.request() in PersistenceState.entries)
        val sheetPath = "${SidecarLiveSet.rootOf(graph)}/proj/sheets/s.v2.json"
        val modelPath = "${SidecarLiveSet.rootOf(graph)}/proj/${SidecarCodec.MODEL_PATH}"
        fs.writeFile(sheetPath, "{}")
        fs.writeFile(modelPath, "{}")
        assertIs<arrow.core.Either.Right<Unit>>(withContext(Dispatchers.Default) { withTimeout(AWAIT_MS) { durability.awaitPersisted("proj", "sheets/s.v2.json") } })
        withContext(Dispatchers.Default) { withTimeout(AWAIT_MS) { lease.state.first { it == SketchWriterLeaseState.Held } } }
        lease.release()
    }

    private fun doc(sheet: String, vararg ids: String): SketchDocumentV2 = SketchDocumentV2(
        scene = ids.foldIndexed(SketchSceneV2.empty()) { i, scene, id ->
            val rect = RectElement(
                ElementBase(ElementId(id), zKey = ZKey("a$id")),
                SceneUnit.ofInt(i * 20), SceneUnit.ofInt(0), SceneUnit.ofInt(10), SceneUnit.ofInt(10),
            )
            scene.add(rect).getOrNull()!!
        },
        sheetUuid = sheet,
    )

    private companion object {
        const val AWAIT_MS = 10_000L
    }
}
