package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.sections.SectionManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Section manifest edits are persisted in the notes folder, the one `GraphLoader` reads. */
class SectionManagementCoordinatorTest {

    private class MapSettings : Settings {
        private val values = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { values[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = values[key] ?: defaultValue
        override fun putString(key: String, value: String) { values[key] = value }
        override fun containsKey(key: String) = key in values
    }

    private val fs = FakeFileSystem()
    private val manifestPath = "/repo/logseq/${SectionManifest.FILENAME}"

    private fun coordinator(state: MutableStateFlow<AppState>) = SectionManagementCoordinator(
        fileSystem = fs,
        graphLoader = GraphLoader(fs, InMemoryPageRepository(), InMemoryBlockRepository()),
        graphWriter = GraphWriter(fs),
        pageRepository = InMemoryPageRepository(),
        writeActor = null,
        platformSettings = MapSettings(),
        scope = CoroutineScope(Dispatchers.Unconfined),
        uiState = state,
        onSectionsLoaded = null,
        sendSnackbar = {},
        onJournalPageCreated = {},
    )

    private fun stateAt(path: String?) =
        MutableStateFlow(AppState(currentGraphPath = path, currentManifest = SectionManifest()))

    @Test
    fun `create, rename and delete a section persist to the notes folder`() {
        val state = stateAt("/repo/logseq")
        val coordinator = coordinator(state)

        coordinator.createSection("acme", "Acme", null, "pages/acme", "journals/acme")
        assertNotNull(fs.readFile(manifestPath), "manifest must be written next to pages/ and journals/")
        assertNull(fs.readFile("/repo/${SectionManifest.FILENAME}"), "never at the repo root")
        assertEquals(listOf("Acme"), state.value.currentManifest?.sections?.map { it.displayName })

        coordinator.renameSection("acme", "Acme Corp")
        assertEquals(listOf("Acme Corp"), state.value.currentManifest?.sections?.map { it.displayName })

        coordinator.deleteSection("acme")
        assertEquals(emptyList(), state.value.currentManifest?.sections)
    }

    @Test
    fun `nothing is written while no graph is open`() {
        val coordinator = coordinator(stateAt(null))

        coordinator.createSection("acme", "Acme", null, "pages/acme", "journals/acme")

        assertNull(fs.readFile(manifestPath))
    }
}
