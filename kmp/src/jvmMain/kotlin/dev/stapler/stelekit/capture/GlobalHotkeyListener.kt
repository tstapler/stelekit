package dev.stapler.stelekit.capture

/**
 * OS-level global hotkey adapter. Kept separate from any specific library (see
 * [JKeymasterHotkeyListener]) because the underlying native-hook library is a maintenance
 * risk — swapping implementations should not require touching callers.
 */
interface GlobalHotkeyListener {
    /** Registers the hotkey and invokes [onTriggered] each time it fires. Never throws. */
    fun register(onTriggered: () -> Unit)

    /** Releases the hotkey. Safe to call even if [register] was never called or failed. */
    fun unregister()

    companion object {
        const val DEFAULT_COMBO_LABEL = "Ctrl+Shift+Space"
    }
}
