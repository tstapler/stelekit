// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.PersistenceState
import dev.stapler.stelekit.platform.SketchWriterLease
import dev.stapler.stelekit.platform.SketchWriterLeaseState
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SidecarWriteOutcome
import dev.stapler.stelekit.ui.buildProjectBridgeDeps
import dev.stapler.stelekit.ui.rememberGraphContentGraphIoStack
import dev.stapler.stelekit.ui.rememberGraphContentVaultSetup
import dev.stapler.stelekit.wiring.DesktopWiredAppFixture
import dev.stapler.stelekit.wiring.FakePlatformPorts
import dev.stapler.stelekit.wiring.HeadlessComposition
import dev.stapler.stelekit.wiring.ObservableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Task 1.4.3q: the sidecar store the real `GraphContentViewModelSetup` provider builds on Desktop CONSULTS the writer
 * lease (it is not only passed through), and the browser-persistence port answers `Unsupported`.
 */
class SketchStorageDesktopEntryPointTest {
    /** Starts `Held`; [revoke] answers `HeldElsewhere` from then on, like a second tab taking the lock. */
    private class RevocableLease : SketchWriterLease {
        private val mutable = MutableStateFlow(SketchWriterLeaseState.Held)
        override val state: StateFlow<SketchWriterLeaseState> = mutable
        override val epoch: Long = 0
        fun revoke() { mutable.value = SketchWriterLeaseState.HeldElsewhere }
        override suspend fun acquire(): SketchWriterLeaseState = mutable.value
        override fun release() = Unit
    }

    private val dataDir: File = createTempDirectory("sketch_storage_desktop_").toFile()
    private val graphPath = "/test/sketch-storage-${System.nanoTime()}"
    private val fs = FakeFileSystem()
    private var fixture: DesktopWiredAppFixture? = null
    private var headless: HeadlessComposition? = null
    private val files = mapOf("project-model.json" to "{\"schema\":1,\"sheets\":[]}".encodeToByteArray())

    @AfterTest
    fun tearDown() {
        headless?.close()
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    /** Builds the store through the same provider `GraphContent` uses; [lease] null keeps the fixture's default. */
    private fun openStore(lease: SketchWriterLease?): ProjectSidecarStore {
        val ports = FakePlatformPorts(fileSystem = fs)
        val wired = runBlocking {
            when (lease) {
                null -> DesktopWiredAppFixture.desktop(dataDir, ports, graphPath = graphPath)
                else -> DesktopWiredAppFixture.desktop(dataDir, ports, graphPath = graphPath, sketchWriterLease = lease)
            }
        }
        fixture = wired
        var store: ProjectSidecarStore? = null
        headless = HeadlessComposition().also { composition ->
            composition.setContent {
                val vault = rememberGraphContentVaultSetup(wired.graphManager, ports.fileSystem, null)
                val io = rememberGraphContentGraphIoStack(
                    wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
                    dev.stapler.stelekit.logging.Logger("SketchStorageDesktopEntryPointTest"),
                )
                store = buildProjectBridgeDeps(wired.deps, vault.effectiveFileSystem, io).sidecarStore()
            }
        }
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (store == null) {
            if (System.nanoTime() > deadline) fail("the sidecar store provider never produced a store")
            headless!!.advance()
            Thread.yield()
        }
        return assertNotNull(store)
    }

    private val modelPath get() = "$graphPath/assets/stelekit-sketch/$SLUG/project-model.json"

    @Test
    fun desktopStore_should_WriteWhileHeld_AndRefuseOnceTheLeaseIsLost() {
        val lease = RevocableLease()
        val store = openStore(lease)

        ObservableEffect.assert("sketch-writer-lease-held-desktop") {
            assertEquals(SidecarWriteOutcome.Written, runBlocking { store.write(SLUG, files) })
            val before = assertNotNull(fs.readFileBytes(modelPath), "the held lease let the model reach the file system")
            lease.revoke()
            val changed = files + ("project-model.json" to "{\"schema\":1,\"sheets\":[]} ".encodeToByteArray())
            val refused = assertIs<SidecarWriteOutcome.Failed>(runBlocking { store.write(SLUG, changed) })
            assertIs<DomainError.FileSystemError.WriterLeaseNotHeld>(refused.error)
            assertTrue(before.contentEquals(assertNotNull(fs.readFileBytes(modelPath))), "the refused write changed nothing")
        }
    }

    @Test
    fun desktopDefaultLease_should_LetTheStoreWrite() {
        val store = openStore(null)
        assertEquals(SidecarWriteOutcome.Written, runBlocking { store.write(SLUG, files) })
        assertNotNull(fs.readFileBytes(modelPath))
    }

    @Test
    fun desktopBrowserPersistence_should_AnswerUnsupported() {
        openStore(null)
        ObservableEffect.assert("browser-persistence-unsupported-desktop") {
            val persistence = fixture!!.deps.platformIntegrations.browserPersistence
            assertEquals(PersistenceState.Unsupported, runBlocking { persistence.request() })
        }
    }

    private companion object {
        const val SLUG = "proj-0a1b2c"
        const val TIMEOUT_NANOS = 60_000_000_000L
    }
}
