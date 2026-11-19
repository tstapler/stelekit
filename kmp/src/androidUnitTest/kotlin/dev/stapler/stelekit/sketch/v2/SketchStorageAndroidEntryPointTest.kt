// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.sketch.v2

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
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
import dev.stapler.stelekit.wiring.FakePlatformPorts
import dev.stapler.stelekit.wiring.ObservableEffect
import dev.stapler.stelekit.wiring.WiredAppFixture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task 1.4.3q: the sidecar store the real `GraphContentViewModelSetup` provider builds on Android CONSULTS the writer
 * lease (it is not only passed through), and the browser-persistence port answers `Unsupported`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SketchStorageAndroidEntryPointTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Starts `Held`; [revoke] answers `HeldElsewhere` from then on. */
    private class RevocableLease : SketchWriterLease {
        private val mutable = MutableStateFlow(SketchWriterLeaseState.Held)
        override val state: StateFlow<SketchWriterLeaseState> = mutable
        override val epoch: Long = 0
        fun revoke() { mutable.value = SketchWriterLeaseState.HeldElsewhere }
        override suspend fun acquire(): SketchWriterLeaseState = mutable.value
        override fun release() = Unit
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dataDir: File = createTempDirectory("sketch_storage_android_").toFile()
    private val graphPath = "/test/sketch-storage-${System.nanoTime()}"
    private val fs = FakeFileSystem()
    private var fixture: WiredAppFixture? = null
    private val files = mapOf("project-model.json" to "{\"schema\":1,\"sheets\":[]}".encodeToByteArray())
    private val modelPath get() = "$graphPath/assets/stelekit-sketch/$SLUG/project-model.json"

    @After
    fun tearDown() {
        fixture?.let { runBlocking { it.close() } }
        dataDir.deleteRecursively()
    }

    private fun openStore(lease: SketchWriterLease?): ProjectSidecarStore {
        val ports = FakePlatformPorts(fileSystem = fs)
        val wired = runBlocking {
            when (lease) {
                null -> WiredAppFixture.android(context, dataDir, ports = ports, graphPath = graphPath)
                else -> WiredAppFixture.android(context, dataDir, ports = ports, graphPath = graphPath, sketchWriterLease = lease)
            }
        }
        fixture = wired
        var store: ProjectSidecarStore? = null
        composeRule.setContent {
            val vault = rememberGraphContentVaultSetup(wired.graphManager, ports.fileSystem, null)
            val io = rememberGraphContentGraphIoStack(
                wired.deps, vault.effectiveFileSystem, vault.activeGraphPath, vault.activeGraphInfo,
                dev.stapler.stelekit.logging.Logger("SketchStorageAndroidEntryPointTest"),
            )
            store = buildProjectBridgeDeps(wired.deps, vault.effectiveFileSystem, io).sidecarStore()
        }
        composeRule.waitUntil(WAIT_MS) { store != null }
        return assertNotNull(store)
    }

    @Test
    fun androidStore_should_WriteWhileHeld_AndRefuseOnceTheLeaseIsLost() {
        val lease = RevocableLease()
        val store = openStore(lease)

        ObservableEffect.assert("sketch-writer-lease-held-android") {
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
    fun androidDefaultLease_should_LetTheStoreWrite() {
        val store = openStore(null)
        assertEquals(SidecarWriteOutcome.Written, runBlocking { store.write(SLUG, files) })
        assertNotNull(fs.readFileBytes(modelPath))
    }

    @Test
    fun androidBrowserPersistence_should_AnswerUnsupported() {
        openStore(null)
        ObservableEffect.assert("browser-persistence-unsupported-android") {
            val persistence = fixture!!.deps.platformIntegrations.browserPersistence
            assertEquals(PersistenceState.Unsupported, runBlocking { persistence.request() })
        }
    }

    private companion object {
        const val SLUG = "proj-0a1b2c"
        const val WAIT_MS = 60_000L
    }
}
