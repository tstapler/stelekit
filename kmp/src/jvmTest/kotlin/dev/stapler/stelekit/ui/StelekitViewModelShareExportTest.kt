// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui

import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.export.HtmlExporter
import dev.stapler.stelekit.export.MarkdownExporter
import dev.stapler.stelekit.export.PlainTextExporter
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.NotificationType
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.google.DriveUploader
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemorySearchRepository
import dev.stapler.stelekit.ui.fixtures.FakeBlockRepository
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.FakePageRepository
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Regression coverage for the Share & Export function group (Phase 4 of the
 * StelekitViewModel decomposition — `project_plans/stelekit-viewmodel-decomposition/plan.md`).
 *
 * This group had **zero existing test coverage** before this file (`grep -rl
 * "shareToGoogleDocs\|exportScopeToClipboard\|resolveExportContent"` across every test source
 * set returned no hits — see the plan's "Test Coverage Findings" section). These tests are
 * written and run green against the pre-extraction `StelekitViewModel` implementation first,
 * then re-run unchanged against the post-extraction `ShareExportCoordinator` to prove the move
 * preserved behavior.
 */
class StelekitViewModelShareExportTest {

    private val now = Clock.System.now()

    private fun page(uuid: String = "page-1", name: String = "Test Page") = Page(
        uuid = PageUuid(uuid),
        name = name,
        createdAt = now,
        updatedAt = now,
    )

    private fun block(uuid: String, content: String, pageUuid: String = "page-1") = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        parentUuid = null,
        content = content,
        level = 0,
        position = "a0",
        createdAt = now,
        updatedAt = now,
    )

    /** Records every write so assertions can inspect exactly what reached the "clipboard". */
    private class RecordingClipboard : ClipboardProvider {
        var writtenText: String? = null
        var writtenHtml: String? = null
        var writtenHtmlPlainFallback: String? = null
        override fun writeText(text: String) {
            writtenText = text
        }
        override fun writeHtml(html: String, plainFallback: String) {
            writtenHtml = html
            writtenHtmlPlainFallback = plainFallback
        }
    }

    /** Records every upload attempt and returns a caller-supplied canned [result]. */
    private class FakeDriveUploader(
        private val result: arrow.core.Either<DomainError, String>,
    ) : DriveUploader {
        var callCount = 0
        var lastFileName: String? = null
        var lastMimeType: String? = null
        override suspend fun uploadFile(
            fileName: String,
            mimeType: String,
            bytes: ByteArray,
            parentFolderId: String?,
        ): arrow.core.Either<DomainError, String> {
            callCount++
            lastFileName = fileName
            lastMimeType = mimeType
            return result
        }
    }

    private fun makeViewModel(
        clipboard: ClipboardProvider = RecordingClipboard(),
        notificationManager: NotificationManager = NotificationManager(),
        exportServiceOverride: ExportService? = null,
    ): StelekitViewModel {
        val pageRepo = FakePageRepository()
        val blockRepo = FakeBlockRepository()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val fileSystem = FakeFileSystem()
        val exportService = exportServiceOverride ?: ExportService(
            exporters = listOf(MarkdownExporter(), PlainTextExporter(), HtmlExporter()),
            clipboard = clipboard,
            blockRepository = InMemoryBlockRepository(),
        )
        return StelekitViewModel(
            StelekitViewModelDependencies(
                fileSystem = fileSystem,
                pageRepository = pageRepo,
                blockRepository = blockRepo,
                searchRepository = InMemorySearchRepository(),
                graphLoader = GraphLoader(fileSystem, pageRepo, blockRepo),
                graphWriter = GraphWriter(fileSystem),
                platformSettings = InMemorySettings(),
                scope = scope,
                notificationManager = notificationManager,
                exportService = exportService,
                activeGitSyncService = MutableStateFlow(null),
            )
        )
    }

    /** Suspends until [onDone] fires or [timeoutMs] elapses, surfacing a clear timeout failure. */
    private fun awaitOnDone(timeoutMs: Long = 5_000, body: (onDone: () -> Unit) -> Unit) = runBlocking {
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        body { completed.complete(Unit) }
        withTimeout(timeoutMs) { completed.await() }
    }

    /** Polls [notificationManager]'s history until a matching entry appears, or times out. */
    private suspend fun awaitNotification(
        notificationManager: NotificationManager,
        timeoutMs: Long = 5_000,
        predicate: (dev.stapler.stelekit.model.Notification) -> Boolean,
    ): dev.stapler.stelekit.model.Notification = withTimeout(timeoutMs) {
        var match = notificationManager.history.value.firstOrNull(predicate)
        while (match == null) {
            kotlinx.coroutines.delay(10)
            match = notificationManager.history.value.firstOrNull(predicate)
        }
        match
    }

    // ── exportScopeToClipboard: success path ────────────────────────────────────

    @Test
    fun exportScopeToClipboard_currentPage_writesMarkdownToClipboard() {
        val clipboard = RecordingClipboard()
        val vm = makeViewModel(clipboard = clipboard)
        val testPage = page()
        val blocks = listOf(block("b1", "Hello world"))

        awaitOnDone { onDone ->
            vm.exportScopeToClipboard(
                shareScope = ShareScope.CurrentPage,
                page = testPage,
                allBlocks = blocks,
                selectedUuids = emptySet(),
                formatId = "markdown",
                onDone = onDone,
            )
        }

        assertTrue(
            clipboard.writtenText?.contains("Hello world") == true,
            "expected clipboard to receive markdown containing the block content, got: ${clipboard.writtenText}",
        )
        assertNull(clipboard.writtenHtml, "markdown export must not go through the HTML write path")
    }

    // ── exportScopeToClipboard: failure path ────────────────────────────────────

    @Test
    fun exportScopeToClipboard_journalRangeWithoutDates_showsErrorNotification_andWritesNothing() {
        val clipboard = RecordingClipboard()
        val notificationManager = NotificationManager()
        val vm = makeViewModel(clipboard = clipboard, notificationManager = notificationManager)
        val testPage = page()

        awaitOnDone { onDone ->
            vm.exportScopeToClipboard(
                shareScope = ShareScope.JournalRange,
                page = testPage,
                allBlocks = emptyList(),
                selectedUuids = emptySet(),
                formatId = "markdown",
                journalFrom = null,
                journalTo = null,
                onDone = onDone,
            )
        }

        val notification = runBlocking {
            awaitNotification(notificationManager) { it.content.contains("Export failed") }
        }
        assertTrue(
            notification.content.contains("Start date not set"),
            "expected resolveExportContent's JournalRange validation message, got: ${notification.content}",
        )
        assertEquals(NotificationType.ERROR, notification.type)
        assertNull(clipboard.writtenText, "a failed resolve must never reach the clipboard")
    }

    // ── shareToGoogleDocs: success path ─────────────────────────────────────────

    @Test
    fun shareToGoogleDocs_currentPage_uploadsHtmlAndClearsExportingFlag() {
        val notificationManager = NotificationManager()
        val vm = makeViewModel(notificationManager = notificationManager)
        val testPage = page(name = "My Page")
        val blocks = listOf(block("b1", "Shared content"))
        val uploader = FakeDriveUploader(result = "drive-file-id-123".right())

        vm.shareToGoogleDocs(
            shareScope = ShareScope.CurrentPage,
            page = testPage,
            allBlocks = blocks,
            selectedUuids = emptySet(),
            driveClient = uploader,
        )

        runBlocking {
            withTimeout(5_000) {
                while (uploader.callCount == 0 || vm.uiState.value.isExportingToDrive) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }

        assertEquals(1, uploader.callCount)
        assertEquals("My Page", uploader.lastFileName)
        assertEquals("application/vnd.google-apps.document", uploader.lastMimeType)
        assertFalse(vm.uiState.value.isExportingToDrive)
        assertTrue(
            notificationManager.history.value.none { it.type == NotificationType.ERROR },
            "a successful upload must not show an error notification",
        )
    }

    // ── shareToGoogleDocs: failure path (upload fails) ──────────────────────────

    @Test
    fun shareToGoogleDocs_uploadFails_showsErrorNotification_andClearsExportingFlag() {
        val notificationManager = NotificationManager()
        val vm = makeViewModel(notificationManager = notificationManager)
        val testPage = page()
        val blocks = listOf(block("b1", "Some content"))
        val uploader = FakeDriveUploader(
            result = DomainError.NetworkError.RequestFailed("upload rejected").left(),
        )

        vm.shareToGoogleDocs(
            shareScope = ShareScope.CurrentPage,
            page = testPage,
            allBlocks = blocks,
            selectedUuids = emptySet(),
            driveClient = uploader,
        )

        val notification = runBlocking {
            awaitNotification(notificationManager) { it.content.contains("Google Docs upload failed") }
        }
        assertTrue(notification.content.contains("upload rejected"))
        assertEquals(NotificationType.ERROR, notification.type)
        runBlocking {
            withTimeout(5_000) {
                while (vm.uiState.value.isExportingToDrive) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        assertFalse(vm.uiState.value.isExportingToDrive)
    }
}
