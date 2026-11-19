// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.SketchCopyAction
import dev.stapler.stelekit.merge.SketchCopyDialogModel
import dev.stapler.stelekit.merge.SketchCopyError
import dev.stapler.stelekit.merge.SketchCopyResult
import dev.stapler.stelekit.merge.SketchOpenTarget
import dev.stapler.stelekit.model.GraphId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 6.1.2c (UX-63): the copy result dialog names the destination and offers Open; a name collision offers Rename and
 * copy and Cancel and commits nothing by itself; a failure offers Retry and Cancel. Robolectric, so no display is needed.
 * Gradle-only: composes @Composable lambdas, which the Bazel android target cannot compile (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h640dp-xhdpi")
class SketchCopyDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val actions = mutableListOf<SketchCopyAction>()
    private val renames = mutableListOf<String>()
    private val target = GraphId("graph-b")

    private fun show(model: SketchCopyDialogModel, slug: String = "kitchen") {
        rule.setContent { SketchCopyDialog(model, slug, { actions += it }, { renames += it }) }
        rule.waitForIdle()
    }

    private fun success(missing: List<String> = emptyList()) = SketchCopyDialogModel.of(
        "kitchen", "B", SketchCopyResult("kitchen", target, "B", SketchOpenTarget(target, "kitchen"), missing).right(),
    )

    private fun failure(error: SketchCopyError): SketchCopyDialogModel {
        val result: Either<SketchCopyError, SketchCopyResult> = error.left()
        return SketchCopyDialogModel.of("kitchen", "B", result)
    }

    @Test
    fun success_should_NameTheDestinationAndOfferOpen() {
        show(success())

        rule.onNodeWithText("Copied to B.").assertIsDisplayed()
        rule.onNodeWithTag(sketchCopyActionTag(SketchCopyAction.Done)).assertIsDisplayed()
        rule.onNodeWithTag(sketchCopyActionTag(SketchCopyAction.Open(SketchOpenTarget(target, "kitchen")))).performClick()

        assertEquals(listOf<SketchCopyAction>(SketchCopyAction.Open(SketchOpenTarget(target, "kitchen"))), actions)
    }

    @Test
    fun success_should_ReportMissingImages_NotHideThem() {
        show(success(missing = listOf("f2")))

        rule.onNodeWithText("1 image(s) were missing in the source and show as placeholders.").assertIsDisplayed()
    }

    @Test
    fun nameCollision_should_OfferRenameAndCancel_WithASuggestedName() {
        show(failure(SketchCopyError.SlugExists("kitchen")))

        rule.onNodeWithText("\"kitchen\" already exists in B.").assertIsDisplayed()
        rule.onNodeWithText("Nothing was changed.").assertIsDisplayed()
        rule.onNodeWithTag(sketchCopyActionTag(SketchCopyAction.Cancel)).assertIsDisplayed()
        rule.onNodeWithTag(sketchCopyActionTag(SketchCopyAction.RenameAndCopy)).assertIsEnabled()
        assertTrue(renames.isEmpty() && actions.isEmpty(), "showing the dialog commits nothing")
    }

    @Test
    fun rename_should_StayOff_ForTheSameNameOrAnUnsafeOne_AndReportATypedName() {
        show(failure(SketchCopyError.SlugExists("kitchen")))
        val field = rule.onNodeWithTag(SKETCH_COPY_NAME_FIELD_TAG)
        val rename = rule.onNodeWithTag(sketchCopyActionTag(SketchCopyAction.RenameAndCopy))

        field.performTextClearance()
        field.performTextInput("kitchen")
        rename.assertIsNotEnabled()
        field.performTextClearance()
        field.performTextInput("bad name!")
        rename.assertIsNotEnabled()
        assertTrue(renames.isEmpty() && actions.isEmpty(), "typing commits nothing")

        field.performTextClearance()
        field.performTextInput("kitchen-b")
        rename.performClick()
        assertEquals(listOf("kitchen-b"), renames)
    }

    @Test
    fun rename_should_ReportTheSuggestedName_NotAnAction() {
        show(failure(SketchCopyError.SlugExists("kitchen")))

        rule.onNodeWithTag(sketchCopyActionTag(SketchCopyAction.RenameAndCopy)).performClick()

        assertEquals(listOf("kitchen-2"), renames)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun failure_should_OfferRetryAndCancel() {
        show(failure(SketchCopyError.Failed(DomainError.FileSystemError.WriteFailed("/x", "disk full"))))

        rule.onNodeWithText("Couldn't copy").assertIsDisplayed()
        rule.onNodeWithTag(sketchCopyActionTag(SketchCopyAction.Retry)).performClick()
        rule.onNodeWithTag(sketchCopyActionTag(SketchCopyAction.Cancel)).performClick()

        assertEquals(listOf<SketchCopyAction>(SketchCopyAction.Retry, SketchCopyAction.Cancel), actions)
    }

    @Test
    fun assetConflict_should_OfferOnlyCancel() {
        show(failure(SketchCopyError.AssetConflict("a1.png")))

        rule.onNodeWithTag(sketchCopyActionTag(SketchCopyAction.Cancel)).performClick()
        assertEquals(listOf<SketchCopyAction>(SketchCopyAction.Cancel), actions)
    }

    @Test
    fun renameTarget_should_RejectTheSameNameAndUnsafeNames() {
        assertTrue(isRenameTarget("kitchen-2", "kitchen"))
        assertTrue(!isRenameTarget("kitchen", "kitchen") && !isRenameTarget("", "kitchen") && !isRenameTarget("a/b", "kitchen") && !isRenameTarget("-x", "kitchen"))
    }
}
