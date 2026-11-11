// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.ui.sketch.YamlPickerAction
import dev.stapler.stelekit.ui.sketch.YamlPickerText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(DirectRepositoryWrite::class)
class YamlSnapshotImporterTest {
    private val slug = "proj-0a1b2c"
    private val project = ProjectFactory.create("Closet", slug, ProjectUuid("pr1"), 1L)
    private val repos = inMemorySketchRepositories()
    private val fs = SidecarFs()
    private var busy = false
    private val store = newStore(fs) { busy }
    private var failWrites = false
    private val importer = YamlSnapshotImporter(repos, { store }, { op -> if (failWrites) DomainError.DatabaseError.WriteFailed("x").left() else op() })

    private suspend fun loaded() = repos.projects.loadProject(ProjectUuid("pr1")).getOrNull()!!.project

    private suspend fun seed() = repos.projects.saveProject(project)

    @Test
    fun importStoresContentUnderAHashNameAndRecordsOnlyTheHash() = runTest {
        seed()
        val outcome = importer.import("pr1", "niche:\n  width: 136\n".encodeToByteArray())

        val hash8 = assertIs<YamlImportOutcome.Imported>(outcome).hash8
        assertTrue(YamlSourceEntry.isValidHash(hash8))
        assertEquals("niche:\n  width: 136\n", fs.readFileBytes(sidecarPath(slug, "sources/yaml-$hash8.yaml"))!!.decodeToString())
        assertEquals(hash8, YamlSourceEntry.snapshotOf(loaded().settings))
        assertEquals("niche:\n  width: 136\n", readYamlSnapshot(repos, store))
    }

    @Test
    fun theSyncedSidecarHoldsNoPathUriOrFileName() = runTest {
        seed()
        importer.import("pr1", "a: 1\n".encodeToByteArray())
        val sidecar = SidecarCodec.encode(ProjectBundle(loaded(), emptyList(), emptyList(), emptyList()))
            .values.joinToString("\n") { it.decodeToString() }

        listOf("content://", "file://", ".yaml\"", "/home", "closet.yaml", "filename", "path").forEach {
            assertFalse(sidecar.contains(it), "synced sidecar must not contain <$it>")
        }
        assertTrue(sidecar.contains("\"kind\": \"yaml\""))
    }

