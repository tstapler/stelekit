# Research: fix-github-device-auth-browser-button

## Current Implementation

### `OpenInBrowser.kt` (common — `expect` declaration)
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.kt`

```kotlin
expect fun openInBrowser(url: String)
```

The KDoc comment claims wasmJs is a "no-op". This is stale — see actual below.

### `OpenInBrowser.jvm.kt` (Desktop — **the broken one**)
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.jvm.kt`

```kotlin
actual fun openInBrowser(url: String) {
    if (Desktop.isDesktopSupported()) {
        try {
            Desktop.getDesktop().browse(URI(url))
        } catch (_: Exception) {
            // Silently ignore
        }
    }
}
```

**Root cause**: `Desktop.isDesktopSupported()` returns `false` on Linux under Wayland (no
X11 DISPLAY), headless AWT, and on many Compose Desktop launch configurations. Even when
it returns `true`, `Desktop.Action.BROWSE` is frequently unimplemented on Linux JVMs
(throws `UnsupportedOperationException`, silently caught). Both failure modes are silent.

### `OpenInBrowser.android.kt`
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.android.kt`

Uses `Intent.ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK` via `SteleKitContext.context.startActivity`.
Works correctly.

### `OpenInBrowser.ios.kt`
`kmp/src/iosMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.ios.kt`

Uses `UIApplication.sharedApplication.openURL(nsUrl)`. Works. The API is deprecated since
iOS 10 (use `openURL(_:options:completionHandler:)` for proper async handling) but not
broken.

### `OpenInBrowser.js.kt`
`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpenInBrowser.js.kt`

```kotlin
private fun jsOpenInBrowser(url: String): Unit = js("window.open(url, '_blank')")
actual fun openInBrowser(url: String) {
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
        jsOpenInBrowser(url)
    }
}
```

Works. The `expect` comment is wrong ("no-op"). The URL scheme guard is correct — device
flow `verificationUri` from GitHub is always `https://github.com/login/device`.

## Call Chain

```
GitHubOAuthDialog (Button: "Open GitHub")
  → onOpenBrowser(verificationUri)
    → GitSetupOAuthDialogHost (GitSetupScreenDialogs.kt:36)
      → openInBrowser(url)               // platform dispatch
        → OpenInBrowser.jvm.kt           // ← fails silently on Linux
```

The dialog state machine in `startOAuthFlow` (`GitSetupScreenSaveLogic.kt:345`) sets
`OAuthDialogState.ShowCode(userCode, verificationUri, expiresAt)` with the `verificationUri`
from `DeviceCodeResponse`. GitHub always returns `https://github.com/login/device` for
this field.

## Standard Linux URL-Opening Approach

`xdg-open` is the XDG specification's portable URL/file opener. It:
- Is present by default on all major Linux desktop environments (GNOME, KDE, XFCE, etc.).
- Works under Wayland and under X11.
- Dispatches to the user's configured default browser.
- Is available on the `PATH` on virtually all desktop Linux systems.

Standard invocation: `ProcessBuilder("xdg-open", url).start()`.

The process is fire-and-forget: we don't need to wait for it or inspect its exit code.
Fire off the child process on a background thread (IO dispatcher) to avoid blocking
Compose's main thread.

## Existing OS Detection Patterns in Codebase

Other JVM files in this codebase already detect Linux via:
```kotlin
val os = System.getProperty("os.name").lowercase()
// then: os.contains("linux"), os.contains("mac"), os.contains("windows")
```

Examples: `LibsqlNativeLoader.jvm.kt`, `DriverFactory.jvm.kt`, `PlatformFileSystem.kt`.

## Parallel: PlatformFileOpener.jvm.kt

`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/platform/PlatformFileOpener.jvm.kt` has
the same `Desktop.isDesktopSupported()` guard for opening files. That path also has no
Linux fallback but is less critical (no user-visible "does nothing" scenario for file
opens). No change there is required by this item.

## Test Infrastructure for JVM platform functions

JVM platform actuals can be unit-tested in `jvmTest`. The OS detection branch can be
tested by injecting a fake `os.name` via `System.setProperty` before/after, or by
extracting the OS-dispatch logic into a testable helper that accepts an `osName: String`
parameter. The `businessTest` source set doesn't include JVM-specific APIs so `jvmTest`
is the correct location.

## No UI or state-model changes required

The `GitHubOAuthDialog` composable, the `OAuthDialogState` sealed class, the
`GitSetupOAuthDialogHost` wiring, and the `startOAuthFlow` orchestration are all correct.
The sole change is the JVM platform actual and its `expect` comment.
