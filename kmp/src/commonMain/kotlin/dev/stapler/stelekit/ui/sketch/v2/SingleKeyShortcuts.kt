package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.platform.Settings

enum class SingleKeyTarget { Desktop, Web, Android, Ios }

/**
 * The `SingleKeyShortcuts` setting (WCAG 2.1.4, UX-85, UX-108). Single-character tool shortcuts fire only
 * while the canvas has focus and this setting is on; modifier chords are unaffected.
 */
object SingleKeyShortcuts {
    const val SETTING_KEY = "sketch.single_key_shortcuts"

    /**
     * On for desktop; off for web (NVDA and JAWS reserve H, F, E, T, L and D in browse mode); on for Android
     * and iOS only with a hardware keyboard and no touch exploration (TalkBack, VoiceOver).
     */
    fun defaultFor(target: SingleKeyTarget, hardwareKeyboard: Boolean, touchExploration: Boolean): Boolean =
        when (target) {
            SingleKeyTarget.Desktop -> true
            SingleKeyTarget.Web -> false
            SingleKeyTarget.Android, SingleKeyTarget.Ios -> hardwareKeyboard && !touchExploration
        }

    /** The stored choice, or [default] when the user never set one. */
    fun read(settings: Settings, default: Boolean): Boolean =
        if (settings.containsKey(SETTING_KEY)) settings.getBoolean(SETTING_KEY, default) else default

    fun write(settings: Settings, enabled: Boolean) = settings.putBoolean(SETTING_KEY, enabled)

    /** Single letters never switch tools in a text field, the style panel or a dialog (UX-22). */
    fun isActive(enabled: Boolean, canvasFocused: Boolean, textInputOrDialogFocused: Boolean): Boolean =
        enabled && canvasFocused && !textInputOrDialogFocused
}
