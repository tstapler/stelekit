// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import android.content.Context
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class QuickTagScreenCarTest {

    private lateinit var testCarContext: TestCarContext
    private lateinit var context: Context
    private lateinit var fakeWriter: SpyNoteWriter
    private lateinit var fakeObserver: ControllableObservedSession

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testCarContext = TestCarContext.createCarContext(context)
        fakeWriter = SpyNoteWriter()
        fakeObserver = ControllableObservedSession()
        context.getSharedPreferences("audiobook_auto_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun buildScreen(tags: List<String>? = null): QuickTagScreen {
        val settings = AudiobookAutoSettings(context)
        if (tags != null) settings.setQuickTags(tags)
        return QuickTagScreen(testCarContext, fakeWriter, fakeObserver, settings)
    }

    private fun driveToResumed(screen: QuickTagScreen): ScreenController {
        val controller = ScreenController(screen)
        controller.moveToState(Lifecycle.State.RESUMED)
        return controller
    }

    // ---- Template structure ----

    @Test
    fun `template is ListTemplate`() {
        val template = buildScreen().onGetTemplate()
        assertTrue("Expected ListTemplate, got ${template::class.simpleName}", template is ListTemplate)
    }

    @Test
    fun `ListTemplate has 4 rows by default`() {
        val template = buildScreen().onGetTemplate() as ListTemplate
        assertEquals(4, template.singleList!!.items.size)
    }

    @Test
    fun `ListTemplate row titles match default quick tags`() {
        val template = buildScreen().onGetTemplate() as ListTemplate
        val titles = template.singleList!!.items.map { (it as Row).title.toString() }
        assertEquals(listOf("Key insight", "Follow up", "Quote", "Action item"), titles)
    }

    @Test
    fun `ListTemplate row titles reflect custom configured tags`() {
        val custom = listOf("Chapter quote", "Action item", "Insight", "Question")
        val template = buildScreen(tags = custom).onGetTemplate() as ListTemplate
        val titles = template.singleList!!.items.map { (it as Row).title.toString() }
        assertEquals(custom, titles)
    }

    @Test
    fun `ListTemplate header title is Quick Tag`() {
        val template = buildScreen().onGetTemplate() as ListTemplate
        assertNotNull(template.header)
        assertEquals("Quick Tag", template.header!!.title.toString())
    }

    @Test
    fun `ScreenController lifecycle reaches RESUMED and screen returns ListTemplate`() {
        // moveToState() drives the lifecycle without a real Car host;
        // getTemplatesReturned() requires a live AppManager call, so we verify
        // lifecycle state + direct template call instead.
        val screen = buildScreen()
        val controller = driveToResumed(screen)
        assertEquals(
            "Screen lifecycle must reach RESUMED",
            Lifecycle.State.RESUMED,
            screen.lifecycle.currentState,
        )
        assertTrue("Screen must return ListTemplate when RESUMED", screen.onGetTemplate() is ListTemplate)
    }

    // ---- Note type contracts ----

    @Test
    fun `QuickTagNote carries correct tag and bookInfo fields`() {
        val bookInfo = BookInfo(title = "Sapiens", isActive = true, positionMs = 120_000L)
        fakeObserver.bookInfoState.value = bookInfo
        val note = AudiobookNote.QuickTagNote(tag = "Follow up", bookInfo = bookInfo)
        assertEquals("Follow up", note.tag)
        assertEquals("Sapiens", note.bookInfo.title)
        assertEquals(120_000L, note.bookInfo.positionMs)
    }

    @Test
    fun `formatQuickTag includes hash-tag book link and position`() {
        val result = AudiobookNoteFormatter.formatQuickTag(
            tag = "Key insight",
            bookTitle = "Dune",
            positionMs = 3661_000L,
        )
        assertTrue("Should contain #Key insight", result.contains("#Key insight"))
        assertTrue("Should contain [[Dune]]", result.contains("[[Dune]]"))
        assertTrue("Should contain 01:01:01", result.contains("01:01:01"))
        assertTrue("Should contain #audiobook-note", result.contains("#audiobook-note"))
    }

    @Test
    fun `formatQuickTag without book title omits book link`() {
        val result = AudiobookNoteFormatter.formatQuickTag(
            tag = "Quote",
            bookTitle = null,
            positionMs = null,
        )
        assertTrue("Should contain #Quote", result.contains("#Quote"))
        assertTrue("Should not contain [[", !result.contains("[["))
    }
}
