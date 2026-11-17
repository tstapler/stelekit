// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.ImmediateSketchDurability
import dev.stapler.stelekit.projectbridge.NoPageLifecycle
import dev.stapler.stelekit.projectbridge.PageLifecycle
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
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetLoad
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetLoader
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaver
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetSaverPorts
import dev.stapler.stelekit.sketch.v2.runtime.persist.SheetTarget
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseHolder
import dev.stapler.stelekit.sketch.v2.runtime.session.LeaseRefusal
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLease
import dev.stapler.stelekit.sketch.v2.runtime.session.SheetLeaseRegistry
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchErrorState
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource
import dev.stapler.stelekit.sketch.v2.tools.StandardToolRegistry
import dev.stapler.stelekit.ui.sketch.NoEditorReason
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.RouteOutcome
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchErrorStrings
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.first
import kotlin.coroutines.CoroutineContext

/** One open v2 sheet: the controller, the diagnostics the saver raises into and the lease it holds. Close it when the editor leaves. */
class SketchEditorSession(
    val controller: SketchEditorController,
    val diagnostics: DiagnosticsHostState,
    val title: String,
    private val lease: SheetLease?,
) {
    fun close() {
        controller.close()
        lease?.release()
    }
}

/** What opening a project's sheet decided; nothing here writes. */
sealed interface EditorOpening {
    /** A classic sheet (or no sheet yet): the legacy editor opens it. */
    data object Legacy : EditorOpening

    /** The platform storage check is still running (UX-141). */
    data object Pending : EditorOpening

    class NoEditor(val reason: NoEditorReason, val sketchName: String, val projectName: String) : EditorOpening

    /** The v2 editor; [readOnly] shows the sheet without edits and with the router's banner. */
    class Editor(val session: SketchEditorSession) : EditorOpening
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
)

/**
 * Resolves what a project's first sheet opens in (Story 6.2.0b): the router's outcome for a v2 file, the legacy editor for anything
 * else. A v2 outcome that edits takes the sheet's edit lease first, so the classic editor can never open the same sheet.
 */
class V2EditorOpener(private val deps: V2EditorDeps, private val context: CoroutineContext) {
    suspend fun open(projectUuid: ProjectUuid?, metrics: TextMetricsProvider): EditorOpening {
        val repos = deps.repos
        val uuid = projectUuid ?: repos.projects.getProjects(1, 0).first().getOrNull()?.firstOrNull()?.uuid ?: return EditorOpening.Legacy
        val project = repos.projects.loadProject(uuid).getOrNull()?.project ?: return EditorOpening.Legacy
        val sheet = repos.sheets.getSheets(uuid, 1).first().getOrNull()?.items?.firstOrNull() ?: return EditorOpening.Legacy
        val store = deps.store()
        val isV2 = sheet.schemaVersion == SheetPaths.SCHEMA_V2 || store?.let { SheetFileSchemaProbe(it).isV2(project.slug, sheet.uuid) } == true
        if (!isV2 || store == null) return EditorOpening.Legacy
        return openV2(OpenTarget(store, project.slug, project.name, uuid, sheet.uuid), metrics)
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
            is RouteOutcome.NoEditor -> EditorOpening.NoEditor(outcome.reason, target.sheet.value, target.projectName)
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

    private fun editor(loaded: SheetLoad, target: OpenTarget, metrics: TextMetricsProvider, readOnlyReason: String?, lease: SheetLease?): EditorOpening {
        val (doc, sha) = when (loaded) {
            is SheetLoad.Loaded -> loaded.doc to loaded.sha
            SheetLoad.Missing -> SketchDocumentV2(sheetUuid = target.sheet.value) to null
            else -> return EditorOpening.Legacy.also { lease?.release() }
        }
        val port = InMemoryDiagnosticsPort(SketchEditorStrings.Banners, deps.limits)
        val saver = if (readOnlyReason == null) {
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
            ),
            context,
            readOnly = readOnlyReason != null,
        )
        val diagnostics = DiagnosticsHostState(port, controller.notices, deps.clockMillis)
        readOnlyReason?.let { port.raise(BannerRowId.ReadOnly, it) }
        return EditorOpening.Editor(SketchEditorSession(controller, diagnostics, target.projectName, lease))
    }
}
