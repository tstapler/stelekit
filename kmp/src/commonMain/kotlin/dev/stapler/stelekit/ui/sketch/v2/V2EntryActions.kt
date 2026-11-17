// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.platform.PersistenceState
import dev.stapler.stelekit.platform.UnsupportedBrowserPersistence
import dev.stapler.stelekit.projectbridge.ProjectSidecarStore
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.v2Storage
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.ProjectAcknowledgements
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.SheetConverter
import dev.stapler.stelekit.sketch.v2.runtime.convert.SketchBackupRestore
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SheetOrigin
import dev.stapler.stelekit.ui.sketch.UpgradeAffordance
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteDialogModel
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteFlow
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteOutcome
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteVariant
import dev.stapler.stelekit.ui.sketch.v2.migration.PostConversionNotice
import dev.stapler.stelekit.ui.sketch.v2.migration.StartDecision
import dev.stapler.stelekit.ui.sketch.v2.migration.UpgradeOfferController
import dev.stapler.stelekit.ui.sketch.v2.migration.WebDurability

/**
 * The writes behind the creation and conversion UI of one open project (Task 6.2.0c), as plain suspend functions the route
 * calls from a click: the first-write flow, `SheetConverter`, the restore and the post-conversion notice. Nothing here draws.
 * Every v2 write of a project that has not acknowledged goes through [confirmFirstWrite] first.
 */
class V2EntryActions(private val deps: V2EditorDeps) {
    private val acks get() = deps.acks

    /** The Upgrade chip controller; null when this build has no sequencer or no acknowledgement store. */
    val offer: UpgradeOfferController? = deps.onboarding?.let { sequencer -> deps.acks?.let { UpgradeOfferController(sequencer, it) } }

    /** The model of the first-write dialog; the web build adds its durability sentences. */
    fun firstWriteModel(variant: FirstWriteVariant, projectName: String, backupSheet: SheetUuid?): FirstWriteDialogModel = FirstWriteDialogModel(
        variant = variant,
        projectName = projectName,
        backupPath = backupSheet?.let { backupPathOf(it) }.orEmpty(),
        form = deps.warningForm(),
        web = WebDurability(sessionOnly = false, privateSuspected = false, browserMayPrompt = true).takeIf { deps.persistence !== UnsupportedBrowserPersistence },
    )

    /** Stores the acknowledgement and asks the browser to keep storage, only for an outcome that acknowledges. */
    suspend fun confirmFirstWrite(outcome: FirstWriteOutcome, slug: String): PersistenceState? =
        acks?.let { FirstWriteFlow(it, deps.persistence).complete(outcome, slug) }

    /** [UpgradeOfferController.start]: the refusal is re-read, then the acknowledgement decides between the dialog and conversion. */
    suspend fun start(slug: String): StartDecision = offer?.start(slug) { null } ?: StartDecision.NeedsFirstWriteDialog

    /** Converts [sheet] of the project; a converted sheet is counted once as a conversion, never as authored. */
    suspend fun convert(project: ProjectUuid, sheet: SheetUuid): ConversionOutcome {
        val store = deps.store() ?: return ConversionOutcome.Failed(ConversionFailure.StorageUnavailable)
        return converter(store).convert(project, sheet).also { if (it is ConversionOutcome.Converted) deps.quality?.sheetCreated(sheet.value, SheetOrigin.Converted) }
    }

    private fun converter(store: ProjectSidecarStore) = SheetConverter(
        repos = deps.repos,
        store = store,
        slugLock = deps.slugLock,
        acknowledgements = acks ?: ProjectAcknowledgements { false },
        conversionReady = { V2Capabilities.of(store.v2Storage()).conversionReady },
        writeActor = deps.writeActor,
        log = deps.log,
        limits = deps.limits,
    )

    /** Puts a converted sheet back to its backup; the open editor must have been flushed and closed first. */
    suspend fun restore(slug: String, sheet: SheetUuid): RestoreOutcome {
        val store = deps.store() ?: return RestoreOutcome.Failed(RestoreFailure.StorageUnavailable)
        return SketchBackupRestore(deps.repos, store, deps.slugLock, deps.writeActor, deps.log).restore(slug, sheet)
    }

    /** True when a v1 backup of [sheet] exists, which is what the restore action needs. */
    suspend fun hasBackup(slug: String, sheet: SheetUuid): Boolean = deps.store()?.readBackup(slug, sheet.value) != null

    /** Raises the once-per-sheet "Upgraded. Backup saved." banner in [session]; returns whether it was raised. */
    fun postConversion(session: SketchEditorSession, sheet: SheetUuid): Boolean =
        acks?.let { PostConversionNotice(session.diagnostics.port, it).onConverted(sheet.value, backupPathOf(sheet)) } ?: false

    fun chipOffered(affordance: UpgradeAffordance): Boolean = offer != null && affordance == UpgradeAffordance.Chip

    private fun backupPathOf(sheet: SheetUuid) = "$BACKUP_DIR/${sheet.value}$BACKUP_SUFFIX"

    private companion object {
        const val BACKUP_DIR = "backup"
        const val BACKUP_SUFFIX = ".v1.json"
    }
}
