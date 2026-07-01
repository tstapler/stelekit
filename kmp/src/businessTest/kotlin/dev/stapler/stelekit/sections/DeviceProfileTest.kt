package dev.stapler.stelekit.sections

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.ExternalFileChange
import dev.stapler.stelekit.db.GraphLoaderPort
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.db.WriteError
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.StelekitViewModel
import dev.stapler.stelekit.ui.StelekitViewModelDependencies
import dev.stapler.stelekit.vault.CryptoLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Story 6.6 — Device profile tests (FR-12).
 *
 * Covers the three wizard paths (Work, Personal, Custom) that [StelekitViewModel.completeDeviceSetup]
 * implements. Verifies that:
 * - The correct [defaultSection] and [SectionState] map are persisted to [Settings].
 * - [deviceSetupComplete] is stored as `true` after any wizard completion.
 * - A subsequent ViewModel instance reads `deviceSetupComplete = true` and therefore
 *   will not show the wizard again (the gating condition in [StelekitViewModel.loadSectionManifest]
 *   is `!setupComplete && manifest.sections.isNotEmpty()`).
 */
class DeviceProfileTest {

    // ─── Local stubs ──────────────────────────────────────────────────────────

    private class StubSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String): String =
            store.getOrDefault(key, defaultValue)
        override fun putString(key: String, value: String) { store[key] = value }
    }

    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp/graph"
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
    }

    private class StubGraphLoaderPort : GraphLoaderPort {
        override fun setActivePageUuids(uuids: StateFlow<Set<String>>?) {}
        override fun setUnsavedPageUuids(uuids: StateFlow<Set<String>>?) {}
        override val externalFileChanges: SharedFlow<ExternalFileChange> = MutableSharedFlow()
        override val writeErrors: SharedFlow<WriteError> = MutableSharedFlow()
        override fun setCryptoLayer(layer: CryptoLayer?) {}
        override fun closeAndClearCryptoLayer() {}
        override suspend fun loadGraphProgressive(
            graphPath: String,
            immediateJournalCount: Int,
            onProgress: (String) -> Unit,
            onPhase1Complete: () -> Unit,
            onFullyLoaded: () -> Unit,
        ) { /* no-op — tests don't need graph loading */ }
        override suspend fun indexRemainingPages(onProgress: (String) -> Unit) {}
        override suspend fun loadPageByName(pageName: PageName): Page? = null
        override suspend fun loadFullPage(pageUuid: String, force: Boolean) {}
        override fun cancelBackgroundWork() {}
        override suspend fun parseAndSavePage(
            filePath: FilePath,
            content: String,
            mode: ParseMode,
            priority: DatabaseWriteActor.Priority,
        ) {}
    }

    private class StubGraphWriterPort : GraphWriterPort {
        override fun setCryptoLayer(layer: CryptoLayer?) {}
        override fun closeAndClearCryptoLayer() {}
        override fun startAutoSave(debounceMs: Long) {}
        override fun stopAutoSave() {}
        override suspend fun flush() {}
        override suspend fun renamePage(page: Page, newName: String, graphPath: String) = true
        override suspend fun savePage(page: Page, blocks: List<Block>, graphPath: String) {}
        override suspend fun deletePage(page: Page) = true
        override suspend fun movePageToSection(
            page: Page,
            newSectionId: String,
            newPathPrefix: String,
        ): Either<DomainError, Page> = page.copy(sectionId = newSectionId).right()
    }

    // Three test sections mirroring a typical multi-section graph
    private val sections = listOf(
        SectionDefinition("acme-work", "Acme Work", "#4A90D9", "pages/acme-work", "journals/acme-work"),
        SectionDefinition("personal", "Personal", "#2ECC71", "pages/personal", "journals/personal"),
        SectionDefinition("health", "Health", "#E74C3C", "pages/health", "journals/health"),
    )

    private fun makeViewModel(settings: Settings = StubSettings()): StelekitViewModel =
        StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = StubFileSystem(),
                pageRepository = InMemoryPageRepository(),
                blockRepository = InMemoryBlockRepository(),
                searchRepository = InMemorySearchRepository(),
                graphLoader = StubGraphLoaderPort(),
                graphWriter = StubGraphWriterPort(),
                platformSettings = settings,
                scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
            )
        )

    // ─── Work-device wizard path ───────────────────────────────────────────────

    @Test
    fun `work device path sets defaultSection to the primary section`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        val states = sections.associate { s ->
            s.id to if (s.id == "acme-work") SectionState.ACTIVE else SectionState.REMOVED
        }
        vm.completeDeviceSetup("acme-work", states)

        assertEquals(
            "acme-work",
            settings.getString("defaultSection", ""),
            "defaultSection must be the chosen work section",
        )
    }

    @Test
    fun `work device path marks non-primary sections as REMOVED`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        val states = sections.associate { s ->
            s.id to if (s.id == "acme-work") SectionState.ACTIVE else SectionState.REMOVED
        }
        vm.completeDeviceSetup("acme-work", states)

        val reloaded = settings.getSectionStates()
        assertEquals(SectionState.ACTIVE, reloaded["acme-work"])
        assertEquals(SectionState.REMOVED, reloaded["personal"])
        assertEquals(SectionState.REMOVED, reloaded["health"])
    }

    @Test
    fun `work device path updates uiState defaultSection and currentSectionStates`() {
        val vm = makeViewModel()
        val states = mapOf(
            "acme-work" to SectionState.ACTIVE,
            "personal" to SectionState.REMOVED,
            "health" to SectionState.REMOVED,
        )

        vm.completeDeviceSetup("acme-work", states)

        assertEquals("acme-work", vm.uiState.value.defaultSection)
        assertEquals(SectionState.ACTIVE, vm.uiState.value.currentSectionStates["acme-work"])
        assertEquals(SectionState.REMOVED, vm.uiState.value.currentSectionStates["personal"])
        assertEquals(SectionState.REMOVED, vm.uiState.value.currentSectionStates["health"])
    }

    // ─── Personal-device wizard path ──────────────────────────────────────────

    @Test
    fun `personal device path sets all sections ACTIVE`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        val allActive = sections.associate { it.id to SectionState.ACTIVE }
        vm.completeDeviceSetup("", allActive)

        val reloaded = settings.getSectionStates()
        assertEquals(SectionState.ACTIVE, reloaded["acme-work"])
        assertEquals(SectionState.ACTIVE, reloaded["personal"])
        assertEquals(SectionState.ACTIVE, reloaded["health"])
    }

    @Test
    fun `personal device path sets defaultSection to empty string`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        vm.completeDeviceSetup("", sections.associate { it.id to SectionState.ACTIVE })

        assertEquals("", settings.getString("defaultSection", "sentinel"),
            "Personal device must have empty defaultSection (global journals)")
    }

    @Test
    fun `personal device path updates uiState correctly`() {
        val vm = makeViewModel()

        vm.completeDeviceSetup("", sections.associate { it.id to SectionState.ACTIVE })

        assertEquals("", vm.uiState.value.defaultSection)
        sections.forEach { s ->
            assertEquals(SectionState.ACTIVE, vm.uiState.value.currentSectionStates[s.id],
                "${s.id} must be ACTIVE")
        }
    }

    // ─── Custom wizard path ───────────────────────────────────────────────────

    @Test
    fun `custom path stores exactly the user-selected states`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        val custom = mapOf(
            "acme-work" to SectionState.HIDDEN,
            "personal" to SectionState.ACTIVE,
            "health" to SectionState.REMOVED,
        )
        vm.completeDeviceSetup("personal", custom)

        val reloaded = settings.getSectionStates()
        assertEquals(SectionState.HIDDEN, reloaded["acme-work"])
        assertEquals(SectionState.ACTIVE, reloaded["personal"])
        assertEquals(SectionState.REMOVED, reloaded["health"])
        assertEquals("personal", settings.getString("defaultSection", ""))
    }

    // ─── deviceSetupComplete flag ─────────────────────────────────────────────

    @Test
    fun `deviceSetupComplete is false before any wizard completion`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        assertFalse(vm.uiState.value.deviceSetupComplete)
        assertFalse(settings.getBoolean("deviceSetupComplete", false))
    }

    @Test
    fun `deviceSetupComplete is persisted as true after work-device wizard completes`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        vm.completeDeviceSetup(
            "acme-work",
            mapOf("acme-work" to SectionState.ACTIVE, "personal" to SectionState.REMOVED),
        )

        assertTrue(settings.getBoolean("deviceSetupComplete", false),
            "deviceSetupComplete must be true in persisted Settings")
        assertTrue(vm.uiState.value.deviceSetupComplete)
    }

    @Test
    fun `deviceSetupComplete is persisted as true after personal-device wizard completes`() {
        val settings = StubSettings()
        val vm = makeViewModel(settings)

        vm.completeDeviceSetup("", sections.associate { it.id to SectionState.ACTIVE })

        assertTrue(settings.getBoolean("deviceSetupComplete", false))
    }

    @Test
    fun `wizard is dismissed (deviceSetupWizardVisible = false) after any completion`() {
        val vm = makeViewModel()

        vm.completeDeviceSetup("acme-work", emptyMap())

        assertFalse(vm.uiState.value.deviceSetupWizardVisible,
            "Wizard must be closed immediately after completeDeviceSetup is called")
    }

    // ─── Wizard not shown on subsequent launches ─────────────────────────────

    @Test
    fun `wizard not shown on next launch when deviceSetupComplete is already true in settings`() {
        val settings = StubSettings()

        // First launch: user completes the wizard
        val vm1 = makeViewModel(settings)
        vm1.completeDeviceSetup("acme-work", mapOf("acme-work" to SectionState.ACTIVE))
        assertTrue(settings.getBoolean("deviceSetupComplete", false),
            "Pre-condition: deviceSetupComplete must be persisted")

        // Second launch: new ViewModel reading the same settings
        val vm2 = makeViewModel(settings)

        assertTrue(vm2.uiState.value.deviceSetupComplete,
            "deviceSetupComplete must be true on re-launch (read from persisted settings)")
        // The wizard is shown only inside loadSectionManifest when !setupComplete.
        // Since deviceSetupComplete = true, the wizard will not be shown even when sections exist.
        assertFalse(vm2.uiState.value.deviceSetupWizardVisible,
            "Wizard must NOT be visible on subsequent launch")
    }
}
