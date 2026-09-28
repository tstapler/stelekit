package dev.stapler.stelekit.capture

import com.tulskiy.keymaster.common.HotKey
import com.tulskiy.keymaster.common.HotKeyListener
import com.tulskiy.keymaster.common.MediaKey
import com.tulskiy.keymaster.common.Provider
import javax.swing.KeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Provider` (com.tulskiy.keymaster.common.Provider) is abstract with a public constructor
 * over native-hook internals — safe to subclass directly as a fake, which avoids ever
 * touching the real per-platform native hook in this headless test JVM.
 */
private class FakeProvider : Provider() {
    var registeredListener: HotKeyListener? = null
    var registeredKeyStroke: KeyStroke? = null
    var resetCalled = false
    var stopCalled = false

    override fun init() = Unit

    override fun reset() {
        resetCalled = true
    }

    override fun register(keyStroke: KeyStroke, listener: HotKeyListener) {
        registeredKeyStroke = keyStroke
        registeredListener = listener
    }

    override fun register(mediaKey: MediaKey, listener: HotKeyListener) = Unit

    override fun unregister(keyStroke: KeyStroke) = Unit

    override fun unregister(mediaKey: MediaKey) = Unit

    override fun stop() {
        stopCalled = true
    }
}

private class ThrowingRegisterProvider : Provider() {
    override fun init() = Unit
    override fun reset() = Unit
    override fun register(keyStroke: KeyStroke, listener: HotKeyListener): Nothing =
        throw IllegalStateException("simulated Wayland registration failure")
    override fun register(mediaKey: MediaKey, listener: HotKeyListener) = Unit
    override fun unregister(keyStroke: KeyStroke) = Unit
    override fun unregister(mediaKey: MediaKey) = Unit
}

class JKeymasterHotkeyListenerTest {

    @Test
    fun register_should_InvokeOnTriggered_When_FakeProviderFiresHotkey() {
        val fakeProvider = FakeProvider()
        val listener = JKeymasterHotkeyListener(providerFactory = { fakeProvider })
        var triggered = false

        listener.register { triggered = true }

        val registered = fakeProvider.registeredListener
        assertTrue(registered != null, "register() should have registered a HotKeyListener with the provider")
        assertEquals(KeyStroke.getKeyStroke("control shift SPACE"), fakeProvider.registeredKeyStroke)

        // Simulate the hotkey firing, as JKeymaster's native callback thread would.
        registered.onHotKey(HotKey(fakeProvider.registeredKeyStroke, registered))

        assertTrue(triggered, "onTriggered should be invoked when the fake provider fires the hotkey")
    }

    @Test
    fun register_should_CatchAndLog_NotThrow_When_ProviderRegistrationThrows() {
        val listener = JKeymasterHotkeyListener(providerFactory = { ThrowingRegisterProvider() })

        // Must not throw — a hotkey registration failure (e.g. under Wayland) must degrade
        // gracefully instead of crashing the caller.
        listener.register { }
    }
}
