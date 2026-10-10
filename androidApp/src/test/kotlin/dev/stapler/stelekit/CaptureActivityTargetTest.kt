// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.capture.CaptureTargetSettings
import dev.stapler.stelekit.capture.AppendOutcome
import dev.stapler.stelekit.capture.InboxFallbackAppender
import dev.stapler.stelekit.capture.InboxSlot
import dev.stapler.stelekit.capture.JournalAppender
import dev.stapler.stelekit.capture.JournalInboxAppender
import dev.stapler.stelekit.capture.OffGraphContentRoute
import dev.stapler.stelekit.capture.ShareContent
import dev.stapler.stelekit.capture.ShareInbox
import dev.stapler.stelekit.capture.ShareInboxDrain
import dev.stapler.stelekit.capture.OffGraphCapture
import dev.stapler.stelekit.capture.ShareCaptureServices
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.db.RegistryGraphLocator
import dev.stapler.stelekit.merge.TargetWriterCapabilities
import dev.stapler.stelekit.merge.TargetWriterRouter
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.model.GraphInfo
import dev.stapler.stelekit.model.GraphRegistry
import dev.stapler.stelekit.platform.AppPrivateFileSystem
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * Stories 4.2.1 and 4.2.2: destination row, override, unavailable and checking states, Back
 * auto-save (success, failure queues, empty), toast, and Direct Share extra handling. UI cases use a
 * plain Application and a test seam on the ViewModel; save cases run a real in-memory GraphManager
 * with a real second graph on disk, written through the one TargetWriterRouter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
class CaptureActivityTargetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val work = GraphChoice(GraphId("g-work"), "Work")
    private val personal = GraphChoice(GraphId("g-personal"), "Personal")

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun plainVm() = CaptureViewModel(ApplicationProvider.getApplicationContext())

    // ---- pure destination resolution ----------------------------------------------------------

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private fun info(c: GraphChoice) = GraphInfo(c.id, "/graphs/${c.id.value}", c.name, addedAt = 0L)

    private fun registry(active: GraphChoice, vararg all: GraphChoice) =
        GraphRegistry(active.id, all.map(::info))

    @Test
    fun resolve_defaultWorkLastPersonalRememberOn_picksPersonal() {
        val settings = CaptureTargetSettings(MapSettings()).apply {
            defaultGraphId = work.id
            recordLastUsed(personal.id)
            rememberLast = true
        }
        val dest = CaptureDestinations.resolve(
            DestinationInputs(registry(work, work, personal), settings, activeOpen = true),
        ) { null }
        assertEquals(personal, (dest as CaptureDestination.Ready).graph)
        assertFalse(dest.isActive)
    }

    @Test
    fun resolve_unavailableTarget_offersFallbackAndQueue() {
        val settings = CaptureTargetSettings(MapSettings()).apply { defaultGraphId = personal.id }
        val dest = CaptureDestinations.resolve(
            DestinationInputs(registry(work, work, personal), settings, activeOpen = true),
        ) { "SteleKit no longer has permission to write to this graph's folder." }
        dest as CaptureDestination.Unavailable
        assertEquals(personal.id, dest.graphId)
        assertEquals(work, dest.fallback)
        assertTrue(dest.reason.startsWith("Personal graph isn't available"))
    }

    @Test
    fun resolve_activeGraphNotOpenYet_isCheckingThenUnavailableAfterCap() {
        val settings = CaptureTargetSettings(MapSettings())
        val inputs = DestinationInputs(registry(work, work, personal), settings, activeOpen = false)
        assertEquals(CaptureDestination.Checking, CaptureDestinations.resolve(inputs) { null })
        val expired = CaptureDestinations.resolve(inputs.copy(checkingExpired = true)) { null }
        assertTrue(expired is CaptureDestination.Unavailable)
        assertEquals(personal, (expired as CaptureDestination.Unavailable).fallback)
    }

    @Test
    fun resolve_directShareOverrideForRemovedGraph_isUnavailable() {
        val dest = CaptureDestinations.resolve(
            DestinationInputs(
                registry(work, work), CaptureTargetSettings(MapSettings()),
                overrideId = GraphId("deleted"), activeOpen = true,
            ),
        ) { null }
        assertTrue(dest is CaptureDestination.Unavailable)
        assertEquals(work, (dest as CaptureDestination.Unavailable).fallback)
    }

    @Test
    fun resolve_noGraphs() {
        val dest = CaptureDestinations.resolve(
            DestinationInputs(GraphRegistry(), CaptureTargetSettings(MapSettings())),
        ) { null }
        assertEquals(CaptureDestination.NoGraphs, dest)
    }

    // ---- destination row (Compose) -------------------------------------------------------------

    @Test
    fun row_readyDestination_textAndContentDescriptionMatchTheAc() {
        val vm = plainVm()
        vm.setDestinationForTest(CaptureDestination.Ready(personal, isActive = false), listOf(work, personal))
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Saving to Personal graph - Today's Journal").assertExists()
        composeRule.onNodeWithContentDescription("Saving to Personal graph. Double-tap to change").assertExists()
        composeRule.onAllNodesWithText("Today's Journal").assertCountEquals(0)
    }

    // Chip and row taps use the node's OnClick action, not performClick(): the sheet is a Surface with
    // a non-uniform RoundedCornerShape (rounded top, square bottom); Compose hit-tests such an outline
    // through Path.op, which Robolectric's Path does not implement, so injected touches inside the sheet
    // fall through to the scrim. A uniform shape or RectangleShape delivers them (verified); real
    // devices use the native Path and are unaffected.
    @Test
    fun row_tapOpensInlineChipsAndSelectionKeepsTypedText() {
        val vm = plainVm()
        vm.updateText("typed before opening the menu")
        vm.setDestinationForTest(CaptureDestination.Ready(personal, isActive = false), listOf(work, personal))
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }

        composeRule.onNodeWithTag(DESTINATION_ROW_TEST_TAG).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(graphChipTestTag(work.id)).assertExists()
        composeRule.onNodeWithTag(graphChipTestTag(personal.id)).assertExists()
        assertTrue(vm.menuOpen.value)

        composeRule.onNodeWithTag(graphChipTestTag(work.id)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertFalse(vm.menuOpen.value)
        assertEquals("typed before opening the menu", vm.captureText.value)
        composeRule.onNodeWithText("typed before opening the menu").assertExists()
    }

    @Test
    fun row_moreThanFourGraphs_usesDropdownMenuInsteadOfChips() {
        val vm = plainVm()
        val five = (1..5).map { GraphChoice(GraphId("g$it"), "G$it") }
        vm.setDestinationForTest(CaptureDestination.Ready(five[0], isActive = false), five)
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }

        composeRule.onNodeWithTag(DESTINATION_ROW_TEST_TAG).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        composeRule.waitForIdle()
        five.forEach { composeRule.onNodeWithTag(graphChipTestTag(it.id)).assertExists() }
        composeRule.onNodeWithTag(graphChipTestTag(five[3].id)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertFalse(vm.menuOpen.value)
    }

    @Test
    fun nonActiveTarget_showsLinkSuggestionNoteAndNoChips() {
        val vm = plainVm()
        vm.updateText("hello")
        vm.setDestinationForTest(CaptureDestination.Ready(work, isActive = false), listOf(work, personal))
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(OffGraphCapture.LINK_SUGGESTIONS_NOTE).assertExists()
    }

    @Test
    fun activeTarget_hidesLinkSuggestionNote() {
        val vm = plainVm()
        vm.setDestinationForTest(CaptureDestination.Ready(work, isActive = true), listOf(work, personal))
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(OffGraphCapture.LINK_SUGGESTIONS_NOTE).assertCountEquals(0)
    }

    @Test
    fun unavailableState_keepsTextVisibleAndOffersThreeActions() {
        val vm = plainVm()
        vm.updateText("shared text that must not vanish")
        vm.setDestinationForTest(
            CaptureDestination.Unavailable(
                personal.id, "Personal graph", "Personal graph isn't available: folder access was revoked.", work,
            ),
            listOf(work, personal),
        )
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Personal graph isn't available: folder access was revoked.").assertExists()
        composeRule.onNodeWithText("shared text that must not vanish").assertExists()
        composeRule.onNodeWithText("Save to Work graph").assertExists()
        composeRule.onNodeWithText("Retry").assertExists()
        composeRule.onNodeWithText("Queue for later").assertExists()
        composeRule.onAllNodesWithText("Save").assertCountEquals(0)
    }

    @Test
    fun checkingState_showsSpinnerRowAndDisablesSave() {
        val vm = plainVm()
        vm.updateText("typing is never blocked")
        vm.setDestinationForTest(CaptureDestination.Checking, emptyList())
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Saving to... (checking)").assertExists()
        composeRule.onNodeWithText("Checking destination...").assertIsNotEnabled()
        vm.updateText("typing is never blocked, still")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("typing is never blocked, still").assertExists()
    }

    @Test
    fun readyState_saveEnabled() {
        val vm = plainVm()
        vm.updateText("hello")
        vm.setDestinationForTest(CaptureDestination.Ready(work, isActive = true), listOf(work))
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun savedState_rowAnnouncesAddedToGraph_thenFinishesAfterTheConfirmation() {
        val vm = plainVm()
        vm.updateText("hello")
        vm.setDestinationForTest(CaptureDestination.Ready(work, isActive = true), listOf(work))
        var finished = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = { finished++ }, onDismiss = {}) } }
        vm.setSavedForTest("Added to Work graph")
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithContentDescription("Added to Work graph").assertExists()
        assertEquals(0, finished)

        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, finished)
    }

    @Test
    fun menuOpen_pausesAutoFinishUntilClosed() {
        val vm = plainVm()
        vm.updateText("hello")
        vm.setDestinationForTest(CaptureDestination.Ready(work, isActive = true), listOf(work, personal))
        var finished = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = { finished++ }, onDismiss = {}) } }
        vm.setMenuOpen(true)
        vm.setSavedForTest("Added to Work graph")
        composeRule.mainClock.advanceTimeBy(5_000)
        assertEquals("timer must not run while the menu is open", 0, finished)

        vm.setMenuOpen(false)
        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, finished)
    }

    @Test
    fun backSaveToast_namesGraphAndOffersUndoAndChange() {
        val vm = plainVm()
        vm.setDestinationForTest(CaptureDestination.Ready(personal, isActive = false), listOf(work, personal))
        val record = RecentCapture("cap-1", personal.id.value, "Personal graph", 0L, "note", "2026_10_09")
        vm.setBackSaveForTest(CaptureViewModel.BackSaveState.Done("Saved to Personal graph", record, queued = false))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.mainClock.advanceTimeBy(100)

        composeRule.onNodeWithText("Saved to Personal graph").assertExists()
        composeRule.onNodeWithText("Undo").assertExists()
        composeRule.onNodeWithText("Change").assertExists()
    }

    @Test
    fun queuedBackSaveToast_hasNoUndoOrChange() {
        val vm = plainVm()
        vm.setDestinationForTest(CaptureDestination.Ready(work, isActive = false), listOf(work))
        vm.setBackSaveForTest(
            CaptureViewModel.BackSaveState.Done("Couldn't save to Work graph. Queued for Work graph.", null, queued = true),
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = {}, onDismiss = {}) } }
        composeRule.mainClock.advanceTimeBy(100)

        composeRule.onNodeWithText("Couldn't save to Work graph. Queued for Work graph.").assertExists()
        composeRule.onAllNodesWithText("Undo").assertCountEquals(0)
    }

    @Test
    fun backSaveToast_autoFinishesAfterTheToastWindow() {
        val vm = plainVm()
        vm.setBackSaveForTest(CaptureViewModel.BackSaveState.Done("Saved to Work graph", null, queued = false))
        var finished = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MaterialTheme { CaptureScreen(vm, onSaved = { finished++ }, onDismiss = {}) } }
        composeRule.mainClock.advanceTimeBy(4_000)
        assertEquals(0, finished)
        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals(1, finished)
    }

    // ---- Direct Share extra handling (Story 4.2.2) ---------------------------------------------

    @Test
    fun directShare_extraOverridesAndShortcutIdIsAFallback() {
        val explicit = Intent().putExtra(ShareShortcutPublisher.EXTRA_TARGET_GRAPH_ID, "abc")
        assertEquals("abc", ShareShortcutPublisher.targetGraphIdFrom(explicit))

        val viaChooser = Intent().putExtra(Intent.EXTRA_SHORTCUT_ID, "graph:def")
        assertEquals("def", ShareShortcutPublisher.targetGraphIdFrom(viaChooser))

        assertNull(ShareShortcutPublisher.targetGraphIdFrom(Intent()))
        assertNull(ShareShortcutPublisher.targetGraphIdFrom(Intent().putExtra(Intent.EXTRA_SHORTCUT_ID, "other:x")))
    }

    @Test
    fun directShare_publishesOneShortcutPerGraphAndReplacesStaleOnes() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ShareShortcutPublisher.publish(ctx, listOf(info(work), info(personal)))
        val first = androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(ctx)
        assertEquals(setOf("Work graph", "Personal graph"), first.map { it.shortLabel.toString() }.toSet())
        assertTrue(first.all { it.intent.getStringExtra(ShareShortcutPublisher.EXTRA_TARGET_GRAPH_ID) != null })

        ShareShortcutPublisher.publish(ctx, listOf(info(work)))
        val second = androidx.core.content.pm.ShortcutManagerCompat.getDynamicShortcuts(ctx)
        assertEquals(listOf("graph:g-work"), second.map { it.id })
    }

    // ---- real GraphManager: save, override, Back, inbox --------------------------------------

    private class StubSettings : Settings {
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

    /** Records off-graph appends; the real router needs the storage-location DB, which Robolectric lacks. */
    private class FakeRoute : OffGraphContentRoute {
        class Call(val graphId: GraphId, val text: String, val captureId: String?)

        val calls = mutableListOf<Call>()

        override suspend fun appendContent(graphId: GraphId, content: ShareContent, captureId: String?): AppendOutcome {
            if (calls.any { it.captureId == captureId && it.graphId == graphId }) return AppendOutcome.AlreadyPresent
            calls += Call(graphId, content.text, captureId)
            return AppendOutcome.AppendedOffGraph(graphId, "/graphs/${graphId.value}/journals/today.md")
        }
    }

    private class Harness(
        val app: SteleKitApplication,
        val gm: GraphManager,
        val services: ShareCaptureServices,
        val route: FakeRoute,
        val settings: CaptureTargetSettings,
        val workDir: String,
    )

    private fun field(target: Any, name: String, value: Any?) {
        SteleKitApplication::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun newDir(prefix: String): String {
        val docs = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
        return File(docs, "$prefix-${UuidGenerator.generateV7()}").apply { mkdirs() }.absolutePath
    }

    /** Personal is the open graph; Work is registered but not open. */
    private fun harness(withGraphs: Boolean = true): Harness {
        val app = ApplicationProvider.getApplicationContext<SteleKitApplication>()
        DriverFactory.setContext(app)
        dev.stapler.stelekit.platform.security.CredentialStore.init(app)
        val gm = GraphManager(
            platformSettings = StubSettings(),
            driverFactory = DriverFactory(),
            fileSystem = StubFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        field(app, "graphManager", gm)
        val fs = PlatformFileSystem().apply { init(app) }
        field(app, "fileSystem", fs)

        val workDir = newDir("cap-work")
        if (withGraphs) {
            runBlocking {
                gm.openGraph(newDir("cap-active"))
                gm.addGraph(workDir, displayName = "Work")
            }
            gm.renameGraph(gm.getActiveGraphId()!!, "Personal")
        }
        val caps = TargetWriterCapabilities(platformSupportsOffGraphWrite = true)
        val inbox = ShareInbox(AppPrivateFileSystem(), File(newDir("cap-inbox"), "share-inbox").absolutePath)
        val route = FakeRoute()
        val appender = JournalAppender(gm, fs, InboxFallbackAppender(route, inbox))
        val locator = RegistryGraphLocator(gm.graphRegistry)
        val router = TargetWriterRouter(
            graphManager = gm,
            locator = locator,
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
        val settings = CaptureTargetSettings(StubSettings())
        app.shareServicesOverride = services
        app.captureTargetSettingsOverride = settings
        app.offGraphReasonOverride = { null }
        return Harness(app, gm, services, route, settings, workDir)
    }

    private fun Harness.workId(): GraphId = gm.graphRegistry.value.graphs.first { it.displayName == "Work" }.id

    private fun await(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            idle()
            Thread.sleep(10)
        }
        assertTrue("condition not met in ${timeoutMs}ms", cond())
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun selectGraph_updatesLastGraphOnly_andSaveAppendsThereWithConfirmation() {
        val h = harness()
        h.settings.defaultGraphId = h.gm.getActiveGraphId()
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value is CaptureDestination.Ready }

        vm.selectGraph(h.workId())
        assertEquals(h.workId(), h.settings.lastGraphId)
        assertEquals("default must not change", h.gm.getActiveGraphId(), h.settings.defaultGraphId)
        await { (vm.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId() }

        vm.updateText("note for the work graph")
        vm.save()
        await { vm.saveState.value != CaptureViewModel.SaveState.Saving }

        assertEquals(CaptureViewModel.SaveState.Saved, vm.saveState.value)
        assertEquals("Added to Work graph", vm.resultNote.value)
        val call = h.route.calls.single()
        assertEquals(h.workId(), call.graphId)
        assertEquals("note for the work graph", call.text)
        assertEquals(vm.captureId, call.captureId)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun saveToTheOpenGraph_stillGoesThroughTheActiveGraphPath() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        await { (vm.destination.value as? CaptureDestination.Ready)?.isActive == true }
        vm.updateText("goes to the open graph")
        vm.save()
        await { vm.saveState.value != CaptureViewModel.SaveState.Saving }

        assertEquals(CaptureViewModel.SaveState.Saved, vm.saveState.value)
        assertEquals("Added to Personal graph", vm.resultNote.value)
        assertTrue(h.route.calls.isEmpty())
        assertEquals(vm.captureId, vm.savedContextForTest!!.block.uuid.value)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun directShareOverride_routesThatShareOnly_andLeavesLastGraphAlone() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { (vm.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId() }

        vm.updateText("direct share note")
        vm.save()
        await { vm.saveState.value != CaptureViewModel.SaveState.Saving }

        assertEquals(CaptureViewModel.SaveState.Saved, vm.saveState.value)
        assertEquals(h.workId(), h.route.calls.single().graphId)
        assertNull("an override must not become the remembered graph", h.settings.lastGraphId)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun namedTargetWorks_whenNoGraphIsActive() {
        val h = harness()
        h.gm.removeGraph(h.gm.getActiveGraphId()!!)
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { (vm.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId() }
        vm.updateText("no active graph")
        vm.save()
        await { vm.saveState.value != CaptureViewModel.SaveState.Saving }
        assertEquals(CaptureViewModel.SaveState.Saved, vm.saveState.value)
        assertEquals(h.workId(), h.route.calls.single().graphId)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun redeliveryWithSameCaptureId_showsAlreadyAdded() {
        val h = harness()
        val first = CaptureViewModel(h.app)
        first.beginShare(h.workId().value)
        await { ((first.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId()) }
        first.updateText("delivered once")
        first.save()
        await { first.saveState.value != CaptureViewModel.SaveState.Saving }

        val restored = CaptureViewModel(h.app)
        restored.beginShare(h.workId().value, restoredCaptureId = first.captureId)
        await { ((restored.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId()) }
        restored.updateText("delivered once")
        restored.save()
        await { restored.saveState.value != CaptureViewModel.SaveState.Saving }

        assertEquals("Already added", restored.resultNote.value)
        assertEquals(1, h.route.calls.size)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun backWithText_autoSavesToShownDestination_andRecordsIt() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { ((vm.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId()) }
        vm.updateText("saved on back")

        vm.backSave()
        await { vm.backSave.value is CaptureViewModel.BackSaveState.Done }

        val done = vm.backSave.value as CaptureViewModel.BackSaveState.Done
        assertEquals("Saved to Work graph", done.message)
        assertFalse(done.queued)
        assertEquals("saved on back", h.route.calls.single().text)
        assertEquals(vm.captureId, h.route.calls.single().captureId)
        val record = h.app.recentCaptures.all().first()
        assertEquals(vm.captureId, record.captureId)
        assertEquals("Work graph", record.graphName)
        assertNull("an override-only share must not change last graph", h.settings.lastGraphId)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun backSaveToTheOpenGraph_undoRemovesTheBlockAndDoesNotTouchLastGraph() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        await { (vm.destination.value as? CaptureDestination.Ready)?.isActive == true }
        vm.updateText("undo me")

        vm.backSave()
        await { vm.backSave.value is CaptureViewModel.BackSaveState.Done }
        val lastAfterSave = h.settings.lastGraphId
        assertEquals("last_graph follows a successful save", h.gm.getActiveGraphId(), lastAfterSave)
        val repo = h.gm.getActiveRepositorySet()!!.blockRepository
        val uuid = dev.stapler.stelekit.model.BlockUuid(vm.captureId)
        assertNotNull(runBlocking { repo.getBlockByUuid(uuid).first().getOrNull() })

        var undone: Boolean? = null
        vm.undoBackSave { undone = it }
        await { undone != null }

        assertEquals(true, undone)
        assertNull(runBlocking { repo.getBlockByUuid(uuid).first().getOrNull() })
        assertTrue(h.app.recentCaptures.all().first().undone)
        assertEquals("Undo leaves last_graph alone", lastAfterSave, h.settings.lastGraphId)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun backWithUnwritableDestination_queuesAndSaysSo() {
        val h = harness()
        h.app.offGraphReasonOverride = { "SteleKit no longer has permission to write to this graph's folder." }
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { vm.destination.value is CaptureDestination.Unavailable }
        vm.updateText("must not be lost")

        vm.backSave()
        await { vm.backSave.value is CaptureViewModel.BackSaveState.Done }

        val done = vm.backSave.value as CaptureViewModel.BackSaveState.Done
        assertEquals("Couldn't save to Work graph. Queued for Work graph.", done.message)
        assertTrue(done.queued)
        assertNull(done.record)
        val queued = h.services.inbox.state.value.items.single()
        assertEquals(InboxSlot.Graph(h.workId()), queued.slot)
        assertEquals("must not be lost", queued.text)
        assertTrue(h.route.calls.isEmpty())
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun backWithBlankText_doesNothing() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value is CaptureDestination.Ready }
        vm.updateText("   \n ")
        vm.backSave()
        idle()
        assertEquals(CaptureViewModel.BackSaveState.None, vm.backSave.value)
        assertTrue(h.services.inbox.state.value.items.isEmpty())
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun backSaveAfterActivityGone_fallsBackToASystemToastNamingTheGraph() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { ((vm.destination.value as? CaptureDestination.Ready)?.graph?.id == h.workId()) }
        vm.updateText("host is gone")
        vm.hostAttached = false

        vm.backSave()
        await { vm.backSave.value is CaptureViewModel.BackSaveState.Done }
        await { ShadowToast.getTextOfLatestToast() != null }

        assertEquals("Saved to Work graph", ShadowToast.getTextOfLatestToast())
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun queueForLater_keepsTheTextInTheUnavailableGraphsSlot() {
        val h = harness()
        h.app.offGraphReasonOverride = { "its folder is missing." }
        val vm = CaptureViewModel(h.app)
        vm.beginShare(h.workId().value)
        await { vm.destination.value is CaptureDestination.Unavailable }
        vm.updateText("queue me")

        vm.queueForLater()
        await { vm.saveState.value is CaptureViewModel.SaveState.Queued }

        assertEquals("Queued for Work graph", vm.resultNote.value)
        assertEquals(1, h.services.inbox.state.value.pendingCount(h.workId()))
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun noGraphsConfigured_closeQueuesInUnassignedSlot_andRedeliveryIsAlreadyAdded() {
        val h = harness(withGraphs = false)
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value == CaptureDestination.NoGraphs }
        vm.updateText("before any graph exists")

        vm.closeWithoutGraph()
        await { vm.resultNote.value != null }
        assertEquals("Saved. It will be added to the first graph you create.", vm.resultNote.value)
        assertEquals(1, h.services.inbox.state.value.unassignedCount)

        val again = CaptureViewModel(h.app)
        again.beginShare(null, restoredCaptureId = vm.captureId)
        await { again.destination.value == CaptureDestination.NoGraphs }
        again.updateText("before any graph exists")
        again.closeWithoutGraph()
        await { again.resultNote.value != null }
        assertEquals("Already added", again.resultNote.value)
        assertEquals(1, h.services.inbox.state.value.unassignedCount)
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun unassignedShare_isRekeyedToTheFirstGraphCreated() {
        val h = harness(withGraphs = false)
        val vm = CaptureViewModel(h.app)
        await { vm.destination.value == CaptureDestination.NoGraphs }
        vm.updateText("kept for the first graph")
        vm.closeWithoutGraph()
        await { vm.resultNote.value != null }

        h.services.drain.start()
        val id = runBlocking { h.gm.addGraph(h.workDir, displayName = "Work") }
        await { h.services.inbox.state.value.pendingCount(id) == 1 }
        assertEquals(0, h.services.inbox.state.value.unassignedCount)
        h.services.drain.close()
    }

    @Test
    @Config(sdk = [29], application = SteleKitApplication::class)
    fun handledFlag_restoredAfterProcessDeath_blocksASecondSave() {
        val h = harness()
        val vm = CaptureViewModel(h.app)
        vm.restoreHandled()
        assertTrue(vm.isHandled)
        assertEquals("Already added", vm.resultNote.value)
    }
}