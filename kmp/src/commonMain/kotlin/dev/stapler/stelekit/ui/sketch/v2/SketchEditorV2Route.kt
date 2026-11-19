// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.FileProjectSidecarStore
import dev.stapler.stelekit.projectbridge.V2Build
import dev.stapler.stelekit.ui.sketch.rememberModernPreviewEnvironment
import dev.stapler.stelekit.ui.sketch.v2.migration.EditorOpen
import dev.stapler.stelekit.ui.sketch.v2.migration.EngineChangeFlow
import dev.stapler.stelekit.ui.sketch.v2.migration.LastBackupExporter
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorAction
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorDetails
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorModel
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorScreen
import dev.stapler.stelekit.ui.sketch.v2.migration.PendingModel
import dev.stapler.stelekit.ui.sketch.v2.migration.PendingScreen
import dev.stapler.stelekit.ui.sketch.v2.migration.ReleasesPageUpdateCheck
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.launch

/**
 * The flush of the classic editor that is open, set by the classic editor's call site: a conversion saves its pending edits
 * before the sheet changes under it and never reads the classic view model itself.
 */
class LegacyFlushHandle {
    var flush: suspend () -> Unit = {}
}

/**
 * Where the app opens a project's sheet (Story 6.2.0b, Task 6.2.0c): the router's outcome for the sheet decides between the v2
 * editor ([SketchEditorV2Host]), the no-editor and pending screens, the first-write dialog of a new v2 sketch, and [legacy] for a
 * classic sheet (with the Upgrade chip above it when the router offers one). [legacy] is composed only for a classic outcome, so
 * the classic editor never takes the edit lease of a sheet the new editor holds. With no [deps] (a platform without the sketch
 * project bridge) the classic editor opens as it always did. Nothing here writes a v2 file before the first-write dialog was
 * confirmed.
 */
@Composable
fun SketchEditorV2Route(
    deps: V2EditorDeps?,
    projectUuid: ProjectUuid?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    pointer: PointerClass = PointerClass.MouseOnly,
    legacyFlush: LegacyFlushHandle = remember { LegacyFlushHandle() },
    legacy: @Composable () -> Unit,
) {
    val environment = rememberModernPreviewEnvironment()
    val opener = remember(deps) { deps?.let { V2EditorOpener(it, Dispatchers.Default, PlatformDispatcher.Main) } }
    val actions = remember(deps) { deps?.let { V2EntryActions(it) } }
    var attempt by remember(projectUuid) { mutableIntStateOf(0) }
    var opening by remember(projectUuid, deps) { mutableStateOf<EditorOpening?>(if (deps == null) EditorOpening.Legacy else null) }
    var busy by remember(projectUuid, deps) { mutableStateOf(false) }
    var dialog by remember(projectUuid, deps) { mutableStateOf<EntryDialog?>(null) }
    var converted by remember(projectUuid, deps) { mutableStateOf<SheetUuid?>(null) }
    var engineChanged by remember(projectUuid, deps) { mutableStateOf(false) }
    val frameClock = compositionFrameClock()
    val scope = remember(deps) {
        routeScope(frameClock, deps?.log) {
            busy = false
            dialog = null
            attempt += 1
        }
    }
    val current by rememberUpdatedState(opening)
    LaunchedEffect(projectUuid, deps, attempt) {
        opener?.let {
            opening = null
            opening = it.open(projectUuid, environment.metrics)
            busy = false
        }
    }
    val flow = remember(deps) {
        EngineChangeFlow(
            savePending = { (current as? EditorOpening.Editor)?.session?.controller?.flush() ?: legacyFlush.flush() },
            reopenSketch = { engineChanged = false; attempt += 1 },
        )
    }
    LaunchedEffect(opening, deps) {
        val bus = deps?.engineChange ?: return@LaunchedEffect
        val base = bus.revision.value
        bus.revision.collect { if (it != base && flow.onModeChanged(EditorOpen.Yes) != null) engineChanged = true }
    }
    val hooks = remember(projectUuid, deps) {
        EntryHooks(onBack, { opening = it }, { dialog = it }, { busy = it }, { converted = it }, { attempt += 1 }, legacyFlush)
    }
    val steps = remember(actions, opener) { if (actions == null || opener == null) null else EntrySteps(actions, opener, environment.metrics, hooks) }
    val restore = remember(actions) {
        actions?.let { a ->
            RestoreBackupAction { slug, sheet ->
                (current as? EditorOpening.Editor)?.session?.controller?.flush()
                busy = true
                withFrameNanos { }
                a.restore(slug, sheet)
            }
        }
    }
    LaunchedEffect(opening) {
        val shown = opening
        if (shown is EditorOpening.NewSheetNeedsConfirm && dialog == null) dialog = steps?.newSheetDialog(shown)
        val sheet = converted
        if (shown is EditorOpening.Editor && sheet != null && shown.session.sheet == sheet) {
            actions?.postConversion(shown.session, sheet)
            converted = null
        }
    }
    Box(modifier) {
        val stage = opening
        when {
            busy || stage == null || stage is EditorOpening.NewSheetNeedsConfirm -> SketchEditorLoading()
            stage === EditorOpening.Pending -> PendingScreen(PendingModel.at(0), onClose = onBack)
            stage is EditorOpening.NoEditor -> NoEditor(stage, deps?.platform ?: HostPlatform(), onBack, onReload = { attempt += 1 })
            stage is EditorOpening.Editor -> EditorStage(stage.session, environment, onBack, if (deps?.touchPresent == true) PointerClass.TouchOrPen else pointer, actions, restore)
            else -> if (stage is EditorOpening.ClassicEditor && actions != null && deps != null) {
                ClassicWithOffer(stage, actions, deps, onStart = { steps?.start(scope, stage) }, legacy = legacy)
            } else {
                legacy()
            }
        }
        if (engineChanged && !busy) {
            EngineChangeRow({ scope.launch { flow.reopen() } }, Modifier.align(Alignment.BottomCenter))
        }
    }
    EntryDialogs(dialog, steps, scope, restore, hooks)
}

