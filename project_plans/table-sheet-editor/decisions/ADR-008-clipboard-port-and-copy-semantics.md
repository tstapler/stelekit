# ADR-008: Table clipboard port, TSV/CSV rules, copy semantics, Wasm paste event

**Status**: Proposed (Wasm route confirmed or replaced by Spike 0.3a)
**Date**: 2026-11-24

## Context
The repo uses the deprecated-but-working `ClipboardManager` (`App.kt:468`, `BlockList.kt:304`) plus `export/ClipboardProvider` (`writeText`, `writeHtml(html, plainFallback)`; android and wasm fall back to plain). Compose 1.10 has `LocalClipboard` (suspend). Wasm `navigator.clipboard.readText` needs a secure context, a user gesture and often a permission prompt; Firefox blocks it outside a paste gesture (pitfalls section 3).

## Decision
- `TableClipboard` port in the `table` package: `suspend fun copy(tsv: String, html: String?)`, `suspend fun paste(): String?`. Pure `TabularText` (RFC-4180-style state machine with tab or comma delimiter, `"` quoting, `""` escapes, CRLF, Excel's trailing newline) lives beside it with property tests. Implementations: `ComposeTableClipboard(ClipboardManager)` in `ui_main` (HTML via `ClipboardProvider.writeHtml` where the platform supports it), and on Wasm a `WasmPasteEventBridge` collaborator (wasmJsMain, a dedicated class, not a field on `PlatformFileSystem`) that listens to the DOM `paste` event and hands `clipboardData` text to the sheet; the "Paste here" fallback text area shows if no paste event is available. Copy reuses the existing `export/ClipboardProvider` (`writeText`/`writeHtml`); only paste and the Wasm DOM route are new, which is why the port is thin. The common sheet host reaches the Wasm bridge through a `LocalPasteEventSource: PasteEventSource?` composition local (commonMain interface, default null, provided by the wasm entry point); null selects the "Paste here" fallback field. Migrating to `LocalClipboard` later is one file.
- **Copy semantics**: the system clipboard receives TSV of displayed values (a formula cell copies its value, as Sheets does); a single non-formula cell copies raw text. An in-app `TableClipboardSlot` additionally keeps the raw cells (formula text included); when pasted text equals the slot's TSV, paste restores the raw cells, so in-app copy/paste preserves formulas. Menu item "Copy as formulas" copies raw text. Pasted formula references are not rewritten (ADR-003).
- **Paste rules**: anchored at the active cell. A single copied cell into a multi-cell selection fills the selection. Otherwise it pastes whole from the selection's top-left and grows the table (rows and columns) to fit. Reject with a message and no change above 10 000 pasted cells or above the 20 000-cell table cap. `|` is escaped, newline becomes `<br>`, and the status line says when content was transformed (8 x 5 pasted at B3 into a 3 x 3 table: "Pasted 8 x 5, added 7 rows, 3 columns"; the added counts depend on the anchor, rows 3+8-1 = 10 and columns 2+5-1 = 6 against a 3 x 3 table). One paste is one `TableEdit`, one undo entry, one recompute. Parsing runs inside a `Throwable` guard and, above 2 000 cells, off the main dispatcher.
- Never call a clipboard API from a timer or from a coroutine that lost the user-gesture window.

## Consequences
In-app round trip keeps formulas; interop with Sheets and Excel works through plain TSV. The Wasm path is the least certain and is spiked first. Wasm sources are not scanned by detekt (memory: stelekit_detekt_source_set_scope), so Task 7.1b includes a manual review pass.

## Alternatives rejected
`navigator.clipboard.readText` as the primary route (permission and gesture failures); HTML-only clipboard (not needed for interop); copying formulas in the system clipboard (pastes as `=SUM(...)` text into other apps and breaks cells there).
