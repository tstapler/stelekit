// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.projectbridge.ImmediateSketchDurability
import dev.stapler.stelekit.projectbridge.NoPageLifecycle
import dev.stapler.stelekit.projectbridge.PageLifecycle
import dev.stapler.stelekit.projectbridge.ProjectBridgeDeps
import dev.stapler.stelekit.platform.BrowserPersistence
import dev.stapler.stelekit.platform.UnsupportedBrowserPersistence
import dev.stapler.stelekit.projectbridge.EngineChangeBus
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.SketchDurability
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.SheetFileSchemaProbe
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarSlugLock
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.v2Storage
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.sketch.constraints.SolverTarget
import dev.stapler.stelekit.sketch.constraints.SolverTierFacts
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetLoad
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetLoader
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetTarget
import dev.stapler.stelekit.sketch.v2.runtime.session.ClassicNoticeLedger
import dev.stapler.stelekit.sketch.v2.runtime.split.SheetSplitter
import dev.stapler.stelekit.sketch.v2.runtime.split.SplitOutcome
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseHolder
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseRefusal
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLease
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLeaseRegistry
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchErrorState
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.ui.sketch.NewSheetKind
import dev.stapler.stelekit.ui.sketch.NoEditorReason
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.UpgradeAffordance
import dev.stapler.stelekit.ui.sketch.v2.migration.ClassicNoticeDecision
import dev.stapler.stelekit.ui.sketch.v2.migration.warningForm
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchErrorStrings
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** One open v2 sheet: the controller, the diagnostics the saver raises into and the lease it holds. Close it when the editor leaves. */
class SketchEditorSession(
    val controller: SketchEditorController,
    val diagnostics: DiagnosticsHostState,
    val title: String,
    private val lease: SheetLease?,
    /** The project slug and sheet this session edits; empty for a session built without them (a test). */
    val slug: String = "",
    val sheet: SheetUuid? = null,
    /** The platform seams of Import, Export, the image assets and the library (Task 6.2.0d). */
    val services: HostServices = HostServices(),
) {
    /** The host's extras while it is mounted (set by [SketchEditorV2Host]); a test reaches the surfaces' state through it. */
    var extras: EditorExtras? = null
        internal set

    fun close() {
        controller.close()
        lease?.release()
    }

    /**
     * Leaving the sheet: the latest edits are written first (the saver's own scope dies with [close], and its debounce would drop
     * them), then the session closes. [discard] closes at once, leaving the sheet as last saved (the user's choice, UX-72).
     * Runs on its own scope: it outlives the composition that asked for it.
     */
    fun closeAfterSave(discard: Boolean) {
        if (discard || controller.readOnly) {
            close()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> close() }).launch {
            try {
                controller.flush()
            } finally {
                close()
            }
        }
    }
}

/** What opening a project's sheet decided; nothing here writes. */
sealed interface EditorOpening {
    /** A classic sheet (or no sheet yet): the legacy editor opens it. */
    data object Legacy : EditorOpening

    /** The platform storage check is still running (UX-141). */
    data object Pending : EditorOpening

    class NoEditor(val reason: NoEditorReason, val sketchName: String, val projectName: String, val slug: String? = null) : EditorOpening

    /** The v2 editor; [readOnly] shows the sheet without edits and with the router's banner. */
    class Editor(val session: SketchEditorSession) : EditorOpening

    /**
     * The classic editor for a project the new editor could own: [sheet] is its classic sheet (null for a project that has none
     * yet), [affordance] says whether the Upgrade chip is offered, [createdNotice] the "Created as classic." toast to show once.
     */
    class ClassicEditor(
        val projectUuid: ProjectUuid,
        val slug: String,
        val projectName: String,
        val sheet: SheetUuid?,
        val affordance: UpgradeAffordance,
        val createdNotice: ClassicNoticeDecision.Toast?,
    ) : EditorOpening

