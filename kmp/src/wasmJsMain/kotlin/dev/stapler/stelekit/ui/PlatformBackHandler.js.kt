package dev.stapler.stelekit.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.Promise

private fun jsPushHistoryGuard(): Unit = js("window.history.pushState({ stelekitBackGuard: true }, '')")

private fun jsPopStatePromise(): Promise<JsAny?> = js(
    """
    (function() {
        return new Promise(function(resolve) {
            function handler(event) {
                window.removeEventListener('popstate', handler);
                resolve(null);
            }
            window.addEventListener('popstate', handler);
        });
    })()
    """,
)

private class BackHandlerEntry(var enabled: Boolean, var onBack: () -> Unit)

/**
 * Traps the browser back button: keeps a synthetic `history` entry pushed at all times so a
 * `popstate` event always fires, then dispatches to the most-recently-registered *enabled*
 * [BackHandlerEntry] — mirroring [androidx.activity.compose.BackHandler]'s dispatcher priority
 * (used by the Android `actual`), where the innermost enabled callback wins over outer ones
 * (e.g. a dialog's dismiss beats the page-level "go back").
 */
private object WasmBackDispatcher {
    private val entries = mutableListOf<BackHandlerEntry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun register(enabled: Boolean, onBack: () -> Unit): BackHandlerEntry {
        val entry = BackHandlerEntry(enabled, onBack)
        entries.add(entry)
        start()
        return entry
    }

    fun unregister(entry: BackHandlerEntry) {
        entries.remove(entry)
    }

    private fun start() {
        if (started) return
        started = true
        jsPushHistoryGuard()
        scope.launch {
            while (true) {
                val ignored: JsAny? = jsPopStatePromise().await()
                entries.lastOrNull { it.enabled }?.onBack?.invoke()
                jsPushHistoryGuard()
            }
        }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    val entry = remember { WasmBackDispatcher.register(enabled) { currentOnBack() } }
    SideEffect { entry.enabled = enabled }
    DisposableEffect(entry) {
        onDispose { WasmBackDispatcher.unregister(entry) }
    }
}
