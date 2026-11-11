// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.projectbridge.AttachedSheetRow
import dev.stapler.stelekit.projectbridge.SheetKind
import dev.stapler.stelekit.projectbridge.SheetStatus
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.inMemorySketchRepositories
import dev.stapler.stelekit.sketchimport.ImportFlow
import dev.stapler.stelekit.ui.sketchimport.ImportPhase
import dev.stapler.stelekit.ui.sketchimport.SketchImportViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DrawingAttachUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val hash = "ab".repeat(32)

    @Test
    fun listShowsRowsAndRemoveReportsTheHash() {
        val removed = mutableListOf<String>()
        var added = 0
        rule.setContent {
            MaterialTheme {
                AttachSheetsPanel(listOf(AttachedSheetRow(SheetKind.SVG, hash, SheetStatus.READY)), { added++ }, { removed += it })
            }
        }
        rule.onNodeWithText("SVG abababab - ready").assertIsDisplayed()
        rule.onNodeWithText("Remove").performClick()
        rule.onNodeWithText("Add drawing").performClick()
        assertEquals(listOf(hash), removed)
        assertEquals(1, added)
    }

    @Test
    fun emptyListSaysSo() {
        rule.setContent { MaterialTheme { AttachSheetsPanel(emptyList(), {}, {}) } }
        rule.onNodeWithText("No drawings attached").assertIsDisplayed()
    }

    @Test
    fun sessionShowsTaggingAndReportsCancel() {
        val assets = AssetIndexService(InMemoryAssetRepository())
        val flow = ImportFlow(assets, inMemorySketchRepositories(), FakeFileSystem(), EffectiveNotesPath("/graph"), null)
        val vm = SketchImportViewModel(flow, ProjectUuid("p1"), DimensionRegistry(dimensions = emptyList()))
        vm.open("a.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><text x=\"1\" y=\"1\">50\"</text></svg>".encodeToByteArray())
        val finished = mutableListOf<ImportPhase>()
        val session = DrawingSession("p1", vm, null, 0)
        rule.setContent { MaterialTheme { DrawingImportSession(session, { phase, _ -> finished += phase }) } }
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(listOf(ImportPhase.Cancelled), finished)
        assets.close()
    }
}
