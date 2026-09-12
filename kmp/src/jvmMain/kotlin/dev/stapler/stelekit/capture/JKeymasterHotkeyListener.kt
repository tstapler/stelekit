package dev.stapler.stelekit.capture

import com.tulskiy.keymaster.common.HotKeyListener
import com.tulskiy.keymaster.common.Provider
import dev.stapler.stelekit.logging.Logger
import javax.swing.KeyStroke

/**
 * [GlobalHotkeyListener] backed by JKeymaster (com.github.tulskiy:jkeymaster), which wraps
 * per-platform native hooks (X11/Win32/Cocoa). Those hooks can throw or simply do nothing on
 * unsupported sessions (e.g. Wayland without an X11 compat layer) — [register] swallows any
 * failure so a bad hotkey environment never crashes the app.
 *
 * [providerFactory] defaults to JKeymaster's real static [Provider] singleton but is
 * injectable so tests can substitute a fake, since the real `Provider` wraps native hooks
 * that are impractical to exercise in a headless test JVM.
 */
class JKeymasterHotkeyListener(
    private val comboLabel: String = DEFAULT_COMBO,
    private val providerFactory: () -> Provider = { Provider.getCurrentProvider(false) },
) : GlobalHotkeyListener {

    private val logger = Logger("JKeymasterHotkeyListener")
    private var provider: Provider? = null

    override fun register(onTriggered: () -> Unit) {
        try {
            val provider = providerFactory()
            this.provider = provider
            val keyStroke = KeyStroke.getKeyStroke(comboLabel)
            provider.register(keyStroke, HotKeyListener { onTriggered() })
        } catch (e: Throwable) {
            // Throwable, not Exception: native hook loading can fail with UnsatisfiedLinkError
            // (an Error, not an Exception) — see SteleKitApplication.onCreate for the same rule.
            logger.warn("Hotkey registration failed", e)
        }
    }

    override fun unregister() {
        try {
            provider?.reset()
            provider?.stop()
        } catch (e: Throwable) {
            logger.warn("Hotkey unregistration failed", e)
        } finally {
            provider = null
        }
    }

    companion object {
        // javax.swing.KeyStroke combo string; JKeymaster registers this against the native hook.
        private const val DEFAULT_COMBO = "control shift SPACE"
    }
}
