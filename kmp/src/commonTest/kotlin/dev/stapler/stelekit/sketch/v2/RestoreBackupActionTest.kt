// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.RetentionPolicy
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreOutcome
import dev.stapler.stelekit.ui.sketch.v2.BackupsSectionModel
import dev.stapler.stelekit.ui.sketch.v2.RestoreAvailability
import dev.stapler.stelekit.ui.sketch.v2.RestoreBackupAction
import dev.stapler.stelekit.ui.sketch.v2.RestoreBackupState
import dev.stapler.stelekit.ui.sketch.v2.RestoreCopy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tasks 6.2.6b and 6.2.6c (UX-78, UX-90, UX-114, UX-137): the confirmed restore, its toast, and the confirmed delete of backups. */
class RestoreBackupActionTest {
    private val sheet = SheetUuid("s1")
    private val calls = mutableListOf<Pair<String, SheetUuid>>()

    private fun action(outcome: RestoreOutcome) = RestoreBackupAction { slug, uuid -> calls += slug to uuid; outcome }

    @Test
    fun restore_should_NotRunUntilConfirmed_AndCancelShouldChangeNothing() = runTest {
        val a = action(RestoreOutcome.Restored(sheet, "backup/s1.v2-before-restore.json"))

        a.request("kitchen", sheet)
        assertEquals(RestoreBackupState.Confirming("kitchen", sheet), a.state.value)
        a.cancel()

        assertEquals(RestoreBackupState.Idle, a.state.value)
        assertTrue(calls.isEmpty(), "Cancel restores nothing")
        a.confirm()
        assertTrue(calls.isEmpty(), "confirm without a request restores nothing")
    }

    @Test
    fun confirm_should_RestoreOnce_AndShowTheRestoredToastNamingTheKeptCopy() = runTest {
        val a = action(RestoreOutcome.Restored(sheet, "backup/s1.v2-before-restore.json"))
        a.request("kitchen", sheet)

        a.confirm()

        assertEquals(listOf("kitchen" to sheet), calls)
        val done = assertIs<RestoreBackupState.Done>(a.state.value)
        assertEquals("Restored. Copy kept.", done.notice.text)
        assertEquals("Copy path", done.notice.action)
        assertEquals(BannerTier.Info, done.notice.tier)
        assertEquals("backup/s1.v2-before-restore.json", done.keptCopy)
        assertEquals("Restored the original sketch. The new-editor version is kept in backup/.", done.details)
        a.confirm()
        assertEquals(1, calls.size, "a second confirm after Done does nothing")
    }

    @Test
    fun everyRefusal_should_SayNothingWasChanged() = runTest {
        for (outcome in listOf(
            RestoreOutcome.NotConverted, RestoreOutcome.BackupMissing, RestoreOutcome.Deferred, RestoreOutcome.Failed(RestoreFailure.Write),
        )) {
            val a = action(outcome)
            a.request("kitchen", sheet)
            a.confirm()
            val failed = assertIs<RestoreBackupState.Failed>(a.state.value, "$outcome")
            assertTrue("changed" in failed.message || "lost" in failed.message, failed.message)
            a.dismiss()
            assertEquals(RestoreBackupState.Idle, a.state.value)
        }
    }

    @Test
    fun confirmationText_should_StateTheLossAndTheKeptCopy() {
        assertEquals("Restore the original sketch?", RestoreCopy.TITLE)
        assertTrue("discarded" in RestoreCopy.BODY && "backup/" in RestoreCopy.BODY)
    }

    @Test
    fun availability_should_PutThePlatformReasonBeforeTheBuildReason_AndHideWithoutABackup() {
        assertEquals(RestoreAvailability.Disabled("iOS reason"), RestoreAvailability.of("iOS reason", buildCapped = true, hasBackup = true))
        assertEquals(RestoreAvailability.Disabled("Not available in this build yet"), RestoreAvailability.of(null, buildCapped = true, hasBackup = true))
        assertEquals(RestoreAvailability.Hidden, RestoreAvailability.of(null, buildCapped = false, hasBackup = false))
        assertEquals(RestoreAvailability.Enabled, RestoreAvailability.of(null, buildCapped = false, hasBackup = true))
    }

    @Test
    fun backupsRow_should_ShowCountAndSize() = runTest {
        val model = BackupsSectionModel({ RetentionPolicy.Size(2, 1_572_864, 0, 0) }, { 0.right() })
        assertEquals("Backups: 2 files, 1.5 MB", model.row().text)
        assertEquals("Backups: none", BackupsSectionModel({ RetentionPolicy.Size(0, 0, 0, 0) }, { 0.right() }).row().text)
        assertEquals("1 file, 512 B", BackupsSectionModel.describe(1, 512))
        assertEquals("3 files, 2.0 KB", BackupsSectionModel.describe(3, 2_048))
    }

    @Test
    fun deleteBackups_should_NeedAConfirmation_AndCancelShouldDeleteNothing() = runTest {
        var deleted = 0
        val model = BackupsSectionModel({ RetentionPolicy.Size(1, 10, 0, 0) }, { deleted++; 1.right() })

        assertNull(model.confirmDelete(), "no confirmation, no delete")
        model.requestDelete()
        assertTrue(model.isConfirming.value)
        model.cancel()
        assertNull(model.confirmDelete())
        assertEquals(0, deleted)

        model.requestDelete()
        assertEquals(1, model.confirmDelete())
        assertEquals(1, deleted)
        assertNull(model.confirmDelete(), "the confirmation is spent")
    }

    @Test
    fun aFailedDelete_should_ReportNoFilesDeleted() = runTest {
        val model = BackupsSectionModel({ RetentionPolicy.Size(1, 10, 0, 0) }, { DomainError.FileSystemError.WriteFailed("backup", "x").left() })
        model.requestDelete()
        assertNull(model.confirmDelete())
    }
}
