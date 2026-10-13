# Requirements: fix-github-device-auth-browser-button

## Problem Statement

During the GitHub device flow OAuth dialog (`GitHubOAuthDialog`), the "Open GitHub"
button (visible in both `ShowCode` and `Polling` states) calls `onOpenBrowser(verificationUri)`.
On Desktop (JVM/Linux), this invokes `openInBrowser(url)` which does nothing because
`java.awt.Desktop.isDesktopSupported()` returns `false` on Linux — particularly under
Wayland, headless AWT, or environments where no AWT toolkit loads. The exception is also
silently swallowed even if `Desktop` is "supported" but `BROWSE` is unimplemented.
Users are forced to manually copy and open the URL.

## Scope

Platforms where the bug is confirmed or probable:
- **JVM/Desktop (Linux)** — primary target; Wayland / non-X11 sessions. `xdg-open` is the
  standard Linux mechanism for opening a URL in the default browser.
- **JVM/Desktop (macOS)** — `java.awt.Desktop.browse()` works correctly; no change needed.
- **JVM/Desktop (Windows)** — `java.awt.Desktop.browse()` works correctly; no change needed.

Platforms that already work:
- **Android** — `Intent.ACTION_VIEW` via `SteleKitContext.context`; working.
- **iOS** — `UIApplication.sharedApplication.openURL`; functional (deprecated API is a
  minor hygiene issue, not a blocker).
- **wasmJs** — `window.open(url, '_blank')`; implemented and working. The `expect` comment
  in `OpenInBrowser.kt` is stale ("no-op") and should be updated.

## Acceptance Criteria

1. Clicking "Open GitHub" in the device auth dialog on Linux opens the default browser
   to `https://github.com/login/device` (the `verificationUri` returned by GitHub's API).
2. The fix uses `xdg-open` as the primary mechanism on Linux, falling back to
   `Desktop.getDesktop().browse()` when `xdg-open` is unavailable.
3. macOS and Windows behaviour is unchanged (still use `Desktop.browse()`).
4. The `xdg-open` `Process` is spawned off the main/UI thread so it does not block Compose.
5. Failures in `xdg-open` (binary not found, non-zero exit) are silently ignored — the
   fallback is the "Copy code" button.
6. The stale `expect` comment in `OpenInBrowser.kt` (says wasmJs is a "no-op") is corrected
   to reflect the actual `window.open` implementation.
7. A `businessTest` or `jvmTest` unit test verifies the Linux branch calls the correct
   subprocess command given a controlled `os.name`.

## Out of Scope

- Updating the iOS implementation from the deprecated `openURL` to the modern completion-
  handler variant — that is a separate hygiene item.
- Any UI changes to the `GitHubOAuthDialog` composable — button wiring already works.
- Android or wasmJs implementation changes.
