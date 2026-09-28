package dev.stapler.stelekit.ui.components

import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers [activeDropHandler]/[listenerInstalled]/[ensureListenerInstalled] directly (bumped from
 * `private` to `internal` for this purpose — see PageDropTarget.kt), since neither has a
 * `compose.ui-test` dependency available on `wasmJsTest` to drive a real Compose composition.
 */
class PageDropTargetListenerLifecycleTest {

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
}
