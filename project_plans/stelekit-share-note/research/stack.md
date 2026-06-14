# Stack Research: KMP Share / Export

## Platform Share APIs

### Android — Intent.ACTION_SEND

Android share is entirely SDK-based (no library dependency needed):

```kotlin
// androidMain — ShareProvider implementation
val intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"           // or "text/html"
    putExtra(Intent.EXTRA_TEXT, plainText)
    putExtra(Intent.EXTRA_HTML_TEXT, htmlContent)   // API 16+; apps that understand HTML use this
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)          // required when context is Application context
}
context.startActivity(Intent.createChooser(intent, "Share via"))
```

Key points:
- Both `EXTRA_TEXT` (plain fallback) and `EXTRA_HTML_TEXT` should be set together for maximum app compatibility.
- `createChooser()` wraps the intent to show a titled bottom sheet on Android 12+; the OS provides the native UI.
- `SteleKitContext.context` is already used in `AndroidGoogleAuthManager` for launching browser intents — same pattern applies here.
- No `INTERNET` permission needed for share itself.

### iOS — UIActivityViewController

In KMP, iOS platform code must live in `iosMain` and call `UIKit`/`Foundation` via cinterop:

```kotlin
// iosMain — actual ShareProvider implementation
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.Foundation.NSString

actual fun shareText(text: String) {
    val activityVC = UIActivityViewController(
        activityItems = listOf(text as NSString),
        applicationActivities = null
    )
    val rootVC = UIApplication.sharedApplication.keyWindow?.rootViewController
    rootVC?.presentViewController(activityVC, animated = true, completion = null)
}
```

For HTML sharing, `NSAttributedString` with `.html` document type can be provided as an `NSItemProvider`. Compose Multiplatform on iOS uses UIKit under the hood, so `UIApplication.sharedApplication` is available.

### Desktop JVM — File Save Dialog

Compose Desktop provides `androidx.compose.ui.awt.ComposeWindow` access and the Swing `JFileChooser` / AWT `FileDialog` for save dialogs:

```kotlin
// jvmMain — ShareProvider implementation
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun saveToFile(content: String, suggestedName: String, extension: String) {
    val dialog = FileDialog(null as Frame?, "Save File", FileDialog.SAVE).apply {
        file = "$suggestedName.$extension"
        isVisible = true
    }
    val dir = dialog.directory ?: return
    val fileName = dialog.file ?: return
    File(dir, fileName).writeText(content)
}
```

`FileDialog` is AWT-native and renders a true OS file-save sheet on macOS/Linux/Windows. Must be called on the AWT Event Dispatch Thread — Compose Desktop's `Dispatchers.Main` satisfies this (it maps to the EDT). An alternative is `JFileChooser`, which is Swing-based and less native-looking on macOS.

The existing `HtmlStringSelection.kt` (jvmMain) shows the project already manipulates AWT `Transferable` objects — same threading model applies.

### OAuth Deep-Link Callback — Android

The Android manifest currently has **no `<intent-filter>` for the custom scheme** `com.stelekit.app:/oauth2redirect`. This must be added to the main `Activity` in the app module's manifest:

```xml
<activity android:name=".MainActivity">
    <intent-filter android:label="OAuth Callback">
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="com.stelekit.app" android:path="/oauth2redirect" />
    </intent-filter>
</activity>
```

The Activity then extracts the `code` parameter from `intent.data` and passes it to `AndroidGoogleAuthManager`. This requires a coroutine bridge (e.g., a `CompletableDeferred<String>` or `Channel`) that `authenticate()` suspends on while the browser session is in progress.

## Existing Platform Abstractions

The `PlatformClipboardProvider` expect/actual pattern (3 files) is the exact model to follow for `ShareProvider`:

| File | Role |
|---|---|
| `commonMain/.../PlatformClipboardProvider.kt` | `@Composable expect fun rememberClipboardProvider(...)` |
| `androidMain/.../PlatformClipboardProvider.android.kt` | `actual` using `ClipboardManager.setText` |
| `jvmMain/.../PlatformClipboardProvider.jvm.kt` | `actual` using AWT `HtmlStringSelection` |

`ShareProvider` will follow the same 3-file structure. The `commonMain` interface is a non-composable `interface` (share is a suspend action, not a composable); the `expect` declaration is for a factory function that creates the platform implementation.

## Dependency Impact

| Capability | New Dependency? |
|---|---|
| Android Intent.ACTION_SEND | No — SDK |
| iOS UIActivityViewController | No — cinterop with UIKit (already linked) |
| Desktop JVM FileDialog | No — AWT bundled with JVM |
| Google Docs upload | No — reuse existing `DriveApiClient.uploadFile()` |
| OAuth token exchange | No new library — complete the existing `JvmGoogleAuthManager.exchangeCodeForTokens()` stub |

## Summary

- **No new library dependencies** — all share mechanisms are OS/SDK APIs already available.
- Android uses `Intent.ACTION_SEND` via `SteleKitContext.context`; the manifest needs an `<intent-filter>` for the OAuth deep-link callback.
- Desktop JVM uses AWT `FileDialog` on the EDT (Compose Main dispatcher); `ShareProvider.jvm.kt` mirrors `PlatformClipboardProvider.jvm.kt`.
- The iOS implementation uses `UIActivityViewController` via cinterop; the `iosMain` actual is the only net-new cinterop call needed.
