package dev.stapler.stelekit.ui.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers [activeDropHandler]/[listenerInstalled]/[ensureListenerInstalled] directly (bumped from
 * `private` to `internal` for this purpose — see PageDropTarget.kt), since neither has a
 * `compose.ui-test` dependency available on `wasmJsTest` to drive a real Compose composition.
 */
class PageDropTargetListenerLifecycleTest {

    // activeDropHandler is a module-level var shared across every test in this Karma page
    // context — reset it so one test's registered handler can't leak into the next.
    @AfterTest
    fun resetActiveDropHandler() {
        activeDropHandler = null
    }

    @Test
    fun activeDropHandler_swapsToNewestRegisteredHandler_onPageNavigation() {
        val pageAHandler: (List<Any>) -> Unit = { }
        val pageBHandler: (List<Any>) -> Unit = { }

        activeDropHandler = pageAHandler
        assertSame(pageAHandler, activeDropHandler)

        activeDropHandler = pageBHandler
        assertSame(pageBHandler, activeDropHandler)
    }

    @Test
    fun ensureListenerInstalled_isIdempotent_acrossRepeatedCalls() {
        ensureListenerInstalled()
        ensureListenerInstalled()

        assertTrue(listenerInstalled)
    }

    @Test
    fun syntheticDrop_deliversDroppedImageFiles_toTheCurrentHandler() = runTest {
        val delivered = mutableListOf<List<Any>>()
        activeDropHandler = { delivered.add(it) }
        ensureListenerInstalled()

        dispatchSyntheticDropWithImageFile("delivered.png")
        withContext(Dispatchers.Default) { delay(300) }

        assertTrue(delivered.isNotEmpty(), "an image file dropped while a handler is registered must reach it")
    }

    @Test
    fun shouldDeliverDrop_isFalse_whenActiveHandlerHasChangedSinceTheDropStarted() {
        val handlerAtDropTime: (List<Any>) -> Unit = { }
        val handlerAfterNavigation: (List<Any>) -> Unit = { }
        activeDropHandler = handlerAfterNavigation

        // This is the exact race PageDropTarget.kt's shouldDeliverDrop guard exists to catch:
        // the page navigated (swapping activeDropHandler) while a drop's async file reads for
        // the *previous* page were still in flight.
        assertFalse(shouldDeliverDrop(listOf("dropped.png"), handlerAtDropTime))
    }

    @Test
    fun shouldDeliverDrop_isTrue_whenActiveHandlerIsUnchangedAndFilesWereRead() {
        val handler: (List<Any>) -> Unit = { }
        activeDropHandler = handler

        assertTrue(shouldDeliverDrop(listOf("dropped.png"), handler))
    }

    @Test
    fun shouldDeliverDrop_isFalse_whenNoImageFilesSurvivedTheExtensionFilter() {
        val handler: (List<Any>) -> Unit = { }
        activeDropHandler = handler

        assertFalse(shouldDeliverDrop(emptyList(), handler))
    }
}

private fun dispatchSyntheticDropWithImageFile(name: String): Unit = js("""
    (function() {
        var bytes = new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10]);
        var file = new File([bytes], name, { type: 'image/png' });
        var dt = new DataTransfer();
        dt.items.add(file);
        var event = new DragEvent('drop', { bubbles: true, cancelable: true, dataTransfer: dt });
        document.body.dispatchEvent(event);
    })()
""")
