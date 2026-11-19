// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/**
 * The ONE source of the old-version and whole-project consent wording (UX-103, Task 1.4.6f): the flag-enable
 * confirmation, the first-write dialog, the Upgrade chip's Details sheet and the post-conversion banner's Details are all
 * built from these constants, so the wording changes in exactly one place. Every sentence says "the new editor" and
 * "the classic editor"; "v2" is an internal name and never appears here.
 */
object SketchConsentCopy {
    const val WHOLE_PROJECT_WARNING =
        "Once a sketch is upgraded, older versions of SteleKit (including another device you have not updated) " +
            "cannot open ANY sketch in that project."

    /** The new-project form used where nothing is being upgraded yet (the New sketch projects only confirmation). */
    const val WHOLE_PROJECT_NEW_PROJECT_FORM =
        "Older versions of SteleKit (including another device you haven't updated) can't open a project that " +
            "contains a new-editor sketch."

    /** The already-true form of the read-only banner's [Use new] confirmation: its sketch is already a new-editor sketch. */
    const val WHOLE_PROJECT_ALREADY_TRUE =
        "Older versions of SteleKit (including another device you haven't updated) already can't open this project, " +
            "because it contains a new-editor sketch."

    const val OTHER_DEVICES_DEFINITION = "\"Other devices\" means any SteleKit that has not been updated."

    /** The text of the Upgrade chip's Details sheet (no project name: the chip does not name one). */
    const val UPGRADE_DETAILS =
        "This sketch can be upgraded to the new editor. Upgrading stops older SteleKit versions from opening ANY sketch " +
            "in this project."

    fun backupSentence(backupPath: String): String = "A backup of the original is kept in $backupPath."

    /** The project-naming sentence of the first-write dialog, below [WHOLE_PROJECT_WARNING]. */
    fun projectSentence(projectName: String): String =
        "The whole sketch project \"$projectName\" won't open on those versions, not just this sketch."

    /** Shown instead of the two warning paragraphs right after an enable confirmation in the same app session. */
    fun projectReference(projectName: String): String =
        "As you confirmed in Settings: older versions of SteleKit can't open the whole sketch project \"$projectName\" " +
            "once it holds a new-editor sketch."

    /** Whether the first-write dialog shows the full warning or the by-reference sentence (right after an enable confirmation). */
    enum class WarningForm { Full, Reference }

    private fun warning(projectName: String, form: WarningForm): List<String> = when (form) {
        WarningForm.Full -> listOf(WHOLE_PROJECT_WARNING, projectSentence(projectName))
        WarningForm.Reference -> listOf(projectReference(projectName))
    }

    /** The first-write dialog's text blocks, in reading order, for the convert variant. */
    fun convertWarning(projectName: String, backupPath: String, form: WarningForm): List<String> =
        warning(projectName, form) + backupSentence(backupPath)

    /** The first-write dialog's text blocks for a NEW sketch (nothing converts, so no backup sentence). */
    fun newSheetWarning(projectName: String, form: WarningForm): List<String> = warning(projectName, form)

    /** The Details of the post-conversion banner: the backup sentence and the whole-project warning. */
    fun postConversionDetails(backupPath: String): String = "${backupSentence(backupPath)} $WHOLE_PROJECT_WARNING"

    /** The Details of the Upgrade chip: the consent sentence and the definition of "other devices". */
    const val CHIP_DETAILS = "$UPGRADE_DETAILS $OTHER_DEVICES_DEFINITION"

    /** The web build's durability sentences of the first-write dialog (UX-139, UX-148, UX-151). */
    object WebStorage {
        const val PERSISTENT =
            "New-editor sketches are saved in this browser's private storage. The browser can clear it, so back it up with git " +
                "sync or a linked folder."
        const val SESSION_ONLY =
            "This graph is open temporarily: its new-editor sketches are kept only until you close or reload this page."
        const val PRIVATE_WINDOW = "This looks like a private window: new-editor sketches are kept only until you close it."
        const val PERSIST_PROMPT = "Your browser may ask to keep this site's storage."

        /** The one line a dialog shows: a temporary graph always gets [SESSION_ONLY], whatever the window (UX-151). */
        fun line(sessionOnly: Boolean, privateSuspected: Boolean): String = when {
            sessionOnly -> SESSION_ONLY
            privateSuspected -> PRIVATE_WINDOW
            else -> PERSISTENT
        }
    }

    /** One flag-enable confirmation: its title, its paragraphs and the label of its confirming button. */
    data class Confirmation(val title: String, val paragraphs: List<String>, val confirmLabel: String) {
        /** Every sentence, for tests and the accessible description. */
        val fullText: String get() = (listOf(title) + paragraphs).joinToString(" ")
    }

    /** `On`: "Turn on the new editor for all sketches?". */
    val EnableOn = Confirmation(
        title = "Turn on the new editor for all sketches?",
        paragraphs = listOf(
            "New sketches use it and each classic sketch gets an Upgrade offer. Nothing converts until you choose Upgrade on a " +
                "sketch, and a backup is kept.",
            WHOLE_PROJECT_WARNING,
        ),
        confirmLabel = "Turn on",
    )

    /** `NewProjectsOnly`. */
    val EnableNewProjectsOnly = Confirmation(
        title = "Turn on the new editor for new projects?",
        paragraphs = listOf(
            "Sketches in new sketch projects, and in projects that already hold only new-editor sketches, use it. Other " +
                "existing sketches and projects stay classic and nothing is converted.",
            WHOLE_PROJECT_NEW_PROJECT_FORM,
        ),
        confirmLabel = "Turn on",
    )

    /** The read-only banner's [Use new] (`ReadOnlyUseNew`): its sketch already uses the new editor. */
    val ReadOnlyUseNew = Confirmation(
        title = "Edit this sketch with the new editor?",
        paragraphs = listOf(
            "This sketch already uses the new editor, so it is read-only until the new editor is on. Turning it on reopens this " +
                "sketch so you can edit it. New sketches in new projects will also use it. Classic sketches stay classic and " +
                "nothing is converted.",
            WHOLE_PROJECT_ALREADY_TRUE,
        ),
        confirmLabel = "Turn on and reopen",
    )

    /** The Details of the read-only banner while the build cannot turn the mode on (D-99): there is no primary action. */
    const val READ_ONLY_CANNOT_TURN_ON = "This version of SteleKit can't edit new-editor sketches yet."

    /** The lines of the in-app "Learn what changes" page (Task 1.4.6c). */
    val LearnWhatChanges: List<String> = listOf(
        "What upgrades: each classic sketch you choose Upgrade on, one at a time. Nothing converts when you open a sketch or turn the new editor on.",
        WHOLE_PROJECT_WARNING,
        "A backup of each original is kept in the backup folder of its sketch project, and Settings > Sketches can restore it.",
        "To go back, choose Off. New-editor sketches then open read-only, and Restore original brings back the classic copy.",
        "This setting is for this graph on this device only. Another device starts with the new editor off until you set it there.",
        OTHER_DEVICES_DEFINITION,
        "A project is the sketches saved together under one project name. A project you empty of sketches counts as an existing project again.",
        "New sketches use the new editor: in every project (All sketches), only in new projects and projects that already hold only " +
            "new-editor sketches (New sketch projects only), or never (Off).",
    )
}
