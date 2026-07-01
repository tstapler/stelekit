package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoaderPort
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.db.UndoManager
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.performance.BugReportBuilder
import dev.stapler.stelekit.performance.DebugFlagRepository
import dev.stapler.stelekit.performance.HistogramWriter
import dev.stapler.stelekit.performance.RingBufferSpanExporter
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bundles all [StelekitViewModel] constructor dependencies into a single value object,
 * reducing call-site fragility when the constructor evolves (Parameter Object pattern,
 * Fowler "Refactoring" §Introduce Parameter Object).
 *
 * Parameters are grouped by concern:
 * - **Core repositories**: the storage layer the ViewModel reads from and writes to.
 * - **Graph services**: load/write orchestration for the Markdown graph on disk.
 * - **Infrastructure**: platform abstractions (file system, settings, lifecycle scope).
 * - **Optional services**: editor state, undo, export, observability, git — all nullable/defaulted.
 *
 * The [journalService] field defaults to `null`; [StelekitViewModel] constructs a default
 * instance from [pageRepository] and [blockRepository] when it is not supplied, preserving
 * the original default behaviour.
 */
data class StelekitViewModelDependencies(
    // ── Core repositories ────────────────────────────────────────────────────
    val pageRepository: PageRepository,
    val blockRepository: BlockRepository,
    val searchRepository: SearchRepository,

    // ── Graph services ───────────────────────────────────────────────────────
    val graphLoader: GraphLoaderPort,
    val graphWriter: GraphWriterPort,

    // ── Infrastructure ───────────────────────────────────────────────────────
    val fileSystem: FileSystem,
    val platformSettings: Settings,
    /** Lifecycle scope for the ViewModel. Must NOT be a rememberCoroutineScope(). Callers must supply explicitly. */
    val scope: CoroutineScope,

    // ── Optional services ────────────────────────────────────────────────────
    val notificationManager: NotificationManager? = null,
    /**
     * Journal service. When null, [StelekitViewModel] constructs a default instance from
     * [pageRepository] and [blockRepository] — matching the original constructor default.
     */
    val journalService: JournalService? = null,
    val blockStateManager: BlockStateManager? = null,
    val writeActor: DatabaseWriteActor? = null,
    val undoManager: UndoManager? = null,
    val exportService: ExportService? = null,

    // ── Observability ────────────────────────────────────────────────────────
    val histogramWriter: HistogramWriter? = null,
    val bugReportBuilder: BugReportBuilder? = null,
    val debugFlagRepository: DebugFlagRepository? = null,
    val ringBuffer: RingBufferSpanExporter? = null,

    // ── Git sync ─────────────────────────────────────────────────────────────
    val activeGitSyncService: StateFlow<GitSyncService?> = MutableStateFlow(null),
    val activeGraphIdProvider: () -> String? = { null },
    val onDismissGitDetection: (suspend (graphId: String) -> Unit)? = null,

    // ── Sections ─────────────────────────────────────────────────────────────
    val onSectionsLoaded: (suspend (dev.stapler.stelekit.sections.SectionManifest, Map<String, dev.stapler.stelekit.sections.SectionState>) -> Unit)? = null,
)
