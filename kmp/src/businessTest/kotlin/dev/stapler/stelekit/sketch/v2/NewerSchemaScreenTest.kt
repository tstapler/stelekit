// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.ui.sketch.NoEditorReason
import dev.stapler.stelekit.ui.sketch.v2.HostPlatform
import dev.stapler.stelekit.ui.sketch.v2.lastBackupExporterOf
import dev.stapler.stelekit.ui.sketch.v2.migration.LastBackupExporter
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorAction
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorDetails
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorModel
import dev.stapler.stelekit.ui.sketch.v2.migration.PendingModel
import dev.stapler.stelekit.ui.sketch.v2.migration.ReleasesPageUpdateCheck
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tasks 1.4.6d and 1.4.6j (UX-79, UX-132, UX-134, UX-147): the no-editor model, the pending model and the newer-schema actions. */
class NewerSchemaScreenTest {
    private val backupDir = "/g/assets/stelekit-sketch/kitchen/backup"

    private class BackupFs(val files: MutableMap<String, ByteArray>, private val picked: String? = "/export/s1.v1.json") : StubFileSystem() {
        val written = mutableMapOf<String, ByteArray>()
        val times = mutableMapOf<String, Long>()
        override fun listFiles(path: String): List<String> =
            files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/") }
        override fun readFileBytes(path: String): ByteArray? = files[path]
        override fun getLastModifiedTime(path: String): Long? = times[path]
        override suspend fun pickSaveFileAsync(suggestedName: String, mimeType: String): String? = picked
        override fun writeFileBytes(path: String, data: ByteArray): Boolean {
            written[path] = data
            return true
        }
    }

    @Test
    fun theNewerSchemaScreen_should_ListItsActionsInTabOrder() {
        val model = NoEditorModel.of(NoEditorReason.NewerSchema, hasBackup = true)
        assertEquals("Update SteleKit to view this sketch", model.heading)
        assertEquals(
            listOf(NoEditorAction.CopyDetails, NoEditorAction.CheckForUpdates, NoEditorAction.ExportLastBackup, NoEditorAction.Close),
            model.actions,
        )
    }

    @Test
    fun exportLastBackup_should_BeHiddenWhenNoBackupExists() {
        val model = NoEditorModel.of(NoEditorReason.NewerSchema, hasBackup = false)
        assertFalse(NoEditorAction.ExportLastBackup in model.actions)
        assertEquals(listOf(NoEditorAction.CopyDetails, NoEditorAction.CheckForUpdates, NoEditorAction.Close), model.actions)
        assertFalse(LastBackupExporter(BackupFs(mutableMapOf()), backupDir).hasBackup())
    }

    @Test
    fun storageUnavailable_should_OfferHowToOpenAndSayNothingCanBeExported() {
        val model = NoEditorModel.of(NoEditorReason.StorageUnavailable(V2Storage.Browser(StorageReason.NoOpfs)), hasBackup = true)
        assertEquals("This sketch can't be opened here", model.heading)
        assertEquals(listOf(NoEditorAction.CopyDetails, NoEditorAction.HowToOpen, NoEditorAction.Close), model.actions)
        assertTrue("Nothing can be exported from here in this version." in model.paragraphs)
        assertEquals("NoOpfs", model.reasonId)
    }

    @Test
    fun aTransientReason_should_OfferReloadFirst() {
        for (reason in listOf(StorageReason.PreloadFailed, StorageReason.CheckTimedOut)) {
            val model = NoEditorModel.of(NoEditorReason.StorageUnavailable(V2Storage.Browser(reason)), hasBackup = false)
            assertEquals("This sketch can't be opened right now", model.heading)
            assertEquals(listOf(NoEditorAction.Reload, NoEditorAction.CopyDetails, NoEditorAction.Close), model.actions)
        }
    }

    @Test
    fun copyDetails_should_CarryNamesAndTheReasonId_AndNoContent() {
        val model = NoEditorModel.of(NoEditorReason.StorageUnavailable(V2Storage.PlatformUnsupported), hasBackup = false)
        val text = NoEditorDetails("Plan A", "Kitchen", "2", "iOS").text(model)
        assertEquals("Sketch: Plan A\nProject: Kitchen\nSchema: 2\nPlatform: iOS\nReason: PlatformUnsupported", text)
    }

    @Test
    fun everyScreenHasCloseAsItsLastAction() {
        val reasons = listOf(
            NoEditorReason.NewerSchema,
            NoEditorReason.StorageUnavailable(V2Storage.NoStore),
            NoEditorReason.StorageUnavailable(V2Storage.Browser(StorageReason.PreloadTooLarge)),
        )
        reasons.forEach { assertEquals(NoEditorAction.Close, NoEditorModel.of(it, true).actions.last()) }
    }

    @Test
    fun pending_should_ShowTheLongerLineOnlyAfterFiveSeconds() {
        assertNull(PendingModel.at(4).longerLine)
        assertEquals("This is taking longer than usual.", PendingModel.at(5).longerLine)
        assertEquals("Checking this browser's storage...", PendingModel.at(0).heading)
    }

    @Test
    fun checkForUpdates_should_OpenTheReleasesPage_AndWriteNothing() {
        val opened = mutableListOf<String>()
        ReleasesPageUpdateCheck { opened += it }.check()
        assertEquals(listOf(ReleasesPageUpdateCheck.RELEASES_URL), opened)
    }

    @Test
    fun exportLastBackup_should_SaveTheNewestBackupWithoutWritingToTheSidecar() = runTest {
        val fs = BackupFs(
            mutableMapOf(
                "$backupDir/old.v1.json" to byteArrayOf(1),
                "$backupDir/new.v1.json" to byteArrayOf(2, 2),
                "$backupDir/new.v2-before-restore.json" to byteArrayOf(9),
            ),
        )
        fs.times["$backupDir/old.v1.json"] = 10L
        fs.times["$backupDir/new.v1.json"] = 20L
        val exporter = LastBackupExporter(fs, backupDir)
        assertTrue(exporter.hasBackup())
        assertEquals("new.v1.json", exporter.newest())
        assertEquals("/export/s1.v1.json", exporter.export())
        assertContentEquals(byteArrayOf(2, 2), fs.written["/export/s1.v1.json"])
        assertTrue(fs.written.keys.none { it.startsWith(backupDir) }, "nothing is written to the sidecar")
    }

    @Test
    fun exportLastBackup_should_ReturnNullWhenTheUserCancelsThePicker() = runTest {
        val fs = BackupFs(mutableMapOf("$backupDir/a.v1.json" to byteArrayOf(1)), picked = null)
        assertNull(LastBackupExporter(fs, backupDir).export())
        assertTrue(fs.written.isEmpty())
    }

    @Test
    fun theRoute_should_FindTheBackupOfTheSlugItOpened_AndNoneWithoutAFileSystemFolderOrSlug() {
        val fs = BackupFs(mutableMapOf("$backupDir/a.v1.json" to byteArrayOf(1)))
        val platform = HostPlatform(fileSystem = fs, notesDirectory = { "/g/" })
        assertTrue(lastBackupExporterOf(platform, "kitchen")!!.hasBackup())
        assertFalse(lastBackupExporterOf(platform, "other")!!.hasBackup())
        assertNull(lastBackupExporterOf(platform, null))
        assertNull(lastBackupExporterOf(HostPlatform(fileSystem = fs), "kitchen"))
        assertNull(lastBackupExporterOf(HostPlatform(notesDirectory = { "/g" }), "kitchen"))
    }
}