    @Test
    fun replacingTheSnapshotRehashesAndBumpsTheModelRevision() = runTest {
        seed()
        val first = assertIs<YamlImportOutcome.Imported>(importer.import("pr1", "a: 1\n".encodeToByteArray())).hash8
        val revision = loaded().modelRevision
        val second = assertIs<YamlImportOutcome.Imported>(importer.import("pr1", "a: 2\n".encodeToByteArray())).hash8

        assertTrue(first != second)
        assertEquals(second, YamlSourceEntry.snapshotOf(loaded().settings))
        assertEquals(revision + 1, loaded().modelRevision)
        assertEquals(1, (loaded().settings[SourceBindings.KEY] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test
    fun theSameContentIsUnchangedAndDoesNotBumpTheRevision() = runTest {
        seed()
        importer.import("pr1", "a: 1\n".encodeToByteArray())
        val revision = loaded().modelRevision

        assertEquals(YamlImportOutcome.Unchanged, importer.import("pr1", "a: 1\n".encodeToByteArray()))
        assertEquals(revision, loaded().modelRevision)
    }

    @Test
    fun pageBindingsSurviveAYamlImportAndTheYamlSurvivesBinding() = runTest {
        val binding = SourceBinding(SourceBindingKind.TABLE, "0a1b2c3d", "hash")
        repos.projects.saveProject(project.copy(settings = SourceBindings.with(project.settings, binding)!!))
        val hash8 = assertIs<YamlImportOutcome.Imported>(importer.import("pr1", "a: 1\n".encodeToByteArray())).hash8

        assertEquals(listOf(binding), SourceBindings.of(loaded().settings))
        val rebound = SourceBindings.with(loaded().settings, binding.copy(contentHash = "other"))!!
        assertEquals(hash8, YamlSourceEntry.snapshotOf(rebound))
    }

    @Test
    fun rulesRejectEmptyOversizedAndBinaryContent() {
        assertEquals(YamlImportOutcome.Empty, YamlSnapshotRules.check(ByteArray(0)))
        assertEquals(YamlImportOutcome.TooLarge, YamlSnapshotRules.check(ByteArray(YamlSnapshotRules.MAX_BYTES + 1) { 'a'.code.toByte() }))
        assertEquals(YamlImportOutcome.NotText, YamlSnapshotRules.check(byteArrayOf(1, 0, 2)))
        assertNull(YamlSnapshotRules.check("a: 1".encodeToByteArray()))
    }

    @Test
    fun aBusySyncDefersWithoutRecordingAnything() = runTest {
        seed()
        busy = true
        assertEquals(YamlImportOutcome.Deferred, importer.import("pr1", "a: 1\n".encodeToByteArray()))
        assertNull(YamlSourceEntry.snapshotOf(loaded().settings))
        assertTrue(fs.allFilePaths().none { it.contains("yaml-") })
    }

    @Test
    fun aFailedSaveReportsFailureAndAnUnknownProjectIsRefused() = runTest {
        seed()
        failWrites = true
        assertIs<YamlImportOutcome.Failed>(importer.import("pr1", "a: 1\n".encodeToByteArray()))
        assertEquals(YamlImportOutcome.ProjectNotFound, importer.import("missing", "a: 1\n".encodeToByteArray()))
    }

    @Test
    fun noStoreMeansUnavailable() = runTest {
        seed()
        val noStore = YamlSnapshotImporter(repos, { null }, { it() })
        assertEquals(YamlImportOutcome.StoreUnavailable, noStore.import("pr1", "a: 1\n".encodeToByteArray()))
    }

    @Test
    fun aMalformedSnapshotEntryReadsAsNone() {
        val settings = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"sources":[{"kind":"yaml","snapshot":"../../x"},{"kind":"yaml"}]}""",
        ) as kotlinx.serialization.json.JsonObject
        assertNull(YamlSourceEntry.snapshotOf(settings))
    }

    @Test
    fun theStoreRefusesAnInvalidSnapshotName() = runTest {
        assertNull(store.readSourceSnapshot(slug, "../../x"))
        assertNull(store.readSourceSnapshot(slug, "abcd1234"))
    }

    @Test
    fun pickerActionCancelledPickRecordsNothingAndReportsNoMessage() = runTest {
        seed()
        val action = YamlPickerAction(importer, repos) { null }
        assertNull(action.run("pr1"))
        assertFalse(action.hasSnapshot("pr1"))
    }

    @Test
    fun pickerActionImportsThePickedTextAndMessagesRejections() = runTest {
        seed()
        var text: String? = "a: 1\n"
        val action = YamlPickerAction(importer, repos) { text }
        assertNull(action.run("pr1"))
        assertTrue(action.hasSnapshot("pr1"))
        assertEquals("That YAML file is already the project's source", action.run("pr1"))
        text = ""
        assertEquals("That file is empty", action.run("pr1"))
        assertEquals("Replace YAML file", YamlPickerText.action(true))
        assertEquals("YAML source: none", YamlPickerText.status(false))
    }

    @Test
    fun everyOutcomeHasAMessageExceptImported() {
        val outcomes = listOf(
            YamlImportOutcome.Unchanged, YamlImportOutcome.Empty, YamlImportOutcome.TooLarge, YamlImportOutcome.NotText,
            YamlImportOutcome.ProjectNotFound, YamlImportOutcome.StoreUnavailable, YamlImportOutcome.Deferred, YamlImportOutcome.Failed("boom"),
        )
        outcomes.forEach { assertTrue(YamlSnapshotRules.message(it)!!.isNotBlank()) }
        assertNull(YamlSnapshotRules.message(YamlImportOutcome.Imported("abcd1234")))
    }
}
