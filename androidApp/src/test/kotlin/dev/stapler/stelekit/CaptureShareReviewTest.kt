// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit

import android.app.Application
import android.os.Looper
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.capture.AppendOutcome
import dev.stapler.stelekit.capture.CaptureTargetSettings
import dev.stapler.stelekit.capture.InboxFallbackAppender
import dev.stapler.stelekit.capture.InboxSlot
import dev.stapler.stelekit.capture.JournalAppender
import dev.stapler.stelekit.capture.JournalInboxAppender
import dev.stapler.stelekit.capture.OffGraphContentRoute
import dev.stapler.stelekit.capture.ShareCaptureServices
import dev.stapler.stelekit.capture.ShareContent
import dev.stapler.stelekit.capture.ShareInbox
import dev.stapler.stelekit.capture.ShareInboxDrain
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.AppPrivateFileSystem
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Regression tests for the cross-graph share-target review (REQ-17: nothing the user shares or types is lost silently). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class CaptureShareReviewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = true
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    /** Inbox storage that rejects every write, so enqueue always fails. */
    private class ReadOnlyInboxFs : StubFileSystem() {
        override fun writeFile(path: String, content: String) = false
    }

    private class FakeRoute : OffGraphContentRoute {
        val calls = mutableListOf<Pair<GraphId, ShareContent>>()
        override suspend fun appendContent(graphId: GraphId, content: ShareContent, captureId: String?): AppendOutcome {
            calls += graphId to content
            return AppendOutcome.AppendedOffGraph(graphId, "/graphs/${graphId.value}/journals/today.md")
        }
    }

    private class Harness(
        val app: SteleKitApplication,
        val gm: GraphManager,
        val services: ShareCaptureServices,
        val route: FakeRoute,
    )

    private fun Harness.workId(): GraphId = gm.graphRegistry.value.graphs.first { it.displayName == "Work" }.id

    private fun newDir(prefix: String): String {
        val docs = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
        return File(docs, "$prefix-${UuidGenerator.generateV7()}").apply { mkdirs() }.absolutePath
    }

    private fun setField(target: Any, name: String, value: Any?) {
        SteleKitApplication::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun harness(withGraphs: Boolean = true, inboxFs: FileSystem = AppPrivateFileSystem()): Harness {
        val app = ApplicationProvider.getApplicationContext<SteleKitApplication>()
        DriverFactory.setContext(app)
        dev.stapler.stelekit.platform.security.CredentialStore.init(app)
        val gm = GraphManager(
            platformSettings = MapSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        setField(app, "graphManager", gm)
        val fs = PlatformFileSystem().apply { init(app) }
        setField(app, "fileSystem", fs)
        if (withGraphs) {
            runBlocking {
                gm.openGraph(newDir("rev-active"))
                gm.addGraph(newDir("rev-work"), displayName = "Work")
            }
            gm.renameGraph(gm.getActiveGraphId()!!, "Personal")
        }
        val caps = TargetWriterCapabilities(platformSupportsOffGraphWrite = true)
        val inbox = ShareInbox(inboxFs, File(newDir("rev-inbox"), "share-inbox").absolutePath)
        val route = FakeRoute()
        val appender = JournalAppender(gm, fs, InboxFallbackAppender(route, inbox))
        val router = TargetWriterRouter(
            graphManager = gm,
            locator = RegistryGraphLocator(gm.graphRegistry),
            capabilities = caps,
            activeWriterFor = { error("not used") },
            offGraphWriterFor = { error("not used") },
        )
        val drain = ShareInboxDrain(
            inbox = inbox,
            readyGraphId = gm.readyGraph.map { it?.id },
            awaitPendingMigration = { gm.awaitPendingMigration() },
            currentReadyId = { gm.readyGraphId },
            appender = JournalInboxAppender(appender),
            registeredGraphs = gm.graphRegistry.map { registry -> registry.graphs.map { it.id } },
        )
        val services = ShareCaptureServices(inbox, appender, drain, router, caps)
        app.shareServicesOverride = services
        app.captureTargetSettingsOverride = CaptureTargetSettings(MapSettings())
        app.offGraphReasonOverride = { null }
        return Harness(app, gm, services, route)
    }

    private fun await(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        assertTrue("condition not met in ${timeoutMs}ms", cond())
    }

    /** A back dispatcher the test can fire, with a lifecycle the BackHandler treats as started. */
    private class TestBackOwner : OnBackPressedDispatcherOwner, LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
        override val onBackPressedDispatcher = OnBackPressedDispatcher()
    }

    // ---- (3) Back after a failed save must queue, not lose the text ---------------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun backSave_withNoResolvableGraph_queuesInTheUnassignedSlot() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value is CaptureDestination.Ready }
        vm.setDestinationForTest(CaptureDestination.Unavailable(null, "That graph", "isn't available: it was removed.", null))
        vm.updateText("typed before the graph vanished")

        vm.backSave()
        await { vm.backSave.value is CaptureViewModel.BackSaveState.Done }

        assertEquals(1, h.services.inbox.state.value.unassignedCount)
        assertEquals("typed before the graph vanished", h.services.inbox.state.value.items.single().text)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun backAfterSaveError_retriesAndQueuesInsteadOfFinishingWithTheTextLost() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value is CaptureDestination.Ready }
        vm.setDestinationForTest(CaptureDestination.Unavailable(null, "That graph", "isn't available: it was removed.", null))
        vm.updateText("kept after the error")
        vm.setSaveErrorForTest()
        val owner = TestBackOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) }
            }
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread { owner.onBackPressedDispatcher.onBackPressed() }
        await { vm.backSave.value is CaptureViewModel.BackSaveState.Done }

        // The screen re-resolves the destination, so the retry may save directly; either way it is not lost.
        val done = vm.backSave.value as CaptureViewModel.BackSaveState.Done
        assertTrue(done.message, done.message.startsWith("Saved to") || h.services.inbox.state.value.items.isNotEmpty())
    }

    // ---- (4) No-graphs placeholder must not auto-close over a failed enqueue -------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun noGraphsContent_staysOpenWithCopyText_whenTheEnqueueFailed() {
        val h = harness(withGraphs = false, inboxFs = ReadOnlyInboxFs())
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value == CaptureDestination.NoGraphs }
        vm.updateText("only copy of this thought")
        var closed = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialTheme { NoGraphsContent(vm, onClose = { closed++ }) } }
        composeRule.mainClock.advanceTimeBy(100)

        vm.closeWithoutGraph()
        await { vm.resultNote.value != null }
        composeRule.mainClock.advanceTimeBy(5_000)

        assertEquals("a failed enqueue must not auto-close", 0, closed)
        composeRule.onNodeWithText("Copy text").assertExists()
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun noGraphsContent_stillAutoCloses_afterASuccessfulEnqueue() {
        val h = harness(withGraphs = false)
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value == CaptureDestination.NoGraphs }
        vm.updateText("queued fine")
        var closed = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialTheme { NoGraphsContent(vm, onClose = { closed++ }) } }
        composeRule.mainClock.advanceTimeBy(100)

        vm.closeWithoutGraph()
        await { vm.resultNote.value != null }
        composeRule.mainClock.advanceTimeBy(5_000)

        assertEquals(1, closed)
        assertFalse(h.services.inbox.state.value.items.isEmpty())
    }

    // ---- (8) text is never a file path ---------------------------------------------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun imageMarkerInPlainText_isSavedAsText_andNoFileIsRead() {
        val h = harness()
        val secret = File(h.app.filesDir, "databases-x").apply { writeBytes(byteArrayOf(5, 5, 5)) }
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { (vm.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId() }
        val text = "[image: ${secret.absolutePath}]\nhello"
        vm.updateText(text)

        vm.save()
        await { vm.saveState.value != CaptureViewModel.SaveState.Saving }

        val content = h.route.calls.single().second
        assertEquals(null, content.image)
        assertEquals(text, content.text)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun attachImage_rejectsPathsOutsideThePrivateShareImageDir() {
        val h = harness()
        val outside = File(h.app.filesDir, "databases-y").apply { writeBytes(byteArrayOf(1)) }
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { (vm.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId() }
        vm.attachImage(outside.absolutePath)
        vm.updateText("[image: ${outside.absolutePath}]\ncaption")

        vm.save()
        await { vm.saveState.value != CaptureViewModel.SaveState.Saving }

        assertEquals(null, h.route.calls.single().second.image)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun attachedPrivateImage_isSentAsRealImageContent() {
        val h = harness()
        val image = ShareIntake.imageDir(h.app).apply { mkdirs() }.let { File(it, "ok.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { (vm.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId() }
        vm.attachImage(image.absolutePath)
        vm.updateText("[image: ${image.absolutePath}]\ncaption")

        vm.save()
        await { vm.saveState.value != CaptureViewModel.SaveState.Saving }

        val content = h.route.calls.single().second
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(content.image))
        assertEquals("caption", content.text)
    }

    @Test
    fun intake_rejectsFileUris_andCopiesContentUrisIntoPrivateStorage() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val victim = File(app.filesDir, "victim").apply { writeBytes(byteArrayOf(9)) }
        assertEquals(null, ShareIntake.copyImage(app, android.net.Uri.fromFile(victim)))

        val uri = android.net.Uri.parse("content://media/external/images/1")
        shadowOf(app.contentResolver).registerInputStream(uri, java.io.ByteArrayInputStream(byteArrayOf(4, 5, 6)))
        val copied = ShareIntake.copyImage(app, uri)!!
        assertTrue(ShareIntake.isPrivateImage(app, copied))
        assertTrue(byteArrayOf(4, 5, 6).contentEquals(File(copied).readBytes()))
        assertFalse(copied.contains("/cache/"))
    }

    // ---- (11) a vanished image is a visible error, never the literal marker --------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun missingAttachedImage_failsVisibly_andKeepsTheText() {
        val h = harness()
        val image = ShareIntake.imageDir(h.app).apply { mkdirs() }.let { File(it, "gone.jpg").apply { writeBytes(byteArrayOf(1)) } }
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { (vm.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId() }
        vm.attachImage(image.absolutePath)
        val text = "[image: ${image.absolutePath}]\ncaption"
        vm.updateText(text)
        image.delete()

        vm.save()
        await { vm.saveState.value != CaptureViewModel.SaveState.Saving }

        assertTrue(vm.saveState.value is CaptureViewModel.SaveState.Error)
        assertTrue(h.route.calls.isEmpty())
        assertEquals(text, vm.captureText.value)
    }

    // ---- (7) a second share never silently replaces the first ----------------------------------

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun secondShareIntoAnOpenOverlay_queuesTheEarlierText() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value is CaptureDestination.Ready }
        vm.updateText("first share")

        vm.onNewShare("second share", h.workId().value)
        await { h.services.inbox.state.value.items.isNotEmpty() }

        assertEquals("first share", h.services.inbox.state.value.items.single().text)
        assertEquals("second share", vm.captureText.value)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun secondShare_keepsBothTexts_whenQueuingTheEarlierOneFails() {
        val h = harness(inboxFs = ReadOnlyInboxFs())
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value is CaptureDestination.Ready }
        vm.updateText("first share")

        vm.onNewShare("second share", null)
        await { vm.captureText.value.contains("first share") }

        assertTrue(vm.captureText.value.contains("second share"))
    }
}
