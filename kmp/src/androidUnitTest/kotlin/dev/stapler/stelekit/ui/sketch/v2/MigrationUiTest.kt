// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.ui.sketch.NoEditorReason
import dev.stapler.stelekit.ui.sketch.v2.migration.ConversionFailureDialog
import dev.stapler.stelekit.ui.sketch.v2.migration.EnableV2Confirmation
import dev.stapler.stelekit.ui.sketch.v2.migration.EnableVariant
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstV2WriteDialog
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteAction
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteDialogModel
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteVariant
import dev.stapler.stelekit.ui.sketch.v2.migration.MigrationTags
import dev.stapler.stelekit.ui.sketch.v2.migration.ModeControlModel
import dev.stapler.stelekit.ui.sketch.v2.migration.ModeSettingTags
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorAction
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorModel
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorScreen
import dev.stapler.stelekit.ui.sketch.v2.migration.NoEditorTags
import dev.stapler.stelekit.ui.sketch.v2.migration.PendingModel
import dev.stapler.stelekit.ui.sketch.v2.migration.PendingScreen
import dev.stapler.stelekit.ui.sketch.v2.migration.SketchModeSetting
import dev.stapler.stelekit.ui.sketch.v2.migration.UpgradeConsentSheet
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 1.4.6 composables over Robolectric (no display needed): the first-write dialog, the enable confirmation, the failure
 * dialog, the consent sheet, the mode radiogroup, and the no-editor and pending screens (UX-64, UX-76, UX-90, UX-134, UX-138).
 * Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class MigrationUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<String>()

    private fun hasHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun firstWriteDialog_should_FocusTheWarning_AndGateThePrimaryButtonOnTheOneCheckbox() {
        var model by mutableStateOf(FirstWriteDialogModel(FirstWriteVariant.Convert, "Kitchen", "kitchen/backup/"))
        rule.setContent {
            FirstV2WriteDialog(model, onCheckedChange = { model = model.withChecked(it) }, onAction = { actions += it.name })
        }
        rule.waitForIdle()

        rule.onNodeWithTag(MigrationTags.FIRST_WRITE_WARNING).assertIsFocused()
        rule.onNodeWithText(SketchConsentCopy.WHOLE_PROJECT_WARNING).assertIsDisplayed()
        rule.onNodeWithText("Check I understand to continue.").assertIsDisplayed()
        rule.onNodeWithTag(MigrationTags.FIRST_WRITE_PRIMARY).assertIsNotEnabled()
        rule.onNodeWithTag(MigrationTags.FIRST_WRITE_CLASSIC).assertIsEnabled()

        rule.onNodeWithTag(MigrationTags.FIRST_WRITE_CHECKBOX).performClick()
        rule.onNodeWithTag(MigrationTags.FIRST_WRITE_PRIMARY).assertIsEnabled()
        rule.onNodeWithTag(MigrationTags.FIRST_WRITE_PRIMARY).performClick()
        assertEquals(listOf(FirstWriteAction.Primary.name), actions)
    }

    @Test
    fun firstWriteDialog_should_CallClassicWithoutTheCheckbox() {
        rule.setContent {
            FirstV2WriteDialog(
                FirstWriteDialogModel(FirstWriteVariant.NewSheet, "Kitchen", "kitchen/backup/"),
                onCheckedChange = {},
                onAction = { actions += it.name },
            )
        }
        rule.onNodeWithText("Create a sketch with the new editor?").assertIsDisplayed()
        rule.onNodeWithTag(MigrationTags.FIRST_WRITE_CLASSIC).performClick()
        assertEquals(listOf(FirstWriteAction.Classic.name), actions)
    }

    @Test
    fun enableConfirmation_should_CallOnlyTheChosenCallback() {
        rule.setContent {
            EnableV2Confirmation(EnableVariant.On, onConfirm = { actions += "confirm" }, onCancel = { actions += "cancel" }, onLearn = { actions += "learn" })
        }
        rule.onNodeWithText(SketchConsentCopy.WHOLE_PROJECT_WARNING).assertIsDisplayed()
        rule.onNodeWithTag(MigrationTags.ENABLE_LEARN).performClick()
        rule.onNodeWithTag(MigrationTags.ENABLE_CANCEL).performClick()
        rule.onNodeWithTag(MigrationTags.ENABLE_CONFIRM).performClick()
        assertEquals(listOf("learn", "cancel", "confirm"), actions)
    }

    @Test
    fun failureDialog_should_SayTheOriginalWasNotChanged_AndOfferThreeWays() {
        rule.setContent {
            ConversionFailureDialog(
                ConversionFailure.Verify,
                onCopyDetails = { actions += "copy" },
                onOpenLegacy = { actions += "legacy" },
                onTryAgain = { actions += "retry" },
                onClose = { actions += "close" },
            )
        }
        rule.onNodeWithText("Your original sketch wasn't changed.").assertIsDisplayed()
        rule.onNodeWithText("Reason: the new copy did not read back correctly (verify)").assertIsDisplayed()
        listOf(MigrationTags.FAILURE_COPY, MigrationTags.FAILURE_OPEN_LEGACY, MigrationTags.FAILURE_TRY_AGAIN, MigrationTags.FAILURE_CLOSE)
            .forEach { rule.onNodeWithTag(it).performClick() }
        assertEquals(listOf("copy", "legacy", "retry", "close"), actions)
    }

    @Test
    fun consentSheet_should_ShowTheLineAndTheSentence_AndEveryDismissalMeansNotNow() {
        rule.setContent {
            UpgradeConsentSheet(onStart = { actions += "start" }, onNotNow = { actions += "notNow" }, onKeepClassic = { actions += "keep" })
        }
        rule.waitForIdle()
        rule.onNodeWithTag(MigrationTags.SHEET_TITLE).assertTextEquals("Upgrade this sketch?").assert(hasHeading())
        rule.onNodeWithTag(MigrationTags.SHEET_TITLE).assertContentDescriptionContains("dialog", substring = true)
        rule.onNodeWithTag(MigrationTags.SHEET_TITLE).assertIsFocused()
        rule.onNodeWithText("Upgrade blocks other devices.").assertIsDisplayed()
        rule.onNodeWithTag(MigrationTags.SHEET_BODY).assertIsDisplayed()
        rule.onNodeWithTag(MigrationTags.SHEET_NOT_NOW).performClick()
        rule.onNodeWithTag(MigrationTags.SHEET_KEEP_CLASSIC).performClick()
        rule.onNodeWithTag(MigrationTags.SHEET_START).performClick()
        assertEquals(listOf("notNow", "keep", "start"), actions)
    }

    @Test
    fun modeSetting_should_BeOneRadiogroupWithDisabledOptionsStillInTheTree() {
        val capped = V2Capabilities(conversionReady = true, conversionOffered = false, storage = V2Storage.Available)
        val model = ModeControlModel.build(V2Mode.Off, capped)
        rule.setContent { SketchModeSetting(model, onChoose = { actions += it.name }, onLearn = { actions += "learn" }) }

        rule.onNodeWithTag(ModeSettingTags.GROUP).assertContentDescriptionContains("New editor")
        rule.onNodeWithTag(ModeSettingTags.option(V2Mode.On)).assertIsNotEnabled().assertContentDescriptionContains("Not available in this build yet", substring = true)
        rule.onNodeWithTag(ModeSettingTags.option(V2Mode.On)).performClick()
        rule.onNodeWithTag(ModeSettingTags.option(V2Mode.NewProjectsOnly)).assertIsEnabled().performClick()
        rule.onNodeWithTag(ModeSettingTags.LEARN).performClick()
        assertEquals(listOf(V2Mode.NewProjectsOnly.name, "learn"), actions, "a disabled option stores nothing")
    }

    @Test
    fun noEditorScreen_should_FocusItsHeading_AndFollowTheTabOrderOfTheTable() {
        val model = NoEditorModel.of(NoEditorReason.StorageUnavailable(V2Storage.Browser(StorageReason.NoOpfs)), hasBackup = false)
        rule.setContent { NoEditorScreen(model, onAction = { actions += it.name }) }
        rule.waitForIdle()

        rule.onNodeWithTag(NoEditorTags.HEADING).assertIsFocused().assert(hasHeading()).assertTextEquals("This sketch can't be opened here")
        rule.onNodeWithTag(NoEditorTags.SCREEN).assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "This sketch can't be opened here"))
        rule.onNodeWithText("Nothing can be exported from here in this version.").assertIsDisplayed()
        model.actions.forEach { rule.onNodeWithTag(NoEditorTags.action(it)).performClick() }
        assertEquals(listOf(NoEditorAction.CopyDetails, NoEditorAction.HowToOpen, NoEditorAction.Close).map { it.name }, actions)
    }

    @Test
    fun newerSchemaScreen_should_HideExportWhenNoBackupExists() {
        val model = NoEditorModel.of(NoEditorReason.NewerSchema, hasBackup = false)
        rule.setContent { NoEditorScreen(model, onAction = { actions += it.name }) }
        rule.onNodeWithTag(NoEditorTags.action(NoEditorAction.CheckForUpdates)).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTagCount(NoEditorTags.action(NoEditorAction.ExportLastBackup)) == 0)
    }

    @Test
    fun pendingScreen_should_FocusItsHeading_AndShowTheLongerLineAfterFiveSeconds() {
        var seconds by mutableStateOf(0)
        rule.setContent { PendingScreen(PendingModel.at(seconds), onClose = { actions += "close" }) }
        rule.waitForIdle()
        rule.onNodeWithTag(NoEditorTags.PENDING_HEADING).assertIsFocused().assert(hasHeading())
        assertTrue(rule.onAllNodesWithTagCount(NoEditorTags.PENDING_LONGER) == 0)
        seconds = 5
        rule.waitForIdle()
        rule.onNodeWithTag(NoEditorTags.PENDING_LONGER).assertIsDisplayed()
        rule.onNodeWithTag(NoEditorTags.action(NoEditorAction.Close)).performClick()
        assertEquals(listOf("close"), actions)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