    /** A new sketch project whose first sheet would be v2: the first-write dialog must be confirmed before anything is written. */
    class NewSheetNeedsConfirm(val projectUuid: ProjectUuid, val slug: String, val projectName: String, val form: SketchConsentCopy.WarningForm) : EditorOpening
}

/** The seams the opener reads. The slug lock is the one the saver writes under. */
class V2EditorDeps(
    val repos: SketchRepositories,
    val store: () -> ProjectSidecarStore?,
    val leases: SheetLeaseRegistry?,
    val settings: ProjectBridgeSettings?,
    val graphId: () -> String?,
    val writeActor: DatabaseWriteActor? = null,
    val slugLock: SidecarSlugLock = SidecarSlugLock(),
    val clockMillis: () -> Long = { 0L },
    val keymap: KeymapEnv = KeymapEnv(isMac = false, isWeb = false),
    val touchPresent: Boolean = false,
    val limits: SceneLimits = SceneLimits.Default,
    val log: SketchLog = SketchLog(),
    val onboarding: OnboardingSequencer? = null,
    /** Waits until a saved sheet reaches storage (the web root's OPFS queue); immediate elsewhere. */
    val durability: SketchDurability = ImmediateSketchDurability,
    /** The hidden-page flush request every open saver collects; silent elsewhere. */
    val lifecycle: PageLifecycle = NoPageLifecycle,
    /** The device-local consent records; null leaves every project classic (Task 6.2.0c). */
    val acks: ProjectAckStore? = null,
    val classicNotices: ClassicNoticeLedger? = null,
    val quality: QualitySignals? = null,
    val persistence: BrowserPersistence = UnsupportedBrowserPersistence,
    val warningForm: () -> SketchConsentCopy.WarningForm = { SketchConsentCopy.WarningForm.Full },
    val engineChange: EngineChangeBus = EngineChangeBus(),
    val newId: () -> String = { UuidGenerator.generateV7() },
    val epochDay: () -> Long = { clockMillis() / MILLIS_PER_DAY },
    /** What the platform root adds for Import, Export, the image assets and the library. */
    val platform: HostPlatform = HostPlatform(),
)

private const val MILLIS_PER_DAY = 86_400_000L
private const val NOT_SAVED = "not-saved"

/**
 * Resolves what a project's first sheet opens in (Story 6.2.0b): the router's outcome for a v2 file, the legacy editor for anything
 * else. A v2 outcome that edits takes the sheet's edit lease first, so the classic editor can never open the same sheet.
 */
