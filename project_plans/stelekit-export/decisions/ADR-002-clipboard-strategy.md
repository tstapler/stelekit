# ADR-002: Clipboard Strategy (Phase 1 — Desktop JVM)

**Status**: Accepted
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

---

## Context

The export feature writes serialized content to the system clipboard. Three clipboard mechanisms are available in the current codebase:

1. **`LocalClipboardManager`** (Compose `androidx.compose.ui.platform`) — cross-platform within Compose; only supports `AnnotatedString` (plain text); requires capture inside a `@Composable` context.
2. **AWT `java.awt.Toolkit.getDefaultToolkit().systemClipboard`** — JVM Desktop stdlib; supports multi-flavor `Transferable` (plain text + HTML simultaneously); no Compose dependency; synchronous.
3. **`expect`/`actual` `ClipboardProvider`** — platform-abstract interface defined in `commonMain`, with separate JVM/Android/iOS implementations.

The existing `OptimizedTextOperations.getClipboardText()` returns `"[pasted text]"` — clipboard is not yet implemented in the codebase.

---

## Decision

**Phase 1: Use a `ClipboardProvider` functional interface injected from the UI layer, backed by Compose `LocalClipboardManager` for plain text formats and AWT `Toolkit` for the HTML multi-flavor write.**

Concretely:

1. Define `ClipboardProvider` as a simple interface in `commonMain`:
   ```kotlin
   // kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt
   interface ClipboardProvider {
       fun writeText(text: String)
       fun writeHtml(html: String, plainFallback: String)
   }
   ```

2. For Phase 1 (desktop-only), `ExportService` receives the provider at construction. The composable at the call site creates the implementation:
   ```kotlin
   // In PageView.kt or the export action handler:
   val composeClipboard = LocalClipboardManager.current
   val clipboardProvider = remember {
       object : ClipboardProvider {
           override fun writeText(text: String) {
               composeClipboard.setText(AnnotatedString(text))
           }
           override fun writeHtml(html: String, plainFallback: String) {
               // AWT call is safe on main thread
               val selection = HtmlStringSelection(html, plainFallback)
               java.awt.Toolkit.getDefaultToolkit().systemClipboard
                   .setContents(selection, null)
           }
       }
   }
   ```

3. `HtmlStringSelection` is a JVM-only helper in `jvmMain` implementing `java.awt.datatransfer.Transferable` with `text/html` and `text/plain` flavors.

---

## Rationale

1. **No new dependencies**: `LocalClipboardManager` is already in every Compose composable. `java.awt.Toolkit` is JDK stdlib. The `HtmlStringSelection` class requires ~20 lines of JVM code.

2. **HTML multi-flavor is necessary for rich-text paste**: Google Docs, Apple Mail, and Confluence all inspect the `text/html` clipboard flavor before falling back to plain text. Using `LocalClipboardManager` alone means HTML export pastes as raw markup strings.

3. **Avoids `expect`/`actual` complexity for Phase 1**: The `expect`/`actual` pattern is the clean long-term solution for mobile, but it adds three stub files (`androidMain`, `iosMain`, `jsMain`) that would be dead code for this phase. Deferring it until the mobile follow-on is pragmatic.

4. **`ExportService` stays pure domain code**: By injecting `ClipboardProvider` as an interface, `ExportService` has zero Compose imports. This is testable with a fake `ClipboardProvider` that captures the written text.

5. **Thread safety**: AWT clipboard writes must happen on the Event Dispatch Thread (EDT). Compose Desktop already dispatches its coroutines on the main thread; the call from a `LaunchedEffect` or `Button.onClick` is on the correct thread.

---

## Consequences

- New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/export/ClipboardProvider.kt` (interface, ~10 lines)
- New file: `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/export/HtmlStringSelection.kt` (~25 lines)
- `ExportService` constructor takes `ClipboardProvider`
- `PageView.kt` creates a concrete `ClipboardProvider` using `LocalClipboardManager.current` + AWT and passes it to `StelekitViewModel.exportPage()`
- Phase 2 (mobile): replace with `expect`/`actual` `ClipboardProvider` using `android.content.ClipboardManager` and `UIPasteboard`
