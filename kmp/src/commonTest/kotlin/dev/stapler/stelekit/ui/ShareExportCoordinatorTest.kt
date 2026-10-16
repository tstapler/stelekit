package dev.stapler.stelekit.ui

import arrow.core.getOrElse
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.export.HtmlExporter
import dev.stapler.stelekit.export.JsonExporter
import dev.stapler.stelekit.export.MarkdownExporter
import dev.stapler.stelekit.export.PlainTextExporter
import dev.stapler.stelekit.export.ShareExportSettings
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock

class ShareExportCoordinatorTest {

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    private class RecordingClipboard : ClipboardProvider {
        var text: String? = null
        var html: String? = null
        override fun writeText(text: String) { this.text = text }
        override fun writeHtml(html: String, plainFallback: String) { this.html = html; this.text = plainFallback }
    }

    private val now = Clock.System.now()
    private val page = Page(uuid = PageUuid("p"), name = "P", createdAt = now, updatedAt = now)
    private val dirty = "https://a.com/x?utm_source=n&keep=1"
    private val blocks = listOf(
        Block(
            uuid = BlockUuid("b1"), pageUuid = PageUuid("p"), parentUuid = null,
            content = "see <$dirty>", level = 0, position = "a0", createdAt = now, updatedAt = now,
        )
    )

    private val clipboard = RecordingClipboard()
    private val pageRepo = InMemoryPageRepository()
    private val blockRepo = InMemoryBlockRepository()
    private val uiState = MutableStateFlow(AppState())
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun installMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun removeMain() = Dispatchers.resetMain()

    private fun coordinator(settings: MapSettings = MapSettings()) = ShareExportCoordinator(
        exportService = ExportService(
            listOf(MarkdownExporter(), PlainTextExporter(), HtmlExporter(), JsonExporter()),
            clipboard,
            blockRepo,
        ),
        notificationManager = null,
        pageRepository = pageRepo,
        blockRepository = blockRepo,
        blockStateManager = null,
        scope = coordinatorScope,
        uiState = uiState,
        shareExportSettings = ShareExportSettings(settings),
    )

    @Test
    fun setStripTrackingParams_updatesState_andIsRestoredByNextCoordinator() {
        val settings = MapSettings()
        val first = coordinator(settings)
        assertFalse(uiState.value.shareStripTrackingParams)
        first.setStripTrackingParams(true)
        assertTrue(uiState.value.shareStripTrackingParams)

        uiState.value = AppState()
        coordinator(settings)
        assertTrue(uiState.value.shareStripTrackingParams, "init must restore the persisted value")
    }

    @Test
    fun resolveExportContent_stripsOnlyWhenEnabled_forEveryScope() = runTest {
        val c = coordinator()
        val selected = setOf("b1")
        val day = LocalDate(2026, 1, 1)
        for (scope in ShareScope.entries - ShareScope.JournalRange) {
            c.setStripTrackingParams(false)
            val off = c.resolveExportContent(scope, page, blocks, selected, "markdown", day, day)
                .getOrElse { error(it.message) }
            c.setStripTrackingParams(true)
            val on = c.resolveExportContent(scope, page, blocks, selected, "markdown", day, day)
                .getOrElse { error(it.message) }
            assertFalse(on.contains("utm_source"), "$scope on: $on")
            assertTrue(off.contains("utm_source"), "$scope off: $off")
        }
    }

    @Test
    fun resolveExportContent_journalRange_errorsWithoutDatesOrJournalPages() = runTest {
        val c = coordinator()
        c.setStripTrackingParams(true)
        val day = LocalDate(2026, 1, 1)
        assertTrue(c.resolveExportContent(ShareScope.JournalRange, page, blocks, emptySet(), "markdown", day, day).isLeft())
        assertTrue(c.resolveExportContent(ShareScope.JournalRange, page, blocks, emptySet(), "markdown").isLeft())
    }

    @Test
    fun exportScopeToClipboard_stripsForCurrentPageSelectedBlocksAndLinks() = runTest {
        val c = coordinator()
        c.setStripTrackingParams(true)
        for (scope in listOf(ShareScope.CurrentPage, ShareScope.SelectedBlocks, ShareScope.PageAndLinks)) {
            for (format in listOf("markdown", "html")) {
                clipboard.text = null
                c.exportScopeToClipboard(scope, page, blocks, setOf("b1"), format)
                // onDone is posted to Dispatchers.Main (unpumped under Robolectric), so wait on the write itself.
                withContext(Dispatchers.Default) { withTimeout(10_000) { while (clipboard.text == null) delay(10) } }
                val written = clipboard.html ?: clipboard.text
                assertTrue(written != null && !written.contains("utm_source"), "$scope/$format: $written")
                assertEquals(false, written!!.isEmpty())
            }
        }
    }
}