/**
 * The scope of the route's multi-step work (a conversion, a restore, a first write). It is owned, not the composition's: leaving the
 * screen mid-conversion does not cancel it, so a started step finishes and reports. It carries the composition's frame clock (the
 * steps wait a frame to let the classic editor leave) and a handler, so a throwable ends in [onFailure] and a log line, never in the
 * platform's uncaught-exception handler (which kills an Android process).
 */
private fun routeScope(frameClock: MonotonicFrameClock?, log: SketchLog?, onFailure: () -> Unit): CoroutineScope {
    val handler = CoroutineExceptionHandler { _, t ->
        if (t !is CancellationException) {
            log?.persistFailed(t::class.simpleName.orEmpty())
            onFailure()
        }
    }
    return CoroutineScope(SupervisorJob() + PlatformDispatcher.Main + (frameClock ?: EmptyCoroutineContext) + handler)
}

/** One editor session; its overflow menu carries the restore entry while the sheet has a backup. */
@Composable
private fun EditorStage(
    session: SketchEditorSession,
    environment: dev.stapler.stelekit.ui.sketch.ModernPreviewEnvironment,
    onBack: () -> Unit,
    pointer: PointerClass,
    actions: V2EntryActions?,
    restore: RestoreBackupAction?,
) {
    var hasBackup by remember(session) { mutableStateOf(false) }
    val sheet = session.sheet
    LaunchedEffect(session) { if (actions != null && sheet != null) hasBackup = actions.hasBackup(session.slug, sheet) }
    val entry = if (restore != null && sheet != null && !session.controller.readOnly) {
        val availability = RestoreAvailability.of(platformReason = null, buildCapped = !V2Build.CONVERSION_READY, hasBackup = hasBackup)
        RestoreMenuEntry(availability) { restore.request(session.slug, sheet) }
    } else {
        null
    }
    SketchEditorV2Host(session, environment, onBack, Modifier, pointer, restore = entry)
}

@Composable
private fun NoEditor(
    opening: EditorOpening.NoEditor,
    platform: HostPlatform,
    onBack: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val exporter = remember(opening, platform) { lastBackupExporterOf(platform, opening.slug) }
    val hasBackup = remember(exporter) { exporter?.hasBackup() == true }
    val model = remember(opening, hasBackup) { NoEditorModel.of(opening.reason, hasBackup) }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val updateCheck = remember(uriHandler) { ReleasesPageUpdateCheck(uriHandler::openUri) }
    val scope = rememberCoroutineScope()
    NoEditorScreen(
        model,
        onAction = { action ->
            when (action) {
                NoEditorAction.Close -> onBack()
                NoEditorAction.Reload -> onReload()
                NoEditorAction.CopyDetails ->
                    clipboard.setText(AnnotatedString(NoEditorDetails(opening.sketchName, opening.projectName, SCHEMA_V2, PLATFORM_UNKNOWN).text(model)))
                NoEditorAction.CheckForUpdates -> updateCheck.check()
                NoEditorAction.ExportLastBackup -> exporter?.let { scope.launch { it.export() } }
                else -> Unit
            }
        },
        modifier = modifier,
    )
}

/** The exporter of the newest v1 backup of the project [slug] names, or null where the host has no file system, notes folder or slug. */
internal fun lastBackupExporterOf(platform: HostPlatform, slug: String?): LastBackupExporter? {
    val fs = platform.fileSystem ?: return null
    val notes = platform.notesDirectory() ?: return null
    val project = slug ?: return null
    return LastBackupExporter(fs, "${notes.trimEnd('/')}/${FileProjectSidecarStore.ROOT_DIR}/$project/${FileProjectSidecarStore.BACKUP_DIR}")
}

private const val SCHEMA_V2 = "2"
private const val PLATFORM_UNKNOWN = ""

/** The composition's frame clock, read without binding the transient scope to a name the remember audit would track. */
@Composable
private fun compositionFrameClock(): MonotonicFrameClock? = rememberCoroutineScope().coroutineContext[MonotonicFrameClock]
