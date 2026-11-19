// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.projectbridge.SessionConfirmation
import dev.stapler.stelekit.ui.sketch.v2.migration.warningForm
import dev.stapler.stelekit.ui.sketch.v2.migration.EnableVariant
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteDialogModel
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteVariant
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Task 1.4.6f (UX-103): the consent wording has one source, and each surface is built from it. */
class ConsentCopyTest {
    private val warning = SketchConsentCopy.WHOLE_PROJECT_WARNING

    private fun dialog(variant: FirstWriteVariant, form: SketchConsentCopy.WarningForm = SketchConsentCopy.WarningForm.Full) =
        FirstWriteDialogModel(variant, "Kitchen", "kitchen/backup/", form)

    @Test
    fun theThreeSurfaces_should_ContainTheWholeProjectWarningByteForByte() {
        assertTrue(warning in EnableVariant.On.copy.fullText)
        assertTrue(warning in dialog(FirstWriteVariant.Convert).blocks.joinToString(" "))
        assertTrue(warning in dialog(FirstWriteVariant.NewSheet).blocks.joinToString(" "))
        assertTrue(warning in SketchConsentCopy.postConversionDetails("kitchen/backup/"))
        assertTrue(warning in SketchConsentCopy.LearnWhatChanges)
    }

    @Test
    fun theBackupSurfaces_should_ContainTheBackupSentence_AndTheNewSheetDialogNone() {
        val sentence = SketchConsentCopy.backupSentence("kitchen/backup/")
        assertTrue(sentence in dialog(FirstWriteVariant.Convert).blocks)
        assertTrue(sentence in SketchConsentCopy.postConversionDetails("kitchen/backup/"))
        assertTrue(dialog(FirstWriteVariant.NewSheet).blocks.none { "backup" in it.lowercase() })
    }

    @Test
    fun theDialog_should_NameTheProject() {
        assertTrue(dialog(FirstWriteVariant.Convert).blocks.any { "\"Kitchen\"" in it })
    }

    @Test
    fun readOnlyUseNew_should_CarryTheAlreadyTrueSentence_NotTheOthers() {
        val text = EnableVariant.ReadOnlyUseNew.copy.fullText
        assertTrue("reopens this sketch" in text)
        assertTrue(SketchConsentCopy.WHOLE_PROJECT_ALREADY_TRUE in text)
        assertFalse(warning in text)
        assertFalse("projects stay classic" in text)
        assertFalse("can't open a project that contains" in text)
    }

    @Test
    fun newProjectsOnly_should_UseTheNewProjectForm() {
        val text = EnableVariant.NewProjectsOnly.copy.fullText
        assertTrue(SketchConsentCopy.WHOLE_PROJECT_NEW_PROJECT_FORM in text)
        assertEquals("Turn on", EnableVariant.NewProjectsOnly.copy.confirmLabel)
    }

    @Test
    fun firstWriteDialog_should_UseTheReferenceFormOnlyRightAfterAnEnableConfirmation() {
        val session = SessionConfirmation()
        val before = dialog(FirstWriteVariant.Convert, session.warningForm).blocks
        assertTrue(warning in before)
        session.confirmedHere()
        val after = dialog(FirstWriteVariant.Convert, session.warningForm).blocks
        assertFalse(warning in after.joinToString(" "))
        assertTrue(SketchConsentCopy.projectReference("Kitchen") in after)
        assertTrue(SketchConsentCopy.backupSentence("kitchen/backup/") in after, "the backup sentence stays in the convert variant")
        assertEquals(3, before.size)
        assertEquals(2, after.size, "the two warning paragraphs become one reference sentence")
    }

    @Test
    fun theWebLine_should_HaveOneWinner_ATemporaryGraphBeatsAPrivateWindow() {
        val web = SketchConsentCopy.WebStorage
        assertEquals(web.SESSION_ONLY, web.line(sessionOnly = true, privateSuspected = true))
        assertEquals(web.PRIVATE_WINDOW, web.line(sessionOnly = false, privateSuspected = true))
        assertEquals(web.PERSISTENT, web.line(sessionOnly = false, privateSuspected = false))
    }

    @Test
    fun noInternalNameV2_should_AppearInAnyCopy() {
        val all = SketchConsentCopy.EnableOn.fullText + SketchConsentCopy.EnableNewProjectsOnly.fullText +
            SketchConsentCopy.ReadOnlyUseNew.fullText + SketchConsentCopy.CHIP_DETAILS + SketchConsentCopy.LearnWhatChanges.joinToString(" ")
        assertFalse(Regex("""\bv2\b|sketch\.engine""", RegexOption.IGNORE_CASE).containsMatchIn(all))
    }

    @Test
    fun noConsentLiteral_should_ExistOutsideTheCopyObject() {
        val sources = WiringInputs.productionSources
        assertTrue(sources.isNotEmpty())
        val home = "ui/sketch/v2/strings/SketchConsentCopy.kt"
        val fragments = listOf("cannot open ANY sketch in that project", "already can't open this project", "won't open on those versions")
        for ((key, text) in sources) {
            if (key.endsWith(home)) continue
            for (fragment in fragments) assertFalse(fragment in text, "$fragment is duplicated in $key")
        }
    }
}
