// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * UX-114: success is confirmed by a toast that names what happened, and only on success. This task covers the export toasts
 * (`ExportSaved`, `ExportSavedWeb`), checked against the parsed chrome table, and the `Restored` notice that already exists.
 * The library add and insert toasts belong to Story 3.5.6 (the library surface), which has not landed; its rows of this test
 * land with it. Robolectric. Gradle-only: composes @Composable lambdas (see BUILD.bazel).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1920dp-h1080dp-mdpi")
class SuccessFeedbackTest {
    @get:Rule
    val rule = createComposeRule()

    private val table = ChromeTableParser.parseFixture()
    private var now = 0L

    private fun row(id: String) = table.toasts.single { it.id == id }

    private fun show(notice: SketchNotice) {
        val queue = SketchNoticeQueue()
        queue.post(notice, now)
        val host = ToastLaneHost(queue, { now })
        rule.setContent { ToastLaneOverlay(host, 640f) }
        rule.waitForIdle()
    }

    @Test
    fun exportSaved_should_MatchItsTableRow_AndShowOpenAsItsPrimaryAction() {
        val notice = ImportExportNotices.exportSaved(SketchSaveOutcome.Saved("architecture.png", "Downloads", "/home/u/Downloads/architecture.png"))
        val spec = row("ExportSaved")
        assertEquals(spec.summary, notice.text)
        assertEquals(spec.shortTitle, notice.shortTitle)
        assertEquals(spec.primaryLabel, notice.action)
        assertEquals(spec.moreItems, ToastMenu.items(notice), "Copy path, Details, Dismiss")
        assertEquals("architecture.png in Downloads", notice.details)

        show(notice)
        rule.onNodeWithTag(ToastTags.row("ExportSaved")).assertIsDisplayed()
        rule.onNodeWithTag(ToastTags.primary("ExportSaved")).assertIsDisplayed()
    }

    @Test
    fun exportSavedWeb_should_MatchItsTableRow_WithNoOpen_AndNameTheDownload() {
        val notice = ImportExportNotices.exportDownloaded(SketchSaveOutcome.Saved("architecture.png", null, null))
        val spec = row("ExportSavedWeb")
        assertEquals(spec.summary, notice.text)
        assertEquals(spec.shortTitle, notice.shortTitle)
        assertNull(notice.action)
        assertEquals(spec.moreItems, ToastMenu.items(notice), "Details, Dismiss")
        assertEquals("architecture.png in Downloads. Check your downloads.", notice.details)

        show(notice)
        rule.onNodeWithTag(ToastTags.row("ExportSavedWeb")).assertIsDisplayed()
    }

    @Test
    fun exportToast_should_AnnounceItsSummaryPolitely_NotAssertively() {
        val queue = SketchNoticeQueue()
        queue.post(ImportExportNotices.exportSaved(SketchSaveOutcome.Saved("a.png", "Downloads", null)), now)

        val announcement = queue.nextAnnouncement(now)
        assertEquals("File exported.", announcement?.text)
        assertEquals(false, announcement?.assertive)
    }
}
