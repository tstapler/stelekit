// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AudiobookNoteScreenCarTest {

    private lateinit var testCarContext: TestCarContext
    private val writtenNotes = mutableListOf<AudiobookNote>()
    private var writeResult: Either<DomainError, Unit> = Unit.right()
    private lateinit var fakeWriter: SpyNoteWriter
    private lateinit var fakeObserver: ControllableObservedSession

    @Before
    fun setUp() {
        testCarContext = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        writtenNotes.clear()
        writeResult = Unit.right()
        fakeWriter = SpyNoteWriter { writeResult }
        fakeObserver = ControllableObservedSession()
    }

    private fun buildScreen(): AudiobookNoteScreen = AudiobookNoteScreen(
        carContext = testCarContext,
        observer = fakeObserver,
        noteWriter = fakeWriter,
        voiceViewModel = null,
    )

    private fun driveToResumed(screen: AudiobookNoteScreen): ScreenController {
        val controller = ScreenController(screen)
        controller.moveToState(Lifecycle.State.RESUMED)
        return controller
    }

    // ---- Template structure ----

    @Test
    fun `initial template is GridTemplate`() {
        val template = buildScreen().onGetTemplate()
        assertTrue("Expected GridTemplate, got ${template::class.simpleName}", template is GridTemplate)
    }

    @Test
    fun `GridTemplate has four items`() {
        val template = buildScreen().onGetTemplate() as GridTemplate
        assertEquals(4, template.singleList!!.items.size)
    }

    @Test
    fun `GridTemplate item labels are correct`() {
        val items = (buildScreen().onGetTemplate() as GridTemplate).singleList!!.items
        // Item 0 is "Voice note" or "Voice note (needs mic)" depending on mic permission
        assertTrue("Item 0 must start with 'Voice note'",
            (items[0] as GridItem).title.toString().startsWith("Voice note"))
        assertEquals("Bookmark",          (items[1] as GridItem).title.toString())
        assertEquals("Quick tag",         (items[2] as GridItem).title.toString())
        assertEquals("Bookmark position", (items[3] as GridItem).title.toString())
    }

    @Test
    fun `fourth button is not labelled audio snippet`() {
        val label = ((buildScreen().onGetTemplate() as GridTemplate).singleList!!.items[3] as GridItem).title.toString()
        assertTrue("Must not contain 'audio snippet'", !label.lowercase().contains("audio snippet"))
    }

    // ---- Header / subtitle ----

    @Test
    fun `header shows No book detected when bookInfo is inactive`() {
        fakeObserver.bookInfoState.value = BookInfo(isActive = false)
        val template = buildScreen().onGetTemplate() as GridTemplate
        assertEquals("No book detected", template.header!!.title.toString())
    }

    @Test
    fun `header shows book title when book is active`() {
        fakeObserver.bookInfoState.value = BookInfo(title = "Dune", isActive = true)
        val template = buildScreen().onGetTemplate() as GridTemplate
        assertEquals("Dune", template.header!!.title.toString())
    }

    @Test
    fun `header shows No book detected when active but title is null`() {
        fakeObserver.bookInfoState.value = BookInfo(title = null, isActive = true)
        val template = buildScreen().onGetTemplate() as GridTemplate
        assertEquals("No book detected", template.header!!.title.toString())
    }

    @Test
    fun `header shows Enable media access when notification listener not granted`() {
        fakeObserver.nlEnabled = false
        val template = buildScreen().onGetTemplate() as GridTemplate
        assertEquals("Enable media access in SteleKit settings", template.header!!.title.toString())
    }

    // ---- Error state ----

    @Test
    fun `pendingError set produces MessageTemplate`() {
        val screen = buildScreen()
        setPendingError(screen, "write failed")
        val t = screen.onGetTemplate()
        // Robolectric loads Car App SDK via a different classloader than the test JVM class,
        // so `is MessageTemplate` fails even when class names match. Compare by name instead.
        assertEquals("Expected MessageTemplate", "MessageTemplate", t.javaClass.simpleName)
    }

    @Test
    fun `error MessageTemplate title is Error`() {
        val screen = buildScreen()
        setPendingError(screen, "disk full")
        val t = screen.onGetTemplate()
        val title = t.javaClass.getMethod("getTitle").invoke(t)?.toString()
        assertEquals("Error", title)
    }

    @Test
    fun `error MessageTemplate has Retry and Dismiss actions`() {
        val screen = buildScreen()
        setPendingError(screen, "oops")
        val t = screen.onGetTemplate()
        @Suppress("UNCHECKED_CAST")
        val actions = t.javaClass.getMethod("getActions").invoke(t) as List<*>
        val titles = actions.mapNotNull { a -> a?.javaClass?.getMethod("getTitle")?.invoke(a)?.toString() }
        assertTrue("Missing Retry in $titles",   "Retry"   in titles)
        assertTrue("Missing Dismiss in $titles", "Dismiss" in titles)
    }

    @Test
    fun `pendingError consumed on first onGetTemplate call — subsequent returns GridTemplate`() {
        val screen = buildScreen()
        setPendingError(screen, "transient")
        screen.onGetTemplate()   // consumes the error (sets pendingError to null internally)
        assertEquals("GridTemplate", screen.onGetTemplate().javaClass.simpleName)
    }

    // ---- ScreenController lifecycle integration ----

    @Test
    fun `ScreenController lifecycle reaches RESUMED and screen returns GridTemplate`() {
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
        assertTrue("Screen must return GridTemplate when RESUMED", screen.onGetTemplate() is GridTemplate)
    }

    // ---- Interface contracts ----

    @Test
    fun `AudiobookNoteWriter implements NoteWriter`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("test_nw_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val w: NoteWriter = AudiobookNoteWriter(NoOpJournalService(), AudiobookAutoSettings(ctx))
        assertNotNull(w)
    }

    @Test
    fun `MediaSessionObserver implements ObservedSession`() {
        val obs: ObservedSession = MediaSessionObserver(testCarContext)
        assertNotNull(obs.bookInfo)
    }

    // ---- Helpers ----

    private fun setPendingError(screen: AudiobookNoteScreen, msg: String) {
        val f: Field = AudiobookNoteScreen::class.java.getDeclaredField("pendingError")
        f.isAccessible = true
        f.set(screen, msg)
    }
}

// ---- Shared test doubles (also used by QuickTagScreenCarTest) ----

internal class SpyNoteWriter(
    private val resultProvider: () -> Either<DomainError, Unit> = { Unit.right() },
) : NoteWriter {
    val written = mutableListOf<AudiobookNote>()
    override suspend fun writeNote(note: AudiobookNote): Either<DomainError, Unit> {
        written.add(note)
        return resultProvider()
    }
}

internal class ControllableObservedSession : ObservedSession {
    val bookInfoState = MutableStateFlow(BookInfo.Unknown)
    override val bookInfo: StateFlow<BookInfo> get() = bookInfoState
    var nlEnabled = true
    var startCalled = false
    var closeCalled = false

    override fun start() { startCalled = true }
    override fun close() { closeCalled = true }
    override fun refreshBookInfo() {}
    override fun isNotificationListenerEnabled() = nlEnabled
}