class V2EditorOpener(
    private val deps: V2EditorDeps,
    private val context: CoroutineContext,
    /** Where the controller adopts results the canvas reads; the UI dispatcher in the app. */
    private val adoption: CoroutineContext = context,
) {
    private val entry = V2EntryDecider(deps)

    suspend fun open(projectUuid: ProjectUuid?, metrics: TextMetricsProvider): EditorOpening = open(projectUuid, metrics, allowCreate = true)

    /** The kind of sheet [projectUuid]'s next sheet would be (the `NewSketchProject` predicate decides for a project that already has sheets). */
    suspend fun nextSheetKind(projectUuid: ProjectUuid): NewSheetKind? {
        val project = deps.repos.projects.loadProject(projectUuid).getOrNull()?.project ?: return null
        val store = deps.store() ?: return null
        return entry.newSheetKind(project, store)
    }

    /** Creates the project's first sheet as v2 after the first-write flow confirmed, then opens it. */
    suspend fun createAndOpen(projectUuid: ProjectUuid, metrics: TextMetricsProvider): EditorOpening =
        entry.createSheet(projectUuid).fold({ EditorOpening.Legacy }, { open(projectUuid, metrics, allowCreate = false) })

    private suspend fun open(projectUuid: ProjectUuid?, metrics: TextMetricsProvider, allowCreate: Boolean): EditorOpening {
        val repos = deps.repos
        val uuid = projectUuid ?: repos.projects.getProjects(1, 0).first().getOrNull()?.firstOrNull()?.uuid ?: return EditorOpening.Legacy
        val project = repos.projects.loadProject(uuid).getOrNull()?.project ?: return EditorOpening.Legacy
        val sheet = repos.sheets.getSheets(uuid, 1).first().getOrNull()?.items?.firstOrNull()
        val store = deps.store() ?: return EditorOpening.Legacy
        if (sheet == null) return openNew(project, store, metrics, allowCreate)
        val isV2 = sheet.schemaVersion == SheetPaths.SCHEMA_V2 || SheetFileSchemaProbe(store).isV2(project.slug, sheet.uuid)
        if (!isV2) return entry.forClassicSheet(project, sheet, store)
        return openV2(OpenTarget(store, project.slug, project.name, uuid, sheet.uuid), metrics)
    }

    private suspend fun openNew(project: SketchProject, store: ProjectSidecarStore, metrics: TextMetricsProvider, allowCreate: Boolean): EditorOpening =
        when (val decision = entry.forNewProject(project, store)) {
            is EntryDecision.Show -> decision.opening
            EntryDecision.CreateNow -> if (allowCreate) createAndOpen(project.uuid, metrics) else EditorOpening.Legacy
        }

    /** Everything that names the one sheet being opened. */
    private class OpenTarget(val store: ProjectSidecarStore, val slug: String, val projectName: String, val project: ProjectUuid, val sheet: SheetUuid)

    private suspend fun openV2(target: OpenTarget, metrics: TextMetricsProvider): EditorOpening {
        val capabilities = V2Capabilities.of(target.store.v2Storage())
        val loaded = SheetLoader.load(target.store, target.slug, target.sheet, deps.limits)
        val schema = if (loaded is SheetLoad.NewerSchema) SheetSchema.Newer else SheetSchema.V2
        val stored = deps.graphId()?.let { deps.settings?.v2Mode(it) } ?: V2Mode.Off
        return when (val outcome = SketchEditorRouter.route(RouteInput(stored, capabilities, schema))) {
            RouteOutcome.Pending -> EditorOpening.Pending
            is RouteOutcome.NoEditor -> EditorOpening.NoEditor(outcome.reason, target.sheet.value, target.projectName, target.slug)
            is RouteOutcome.Legacy, RouteOutcome.ConvertThenV2 -> EditorOpening.Legacy
            is RouteOutcome.V2ReadOnly -> editor(loaded, target, metrics, readOnlyReason = outcome.reason, lease = null)
            is RouteOutcome.LeaseConflict -> editor(loaded, target, metrics, readOnlyReason = SketchReasonText.OTHER_TAB, lease = null)
            RouteOutcome.V2Editable -> editable(loaded, target, metrics)
        }
    }

    private suspend fun editable(loaded: SheetLoad, target: OpenTarget, metrics: TextMetricsProvider): EditorOpening {
        val leases = deps.leases ?: return editor(loaded, target, metrics, readOnlyReason = null, lease = null)
        return when (val granted = leases.acquire(target.sheet, LeaseHolder.V2)) {
            is Either.Right -> editor(loaded, target, metrics, readOnlyReason = null, lease = granted.value)
            is Either.Left -> {
                val reason = if (granted.value is LeaseRefusal.SheetBusy) SketchErrorStrings.message(SketchErrorState.SheetBusy) else SketchReasonText.OTHER_TAB
                editor(loaded, target, metrics, readOnlyReason = reason, lease = null)
            }
        }
    }

    /**
     * Adds a sheet holding a document beside the open one ("Split into new sketch", "Import into a new sketch"): the open sheet's
     * pending edits are saved first, and the splitter then writes the new sheet under the slug lock. Left is the reason token.
     */
    private fun newSheetCreator(controller: SketchEditorController, target: OpenTarget): suspend (SketchDocumentV2) -> Either<String, Unit> = { document ->
        if (controller.flush().hasUnsavedWork) {
            Either.Left(NOT_SAVED)
        } else {
            val splitter = SheetSplitter(deps.repos, target.store, deps.slugLock, deps.writeActor, deps.log, deps.limits)
            when (val outcome = splitter.addSheet(target.project, target.sheet, document, SheetUuid(deps.newId()))) {
                is SplitOutcome.Split -> Either.Right(Unit)
                is SplitOutcome.Failed -> Either.Left(outcome.reason.token)
            }
        }
    }

    private fun editor(loaded: SheetLoad, target: OpenTarget, metrics: TextMetricsProvider, readOnlyReason: String?, lease: SheetLease?): EditorOpening {
        // A v2 sheet whose file is absent (never opened as new: an unreferenced file on disk could be overwritten) shows read-only.
        val missing = loaded is SheetLoad.Missing
        val readOnly = if (missing) SketchReasonText.FILE_MISSING else readOnlyReason
        val held = if (missing) null.also { lease?.release() } else lease
        val (doc, sha) = when (loaded) {
            is SheetLoad.Loaded -> loaded.doc to loaded.sha
            SheetLoad.Missing -> SketchDocumentV2(sheetUuid = target.sheet.value) to null
            else -> return EditorOpening.Legacy.also { held?.release() }
        }
        val port = InMemoryDiagnosticsPort(SketchEditorStrings.Banners, deps.limits)
        val saver = if (readOnly == null) {
            SheetSaver(
                SheetTarget(target.slug, target.project, target.sheet, sha),
                SheetSaverPorts(
                    deps.repos, target.store, deps.slugLock, port, log = deps.log, writeActor = deps.writeActor,
                    durability = deps.durability, lifecycle = deps.lifecycle,
                ),
                limits = deps.limits,
                context = context,
            )
        } else {
            null
        }
        var counter = 0
        val ids = ElementIdSource { ElementId("e-${UuidGenerator.generateV7()}-${counter++}") }
        val controller = SketchEditorController(
            doc,
            EditorPorts(
                tools = StandardToolRegistry.create(metrics, deps.limits, deps.touchPresent),
                saver = saver,
                ids = ids,
                limits = deps.limits,
                log = deps.log,
                clockMillis = deps.clockMillis,
                keymap = deps.keymap,
                touchPresent = deps.touchPresent,
                sequencer = deps.onboarding,
                quality = deps.quality,
            ),
            context,
            readOnly = readOnly != null,
            adoption = adoption,
        )
        val diagnostics = DiagnosticsHostState(port, controller.notices, deps.clockMillis)
        readOnly?.let { port.raise(BannerRowId.ReadOnly, it) }
        val solverTarget = if (deps.keymap.isWeb) SolverTarget.WasmJs else SolverTarget.Jvm
        val newSheet = if (readOnly == null) newSheetCreator(controller, target) else null
        val services = HostServices.of(deps.platform, { target.store.v2Storage() }, deps.limits, createSheet = newSheet) { SolverTierFacts.Recorded.tierOf(solverTarget) }
        return EditorOpening.Editor(SketchEditorSession(controller, diagnostics, target.projectName, held, target.slug, target.sheet, services))
    }
}

/** What the v2 editor needs from the project bridge: its store, leases, mode settings, durability and page lifecycle. */
fun ProjectBridgeDeps.v2EditorDeps(
    repos: SketchRepositories,
    writeActor: DatabaseWriteActor?,
    graphId: () -> String?,
    keymap: KeymapEnv,
    touchPresent: Boolean,
    onboarding: OnboardingSequencer? = null,
    platform: HostPlatform = HostPlatform(),
): V2EditorDeps = V2EditorDeps(
    acks = acks,
    classicNotices = classicNotices,
    quality = quality,
    log = SketchLog(signals = quality),
    persistence = browserPersistence,
    warningForm = { sessionConfirmation.warningForm },
    engineChange = engineChange,
    repos = repos,
    store = sidecarStore,
    leases = sheetLeases,
    settings = settings,
    graphId = graphId,
    writeActor = writeActor,
    clockMillis = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    keymap = keymap,
    touchPresent = touchPresent,
    durability = sketchDurability,
    lifecycle = pageLifecycle,
    onboarding = onboarding,
    platform = platform,
)
