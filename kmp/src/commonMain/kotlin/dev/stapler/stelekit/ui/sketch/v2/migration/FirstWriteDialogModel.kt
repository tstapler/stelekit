// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import dev.stapler.stelekit.platform.BrowserPersistence
import dev.stapler.stelekit.platform.PersistenceState
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy.WarningForm
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

/** Which first-write dialog: a conversion of an existing sketch, or the creation of a NEW sketch (UX-64; D-35). */
enum class FirstWriteVariant { Convert, NewSheet }

/** The user's input to the dialog: the primary button, the left button, or Esc and back. */
enum class FirstWriteAction { Primary, Classic, Dismiss }

/** What the dialog decided. Only [acknowledges] outcomes store the per-project acknowledgement. */
enum class FirstWriteOutcome(val acknowledges: Boolean) {
    ConvertAndOpen(true),
    OpenClassic(false),
    CreateAndOpen(true),
    CreateClassic(false),

    /** Esc or back in the new-sheet variant: nothing is created, written or acknowledged. */
    Cancelled(false),
}

/** What the web build adds to the dialog (UX-139, UX-148, UX-151); null on every other platform. */
data class WebDurability(val sessionOnly: Boolean, val privateSuspected: Boolean, val browserMayPrompt: Boolean)

/**
 * The state of the first-write dialog: ONE checkbox that starts unchecked on every open and gates the primary button, the
 * text blocks from `SketchConsentCopy`, and the mapping from an action to an outcome. Pure; the composable only draws it.
 */
data class FirstWriteDialogModel(
    val variant: FirstWriteVariant,
    val projectName: String,
    val backupPath: String,
    val form: WarningForm = WarningForm.Full,
    val web: WebDurability? = null,
    val checked: Boolean = false,
) {
    val title: String
        get() = if (variant == FirstWriteVariant.Convert) SketchMigrationStrings.FirstWrite.CONVERT_TITLE else SketchMigrationStrings.FirstWrite.NEW_SHEET_TITLE

    /** The warning paragraphs, then the web durability sentence(s); the dialog's accessible description is these joined. */
    val blocks: List<String>
        get() {
            val warning = when (variant) {
                FirstWriteVariant.Convert -> SketchConsentCopy.convertWarning(projectName, backupPath, form)
                FirstWriteVariant.NewSheet -> SketchConsentCopy.newSheetWarning(projectName, form)
            }
            return warning + webLines()
        }

    val primaryLabel: String
        get() = if (variant == FirstWriteVariant.Convert) SketchMigrationStrings.FirstWrite.CONVERT_AND_OPEN else SketchMigrationStrings.FirstWrite.CREATE_AND_OPEN

    val classicLabel: String
        get() = if (variant == FirstWriteVariant.Convert) SketchMigrationStrings.FirstWrite.OPEN_CLASSIC else SketchMigrationStrings.FirstWrite.CREATE_CLASSIC

    val canConfirm: Boolean get() = checked

    /** Visible text under the checkbox row and the primary button's accessible description while the box is unchecked. */
    val disabledHint: String? get() = if (checked) null else SketchMigrationStrings.FirstWrite.CHECK_HINT

    fun withChecked(value: Boolean): FirstWriteDialogModel = copy(checked = value)

    /** The outcome of [action], or null when the primary button is pressed while it is disabled. */
    fun resolve(action: FirstWriteAction): FirstWriteOutcome? = when (action) {
        FirstWriteAction.Primary ->
            if (!checked) null else if (variant == FirstWriteVariant.Convert) FirstWriteOutcome.ConvertAndOpen else FirstWriteOutcome.CreateAndOpen
        FirstWriteAction.Classic -> if (variant == FirstWriteVariant.Convert) FirstWriteOutcome.OpenClassic else FirstWriteOutcome.CreateClassic
        FirstWriteAction.Dismiss -> if (variant == FirstWriteVariant.Convert) FirstWriteOutcome.OpenClassic else FirstWriteOutcome.Cancelled
    }

    private fun webLines(): List<String> {
        val durability = web ?: return emptyList()
        val line = SketchConsentCopy.WebStorage.line(durability.sessionOnly, durability.privateSuspected)
        return if (durability.browserMayPrompt) listOf(line, SketchConsentCopy.WebStorage.PERSIST_PROMPT) else listOf(line)
    }
}

/**
 * Applies a dialog outcome: the acknowledgement is stored, and `navigator.storage.persist()` requested, ONLY for an
 * [FirstWriteOutcome.acknowledges] outcome (the user gesture is the confirm click; nothing earlier asks the browser, UX-143).
 * Returns the persistence answer, or null when none was asked.
 */
class FirstWriteFlow(private val acks: ProjectAckStore, private val persistence: BrowserPersistence) {
    suspend fun complete(outcome: FirstWriteOutcome, slug: String): PersistenceState? {
        if (!outcome.acknowledges) return null
        val answer = persistence.request()
        acks.acknowledge(slug)
        return answer
    }
}
