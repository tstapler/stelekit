# Plan: fix-github-device-auth-browser-button

## Summary

A two-file change plus one test file. The primary fix is in
`OpenInBrowser.jvm.kt`: add a Linux-first branch that uses `xdg-open` via
`ProcessBuilder`, falling back to `Desktop.browse()` on macOS/Windows. The secondary
change is a comment correction in `OpenInBrowser.kt` (the `expect`). A `jvmTest`
verifies the Linux branch dispatches correctly.

## Affected Files

| File | Change |
|------|--------|
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.jvm.kt` | Replace silent-fail body with OS-branching logic |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.kt` | Fix stale wasmJs comment |
| `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/platform/OpenInBrowserJvmTest.kt` | New test for Linux/macOS branch selection |

No other files require changes.

## Task 1 — Fix `OpenInBrowser.jvm.kt`

Replace the current implementation:

```kotlin
// BEFORE
actual fun openInBrowser(url: String) {
    if (Desktop.isDesktopSupported()) {
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (_: Exception) { }
    }
}
```

With an OS-branching implementation:

```kotlin
// AFTER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

actual fun openInBrowser(url: String) {
    val os = System.getProperty("os.name", "").lowercase()
    when {
        os.contains("linux") -> {
            // xdg-open is the XDG-standard URL opener — works on Wayland, X11, and all
            // major Linux DEs. Desktop.browse() is unreliable on Linux (returns false for
            // isDesktopSupported() under Wayland/headless, and silently ignores BROWSE
            // actions even when it claims to be supported). Spawn off the UI thread.
            GlobalScope.launch(Dispatchers.IO) {
                runCatching { ProcessBuilder("xdg-open", url).start() }
            }
        }
        Desktop.isDesktopSupported() -> {
            runCatching { Desktop.getDesktop().browse(URI(url)) }
        }
    }
}
```

**Note on `GlobalScope.launch`**: The function is a top-level `actual fun` with no
injected scope. `GlobalScope` is acceptable here for the same reason the existing Android
implementation calls `startActivity` synchronously — this is a fire-and-forget side
effect with no result to observe. The coroutine outlives no state; it exits as soon as
`ProcessBuilder.start()` returns (which is nearly instant — it only forks the process).

Alternatively (simpler, avoids GlobalScope): run it on a daemon thread directly since
`ProcessBuilder.start()` is near-instant:
```kotlin
os.contains("linux") -> Thread {
    runCatching { ProcessBuilder("xdg-open", url).start() }
}.also { it.isDaemon = true }.start()
```
This avoids importing coroutines into a pure-platform file. Prefer this form.

## Task 2 — Fix stale comment in `OpenInBrowser.kt`

Update the KDoc comment for wasmJs from "no-op" to reflect the `window.open` actual:

```kotlin
/**
 * Opens the given URL in the platform's default browser.
 * Platform actuals:
 * - androidMain: Intent.ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK
 * - jvmMain: xdg-open on Linux; Desktop.getDesktop().browse(URI(url)) on macOS/Windows
 * - iosMain: UIApplication.sharedApplication.openURL
 * - wasmJsMain: window.open(url, '_blank') — blocked by pop-up blockers in some contexts
 */
expect fun openInBrowser(url: String)
```

## Task 3 — Add `jvmTest` unit test

Create `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/platform/OpenInBrowserJvmTest.kt`.

Strategy: extract OS dispatch to a testable helper that accepts `osName` and a
`processLauncher: (List<String>) -> Unit` parameter, or use a simpler approach of
verifying the behaviour through a `ProcessBuilder` spy. The cleanest approach for this
codebase (no mockk in jvmTest) is to make the xdg-open invocation go through an
injectable lambda:

```kotlin
// In OpenInBrowser.jvm.kt — add internal for test access:
internal fun openInBrowserWithOs(
    url: String,
    osName: String = System.getProperty("os.name", ""),
    processLauncher: (String) -> Unit = { cmd -> ProcessBuilder(cmd, url).start() },
) {
    val os = osName.lowercase()
    when {
        os.contains("linux") -> runCatching { processLauncher("xdg-open") }
        Desktop.isDesktopSupported() -> runCatching { Desktop.getDesktop().browse(URI(url)) }
    }
}

actual fun openInBrowser(url: String) = openInBrowserWithOs(url)
```

Test:
```kotlin
class OpenInBrowserJvmTest {
    @Test fun `linux branch invokes xdg-open`() {
        var capturedCmd: String? = null
        openInBrowserWithOs(
            url = "https://github.com/login/device",
            osName = "Linux",
            processLauncher = { capturedCmd = it },
        )
        assertEquals("xdg-open", capturedCmd)
    }

    @Test fun `non-linux branch does not invoke xdg-open`() {
        var capturedCmd: String? = null
        // macOS — Desktop may or may not be supported in test; just verify xdg-open not called
        openInBrowserWithOs(
            url = "https://github.com/login/device",
            osName = "Mac OS X",
            processLauncher = { capturedCmd = it },
        )
        assertNull(capturedCmd)
    }
}
```

## Adversarial Review

**Q: Does `GlobalScope.launch` (or a daemon Thread) cause a resource leak?**
A: No. `ProcessBuilder.start()` forks the child process — the JVM parent's thread
completes immediately. The daemon thread has no cleanup requirement.

**Q: What if `xdg-open` is not installed?**
A: `ProcessBuilder.start()` throws `IOException` ("No such file or directory") which
`runCatching` absorbs. The user's fallback is the "Copy code" button.

**Q: Should there be a macOS-specific `open` command path instead of Desktop?**
A: `java.awt.Desktop.browse()` works reliably on macOS (the JDK ships with a native
bridge). No change needed.

**Q: Is `xdg-open` safe for arbitrary URLs?**
A: The URL comes from `DeviceCodeResponse.verificationUri` returned by GitHub's OAuth
API, which always returns `https://github.com/login/device`. The URL is not
user-controlled at the call site. As a defence-in-depth measure, the wasmJs
implementation already guards `url.startsWith("https://")` — a matching guard could be
added to the JVM Linux branch, though it's low-priority since the value origin is a
trusted API response.

**Q: Does the iOS `openURL` deprecation block this item?**
A: No — it is functional on all current iOS versions. A follow-up hygiene item is appropriate.

**Q: Is the test sufficient?**
A: The test verifies the Linux dispatch path calls `xdg-open`. It does not spin up a real
browser — that's an acceptable tradeoff for a unit test of platform dispatch logic.
