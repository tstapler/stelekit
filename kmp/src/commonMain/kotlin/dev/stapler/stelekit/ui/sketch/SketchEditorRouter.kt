// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import dev.stapler.stelekit.platform.SketchWriterLeaseState
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.projectbridge.isAvailable
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.sketch.v2.runtime.convert.HasTechnicalData

/** What the sheet's FILE classifies as (the database mirror is only a hint). */
enum class SheetSchema { V1, V2, Newer }

/** Where a sheet stands in the conversion flow; remembered per sheet in `Settings` for [Declined]. */
sealed interface ConversionState {
    data object NotConverted : ConversionState
    data object UpgradeRequested : ConversionState
    data object Declined : ConversionState
    data class Failed(val reason: ConversionFailure) : ConversionState
}

/** Why a sheet shows no editor at all (the no-editor screen of ux.md 14.7). */
sealed interface NoEditorReason {
    data object NewerSchema : NoEditorReason
    data class StorageUnavailable(val storage: V2Storage) : NoEditorReason
}

/** The one place the Upgrade chip decision is made: a chip, a disabled menu entry with its reason, or nothing. */
sealed interface UpgradeAffordance {
    data object None : UpgradeAffordance
    data object Chip : UpgradeAffordance
    data class Disabled(val reason: HasTechnicalData) : UpgradeAffordance
}

/** The editor the router chose. Nothing here ever writes. */
sealed interface RouteOutcome {
    /** The storage check has not finished (UX-141): never the no-editor screen. */
    data object Pending : RouteOutcome
    data class NoEditor(val reason: NoEditorReason) : RouteOutcome
    data class Legacy(val upgrade: UpgradeAffordance, val failure: ConversionFailure? = null) : RouteOutcome

    /** Mode On and the user asked to Upgrade: the first-write dialog, then conversion, then the v2 editor. */
    data object ConvertThenV2 : RouteOutcome
    data class V2ReadOnly(val reason: String) : RouteOutcome
    data object V2Editable : RouteOutcome

    /** Another tab holds the single-writer lease: read-only, no primary action. */
    data class LeaseConflict(val reasonId: String) : RouteOutcome
}

/** The facts one routing decision reads. [refusal] is `ConversionPolicy.refusal` for this sheet, null when none. */
data class RouteInput(
    val storedMode: V2Mode,
    val capabilities: V2Capabilities,
    val schema: SheetSchema,
    val conversion: ConversionState = ConversionState.NotConverted,
    val refusal: HasTechnicalData? = null,
    val lease: SketchWriterLeaseState = SketchWriterLeaseState.Held,
)

/** The kind of a NEW sheet and, when classic, why. */
sealed interface NewSheetKind {
    data object V2 : NewSheetKind
    data class Classic(val reason: ClassicReason) : NewSheetKind
}

/** Pure routing over (mode, schema, conversionState, storage): precedence is storage-unavailable first (UX-136). */
object SketchEditorRouter {
    const val OTHER_TAB_ID = "OtherTab"

    fun route(input: RouteInput): RouteOutcome {
        if (input.schema == SheetSchema.Newer) return RouteOutcome.NoEditor(NoEditorReason.NewerSchema)
        val storage = input.capabilities.storage
        if (input.schema == SheetSchema.V2) {
            when {
                storage == V2Storage.Checking -> return RouteOutcome.Pending
                !storage.isAvailable -> return RouteOutcome.NoEditor(NoEditorReason.StorageUnavailable(storage))
            }
        }
        val mode = input.capabilities.effective(input.storedMode)
        return when (input.schema) {
            SheetSchema.V1 -> routeV1(mode, input)
            else -> routeV2(mode, input.lease)
        }
    }

    private fun routeV1(mode: V2Mode, input: RouteInput): RouteOutcome {
        if (mode != V2Mode.On) return RouteOutcome.Legacy(UpgradeAffordance.None)
        return when (val state = input.conversion) {
            ConversionState.Declined -> RouteOutcome.Legacy(UpgradeAffordance.None)
            is ConversionState.Failed -> RouteOutcome.Legacy(UpgradeAffordance.None, state.reason)
            ConversionState.UpgradeRequested ->
                if (input.refusal == null) RouteOutcome.ConvertThenV2 else RouteOutcome.Legacy(UpgradeAffordance.Disabled(input.refusal))
            ConversionState.NotConverted ->
                RouteOutcome.Legacy(input.refusal?.let(UpgradeAffordance::Disabled) ?: UpgradeAffordance.Chip)
        }
    }

    private fun routeV2(mode: V2Mode, lease: SketchWriterLeaseState): RouteOutcome = when {
        mode == V2Mode.Off -> RouteOutcome.V2ReadOnly(SketchReasonText.READ_ONLY_TURN_ON)
        lease == SketchWriterLeaseState.Held -> RouteOutcome.V2Editable
        lease == SketchWriterLeaseState.Acquiring -> RouteOutcome.Pending
        else -> RouteOutcome.LeaseConflict(OTHER_TAB_ID)
    }

    /**
     * The kind of the sheet the user creates now. [projectIsNew] is the `NewSketchProject` verdict (the predicate, or
     * true for a project created by this very action). `SheetSplitter` never calls this.
     */
    fun newSheetKind(input: RouteInput, projectIsNew: Boolean): NewSheetKind {
        val storage = input.capabilities.storage
        if (!storage.isAvailable) return NewSheetKind.Classic(ClassicReason.Storage(storage))
        return when (input.capabilities.effective(input.storedMode)) {
            V2Mode.Off -> NewSheetKind.Classic(
                if (input.storedMode == V2Mode.Off) ClassicReason.ModeOff else ClassicReason.BuildCapped,
            )
            V2Mode.NewProjectsOnly ->
                if (projectIsNew) NewSheetKind.V2 else NewSheetKind.Classic(ClassicReason.ProjectNotNew)
            V2Mode.On ->
                if (input.refusal == null) NewSheetKind.V2 else NewSheetKind.Classic(ClassicReason.TechnicalData)
        }
    }

    /** The banner row of an outcome, or null; the router only raises it through [DiagnosticsPort], never touches a slot. */
    fun bannerRow(outcome: RouteOutcome): BannerRowId? = when (outcome) {
        is RouteOutcome.V2ReadOnly -> BannerRowId.ReadOnly
        is RouteOutcome.LeaseConflict -> BannerRowId.LeaseConflict
        else -> null
    }

    /** Raises the banner candidate of [outcome] (and resolves the other of the two) through [diagnostics]. */
    fun publish(outcome: RouteOutcome, diagnostics: DiagnosticsPort) {
        val row = bannerRow(outcome)
        for (candidate in listOf(BannerRowId.ReadOnly, BannerRowId.LeaseConflict)) {
            if (candidate != row) diagnostics.resolve(candidate)
        }
        when (outcome) {
            is RouteOutcome.V2ReadOnly -> diagnostics.raise(BannerRowId.ReadOnly, outcome.reason)
            is RouteOutcome.LeaseConflict -> diagnostics.raise(BannerRowId.LeaseConflict, SketchReasonText.OTHER_TAB)
            else -> Unit
        }
    }
}
