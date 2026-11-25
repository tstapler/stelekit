# Pitfalls and risks: table-sheet-editor

Confidence labels: VERIFIED = source opened or command run this session; INFERRED = reasoning from code or general platform knowledge, not checked against a running build; UNVERIFIED = needs a spike.

## 0. Baseline facts that drive the risks (VERIFIED)

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TableModel.kt` is **parse-only and lossy**. There is no serializer.
  - `parseTableContent` filters blank lines, trims every cell, drops body cells beyond the header count, pads short rows, and keeps only the parsed alignment (`:---`, `---:` or none), not the raw delimiter row.
  - `splitTableRow` turns `\|` into `|` on read. A serializer that does not re-escape corrupts the table.
  - Consequence: "untouched table is byte-identical" cannot be met by `serialize(parse(raw))`. It needs the original raw string, or per-cell and per-row raw spans kept in the model, and a dirty check before re-serializing.
- `TableBlock.kt:~100` puts a single `combinedClickable(onClick = start edit, onLongClick = select)` on the whole table box, with an inner `horizontalScroll`. `TableCell` renders `WikiLinkText`, which carries its own tap recognizer for links.
- #721 and #724: link and URL taps in cells were added via `LocalUriHandler`. #724 removed archive right-click on tables because it appended `[archived](...)` after the closing pipe, which `parseTableContent` silently drops. This is a precedent for "write path that the parser can't see".
- #240 (`dc94048f63`): two independent `detectTapGestures` recognizers on the same pointer stream raced (each ran its own long-press timer). The fix was one recognizer per block row, with selection state threaded down into the block composable. Cell-level gestures must fit inside that single-recognizer design.
- Write path: `BlockStateManager` debounces disk writes (500 ms per CLAUDE.md; `queueDiskSave` around `BlockStateManager.kt:1145`), and the file watcher has self-write suppression.

## 1. Gestures: tap vs selection vs link (highest UX risk)

| Pitfall | Why | Mitigation |
|---|---|---|
| Re-introducing the #240 race: per-cell `detectTapGestures` plus the table-level `combinedClickable` plus `WikiLinkText`'s own recognizer, all on one pointer stream | Each has an independent long-press timer; under jank the wrong one wins (tap lands in block-select mode instead of cell edit) | Exactly one gesture owner for the table. Do hit-testing once (cell index from pointer position and column/row layout, then link hit-test inside that cell), not nested clickables. Add a Robolectric test that taps with a delayed frame clock. |
| Platform text selection (`SelectionContainer`) across many `WikiLinkText` cells | `SelectionContainer` installs its own long-press/drag recognizer that competes with block-level long-press-to-select-block. Selection also cannot span a `horizontalScroll` + custom `Layout` reliably, and `WikiLinkText` is likely a custom-drawn or annotated text, not `BasicText` selectable. INFERRED (not opened this session). | Recommend a custom range-selection model (cell rectangle) with its own copy path, not platform text selection. Platform selection only inside the single editing cell's `BasicTextField`. |
| Long-press means three things: block select (existing), cell text selection, range-select start | Same gesture, 3 meanings | Define precedence in one place. Suggested: long-press on a non-editing cell = existing block select (unchanged); range select is shift+click / drag after the sheet is maximized, or a handle drag on touch. Never overload without a design decision. |
| `horizontalScroll` inside LazyColumn: existing `TODO(CMP-4279)` notes iOS conflict | Nested scroll plus cell drag-select competes for horizontal drags | Range-drag only starts from a selected cell's handle or after a slop and long-press. Keep scroll the default for touch. |
| Touch targets in raw px | Past release regression (sketch tool) | Use dp and `pxPerDp`, and `hitTolerancePx`-style helpers. |
| Link tap vs enter-edit | #721: a link tap must open the URL and not edit | Preserve: in a non-editing cell the link hit-test wins; a tap on non-link text edits. Keep `TableBlockLayoutTest` cases (10 tests) green as the contract. |

## 2. IME, composition and focus when a lazy list recycles an editing cell

- INFERRED: a `LazyColumn` item is disposed when it scrolls out of the viewport. If the editing cell's `BasicTextField` is disposed, the `TextFieldValue` composition range, the IME connection and focus are lost. On Android the soft keyboard may close or restart input and the composing text may be committed or dropped; on Compose Desktop and Wasm the input method state is also reset.
- Mitigations:
  - Hold the editing cell state (`TextFieldValue` including `composition`, selection) in a state holder **outside** the lazy item, keyed by (row, col), not inside `LazyColumn` item `remember`.
  - Use `rememberSaveable` or a hoisted state for the draft, and **commit on dispose** (don't lose the draft when the item leaves the viewport). *(Superseded by ADR-004: there is no commit on dispose; the draft is hoisted and commits only on explicit triggers.)*
  - Pin the editing row: `LazyListState` keeps visible items, so on scroll away either keep the row composed (custom `Layout` or lazy-list `key` stability only helps for reorders, not for disposal), or commit and exit edit mode and say so in the UI.
  - Always use stable `key = rowId`, not index, so row insert/delete/sort doesn't hand one cell's `TextFieldValue` to another row.
  - Request focus in a `LaunchedEffect` after the cell composes, and guard against the keyboard flashing on every edit-cell change.
  - Tab/Enter/arrow key handling must use `onPreviewKeyEvent` and must **not** consume events while `composition != null` (IME composing). Otherwise Japanese/Chinese users cannot confirm a composition with Enter.
- Wasm/JS: Compose for Web renders to canvas, so IME goes through a hidden DOM input element. UNVERIFIED which Compose Multiplatform version and which gaps exist (composition underline, candidate window placement). Spike on the wasm build with a real IME before committing to inline edit on web. The `wasmJsBrowserTest` runs real Chrome but won't exercise an IME.
- Android: `adjustResize` and the soft keyboard cover the bottom rows of a maximized sheet; ensure `imePadding` and `bringIntoViewRequester` for the editing cell. Must be checked on `Pixel_Fold` (the CLAUDE.md-specified AVD). Headless Robolectric does not show IME behaviour.

## 3. Clipboard and Wasm/JS

- INFERRED: the async Clipboard API (`navigator.clipboard.readText`) requires a secure context (HTTPS or localhost), a user gesture and, in Chromium, a permission prompt; Firefox does not allow `readText` from a web page without the paste gesture (it only allows reading via a user-initiated paste event, with a context-menu "Paste" button). Safari requires a user gesture and shows a paste bubble.
- Mitigations: handle paste through the `paste` DOM event (a user-gesture-driven path that exposes `clipboardData` with no permission prompt), not `readText`. Provide a fallback "Paste" dialog with a text area when the permission is denied. Never call clipboard APIs on a timer or from a coroutine that has lost the user-gesture window.
- Copy: write both `text/plain` (TSV) and, ideally, `text/html` (a `<table>`), because Google Sheets and Excel prefer HTML when pasting into each other. Excel TSV: cells containing tabs, newlines or quotes are wrapped in `"` with `""` escapes. A naive `split('\t')` breaks on that. Parse with a real RFC-4180-style state machine with tab as the delimiter.
- Existing `ClipboardManager` abstraction on commonMain: reading is `suspend` or `AnnotatedString`-only in many CMP versions; confirm what the project's platform layer exposes (not checked this session). Per the project's wasm pattern (`stelekit_wasmjs_platform_layer_pattern` memory), add a dedicated collaborator class, not a new field on `PlatformFileSystem`.
- Large paste: a 5000-cell paste must be a single transaction to the view model (one undo step, one save), not 5000 cell edits (5000 invalidations and 5000 debounce resets).
- Newlines in pasted cells: a pipe table cell cannot contain a raw newline. Convert to `<br>` (the Rabbit Holes list notes this) and on read treat `<br>` as display-only, keep the literal text. Also escape `|` as `\|`.

## 4. Android process death, OOM and scope rules (CLAUDE.md)

- **CoroutineExceptionHandler**: any new long-lived scope (a sheet controller, formula engine, a clipboard-paste worker) must attach a handler. An uncaught `OutOfMemoryError` in a standing collector kills the process on Android only, so desktop tests never show it. A large paste or a formula graph build is exactly where OOM happens. Catch `Throwable` (not `Exception`) at the paste/parse boundary and surface a cell or sheet error.
- **rememberCoroutineScope escape**: a `TableSheetState`/controller held via `remember {}` must own its scope (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`), not take `rememberCoroutineScope()`. Cancel it in `DisposableEffect.onDispose` (the CLAUDE.md pattern only says it must be internal, so a leak check is on the implementer).
- **Graph-scale reads**: a table lives inside one block's content, so there is no graph-scale read; the risk is a table-scoped standing observer (e.g. collecting `getBlocks(page)` per cell). Observe the one block only (`getBlockByUuid`) and never add an unbounded repository query for the sheet.
- **Process death while the maximized sheet is open**: a maximized sheet is a transient UI state, but unsaved draft cell text would be lost. Policy: commit each cell edit into the block state on cell exit (not on sheet close) so the existing debounce and crash recorder are the only durability layer. Don't add a second persistence mechanism. Restoring "sheet was open" on recreate is optional; if done, use `rememberSaveable` of (blockUuid, selection) only, never table content.
- Android `Application.onCreate` rule is not relevant unless a new startup hook is added. Keep any feature flag read lazy.
- Logging: use `Logger`, not `android.util.Log`, so formula/parse errors reach the persisted sink (the owner cannot use logcat).

## 5. Write path: debounce, DiskConflict, self-write suppression

- Per-keystroke edits in a cell should update the **local block state** immediately and rely on the existing 500 ms debounce. Do not write per keystroke and do not build a second debounce.
- Interaction hazards:
  - **Sort / paste / row delete** are bulk changes. They must go through one `BlockStateManager` update, so the debounce sees one change and undo gets one entry.
  - **External change while editing a cell** (`GraphLoader.externalFileChanges`, then `DiskConflict`): if the block content changes under an open sheet, the sheet's in-memory `TableData` is stale. Rebuild from block content on external change and decide policy for an open edit (keep draft and offer the existing conflict dialog, rather than silently replacing). Never keep a second source of truth for the table beyond the block content plus the transient draft.
  - **Self-write suppression**: since the sheet writes only through `BlockStateManager` → `GraphWriter`, suppression works. Writing markdown any other way (e.g. a "save table" helper hitting the filesystem) would trigger a bogus DiskConflict. Same lesson as #724 above.
  - **Echo loops**: block content changes → `remember(content) { parse }` rebuilds `TableData` → composition resets cell state → cursor jumps while typing. Do not re-parse the table from content on every keystroke while a cell is being edited. Gate re-parse on "content changed by someone other than me" (compare against the last serialized string).
  - The debounce means a quick maximize/close, app background or process death within 500 ms can lose the last edit. Flush the pending save on sheet close and on `onStop`, using whatever flush API `BlockStateManager` exposes (confirm name before planning).
- Undo/redo: the existing stack is block-level. A cell edit pushed per keystroke floods it. Coalesce: one undo entry per cell edit session (focus in to focus out), and one per structural op. Open Question in requirements; the pitfall is mixing granularities so that undo after a sort restores row order but not the in-progress cell text.

## 6. Formula evaluator: DoS and correctness

All INFERRED design risks; require a limits spec and tests.

- **Huge ranges**: `=SUM(A1:ZZZ999999)` allocates or iterates millions of cells. Clamp every range to the table's actual bounds before iterating (cells that don't exist are empty), so cost is bounded by table size, not by the text. Reject references outside a hard limit (e.g. > 10 000 cells considered).
- **Deep recursion**: `=((((...))))` nesting, or a chain `A1=A2+1, A2=A3+1 ...` thousands deep, blows the JVM stack (a `StackOverflowError` is an `Error`, so `catch (Exception)` misses it and on Android it kills the process, per CLAUDE.md). Mitigations: a parser depth limit (e.g. 64), an explicit-stack or iterative topological evaluation instead of recursive cell-to-cell evaluation, and `catch (Throwable)` at the evaluation boundary.
- **Cycles**: detect with DFS color marking or Kahn's algorithm over the dependency graph and produce a per-cell `#CYCLE!` error. Self-reference (`A1 = A1+1`) and range-includes-self (`B5 = SUM(B1:B5)`) are the common cases; the latter is easy to miss if range expansion happens after the cycle check.
- **Recompute cost**: requirement says no O(cells²) per keystroke. Build the dependency graph once per structural change, and per edit recompute only the transitive dependents of the edited cell (dirty propagation, topological order). Memoize per-evaluation pass. Range-heavy formulas (`SUM` over a column in every row of 200 rows) are O(rows × rows) cells read unless prefix sums are used; fine at a few thousand cells, but state the bound.
- **Number parsing and locale**: do not use `String.toDouble()` on user text blindly (accepts `NaN`, `Infinity`, hex-float forms like `0x1p3` on JVM, `1d`, `1f` suffixes). Use an explicit grammar. Decide locale: use `.` decimal and `,` thousands, as `isNumericCell` already assumes (`1,234.5`), and do not follow device locale (a file would compute differently on different machines).
- **Floating point**: show `0.1+0.2` as `0.3` (display rounding, e.g. 12 significant digits) while not mutating the source. Division by zero gives `#DIV/0!`, not an exception or Infinity.
- **Function allowlist**: closed set only (`SUM, AVERAGE, MIN, MAX, COUNT, IF`). Unknown function gives `#NAME?`. No reflection, no string-to-code. No `eval`-like Wasm/JS call. This matches the "no network, no file access" classification. *(Superseded by ADR-002: the set is `SUM AVERAGE MIN MAX COUNT COUNTA IF ROUND ABS`.)*
- **Display vs data**: a cell whose text starts with `=` but is not meant to be a formula (e.g. `=== heading ===`, or text pasted from elsewhere) will now display as `#ERROR`. Decide: only treat as formula if it parses as one, else show the literal text (never error on prose). Logseq will show the literal `=SUM(...)` text; that is accepted by owner.
- **Escaped pipe inside formulas**: `=IF(A1>0, "a|b", "c")` contains a pipe which on disk must be `\|`. The parse layer unescapes before the evaluator sees it; the serializer re-escapes. Test it.
- **Formula text in sort / search / copy**: copying a range should copy the displayed value or the formula? Decide (Sheets copies value in TSV, formula in internal clipboard). Define it explicitly.

## 7. Reference rewriting under structural edits

- Insert/delete/move/sort row or column must rewrite A1-style references (if the owner chooses Sheets-style auto-shift) or leave them literal (simplest, but silently wrong). Open Question #3.
- Classic bugs:
  - Off-by-one at the boundary (insert **at** a referenced row vs after it).
  - Range endpoints: deleting a row inside `B2:B5` shrinks the range; deleting an endpoint row shifts it; deleting the whole range gives `#REF!`. A naive per-endpoint rewrite gives a reversed or negative range.
  - Rewriting inside string literals or function names (`"A1"` text, or a column letter inside `LOG10`, or the function `ATAN2`). Rewrite via the parsed AST, never regex on the string.
  - Column letters beyond Z (`AA`, `AB`), and base-26 conversion off-by-one (`Z` to `AA`; there is no zero digit).
  - **Sort** changes row order but conceptually references follow data in Sheets (formulas in sorted rows keep **relative** references, which commonly breaks them). Choose an explicit semantics; the safest is to sort only value rows and refuse (or warn) when any formula refers to a row being moved.
  - **Header row**: A1-style numbering must say whether header is row 1 (`A1` is the header cell) or row 0. Requirement example `=SUM(B2:B5)` implies the header is row 1 so data starts at row 2. Fix and test this.
  - Absolute markers (`$A$1`) are in or out; if out, `$` must give an error and not misparse.
  - Rewriting must be one atomic transform together with the structural edit (one undo step), otherwise undo restores cells but not references.
- Test with property tests: for random tables and random structural op, `eval(afterOp) == eval(before)` for the same logical cells (an invariant of Sheets-style rewriting).

## 8. Round-trip drift (markdown)

Specifics checked against `TableModel.kt` where noted.

| Drift source | Status |
|---|---|
| Cell whitespace: the parser trims; a table written as `|  a  |` rewritten as `| a |` | VERIFIED trims. Byte-identical needs raw retention or normalize-on-edit only (and only for edited tables). Decide: is normalizing a whole table's padding on first edit acceptable (a diff-noisy rewrite)? Prefer preserving untouched rows' raw lines and only re-serializing changed rows. |
| Delimiter row: `---` vs `:---` vs `-----:` vs `:-:`; widths | VERIFIED parse keeps only the alignment enum. Keep the raw delimiter cells; regenerate only the changed column's marker. `parseAlignmentCell` requires length >= 2, so `:` alone or `-` alone is "no marker" (Logseq/GFM require at least 3 dashes in strict GFM; real-world files vary). |
| Escaped pipes `\|` | VERIFIED unescaped on read. Serialize must escape `|` (and keep a trailing backslash cell safe: a cell ending in `\` before the closing pipe becomes `\|` and merges cells. `splitTableRow` checks `endsWith("\\|")`, so a cell ending `\\` is ambiguous). |
| Pipes inside backtick code spans | `splitTableRow` splits them (GFM spec says pipe in code span still splits unless escaped, so this matches the spec; but Logseq's behaviour may differ, UNVERIFIED). Round trip must emit `\|`, consistent with the spec. |
| Trailing pipe present/absent, leading pipe absent | VERIFIED the parser accepts both; serialize must remember the style per table (or per row) to avoid rewriting every row's ends. |
| Ragged rows: extra cells dropped, short rows padded | VERIFIED. **Data loss**: loading a ragged table (extra cells) and saving silently drops the extra text. Only re-serialize on user edit, and consider preserving or warning about the dropped cells. |
| Blank lines inside the block | VERIFIED filtered out. Re-serialize drops them. |
| Non-table lines in the same block (text before/after, block properties such as `id::`, `collapsed::`) | The `BlockType.Table` content may include property lines; check what `content` holds before serializing. #724 shows trailing text after the last row is lost by the parse. UNVERIFIED how properties are separated; read `BlockType.Table` detection before the plan. |
| Line endings: CRLF in a graph file | `raw.lines()` handles `\r\n` and the writer may normalize. Keep original line ending style on rewrite, UNVERIFIED how `GraphWriter` treats this. |
| Unicode, emoji, wide chars | Column width alignment (padding for pretty-printed source) uses `String.length` and breaks for combining marks and surrogate pairs; avoid pretty-padding the source (or never pad). |
| Inline markdown and `<br>` inside cells | Cell text is opaque raw markdown to the model. Do not "clean" it. |
| Leading `=` in a cell escaped by the serializer? | Do not escape; the formula is literal text by design. |
| Round-trip property test | `parse(serialize(t)) == t` for generated tables including pipes, backslashes, empty cells, whitespace edge cases (leading/trailing spaces are lost by trim, so the generator must state that cells are trimmed). Plus a second property: `serialize(parse(raw)) == raw` only for canonical-form raw; for non-canonical raw assert an unedited table is never rewritten (dirty flag test). |

## 9. Tests and Bazel traps in this repo

- **Placement** (CLAUDE.md): pure logic (parser, serializer, formula engine, reference rewriting, TSV/CSV codec) goes in `commonTest`; Compose behaviour (tap to edit, link tap, selection model, grid keyboard nav) goes in `androidUnitTest` under Robolectric, not `jvmTest`. A `jvmTest` file importing `androidx.compose.ui.test` needs `// jvmTest-reason: <pixels|desktop-only>` (`TestPlacementAuditTest`). Screenshot tests stay Gradle-only; `TableBlockScreenshotTest` is jvmTest/Roborazzi and needs a real X11 display (run `scripts/jvm-display-check.sh -- ...`; do not treat a bare failure as a regression).
- **Robolectric images**: without `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and `@Config(sdk=[29], qualifiers="w360dp-h780dp-xxhdpi")`, `captureRoboImage` writes a semantic dump, not pixels. Open the PNG before claiming the phone layout works. Robolectric cannot show hit areas, gestures, IME or selection handles; that needs `Pixel_Fold`.
- **Registration**:
  - New test classes must be added to `AllBusinessTests` / `AllJvmTests` / `AllAndroidUnitTests` (explicit lists, guarded under Bazel) and, for commonTest, to `kmp/src/commonTest/kotlin/BUILD.bazel`.
  - `scripts/kmp-module-tests-sync.py` (VERIFIED exists) assigns unregistered commonTest classes to a module test target by package and imports and writes `kmp/module_tests_manifest.bzl` plus generated suites. Run it after adding tests; it may move businessTest/jvmTest classes into `ModuleBusinessTests.kt` / `ModuleJvmTests.kt`. Check `scripts/check-bazel-test-listing.sh` (VERIFIED exists) for the CI gate. Hand-editing generated files will be overwritten.
  - Tests that read sources or resources stay in the umbrella registries (per script docstring).
  - If new sources go in a new package, the module manifest (`kmp/modules_manifest.bzl`) controls which module they belong to; a new package in the wrong module can break `bazel test //kmp/modules:<module>_tests` dependency closure.
- **Internal symbols**: Bazel-built tests can't see `internal` symbols across targets (memory: `project_bazel_new_test_gotchas`). Make the formula and codec entry points `public` or put the test in the same module.
- **Wiring gates** (businessTest): a new seam implementation (e.g. a `TableClipboard` interface with per-platform impls) must be constructed in a composition root or listed in `wiring-allowlist.txt`, or `WiringCompletenessTest` fails. A new screen (the maximized sheet, if a route or screen) must be added to `first-open-screens.txt` and `FIRST_OPEN_SCREENS` in `FirstOpenSmokeTest.kt`. A new wired seam also gets a `wiring-catalog.txt` line plus an entry-point test using `ObservableEffect.assert`.
- **Hygiene audits**: no new `Thread.sleep`, `GlobalScope`, uncancelled `Dispatchers.IO` scope or real-clock `delay` in tests (`TestHygieneAuditTest`). Use `runTest` virtual time for the 500 ms debounce and gesture-timing tests.
- **detekt** does not scan `wasmJsMain`/`wasmJsTest`, so platform clipboard code for Wasm lands unlinted. Review it by hand. wasmJs test compile has a recurring bug "JVM-only coroutine API in commonTest" (memory): keep commonTest free of `runBlocking` and `Dispatchers.IO`.
- **Run discipline**: one gradle or bazel at a time, wrap in `timeout 30m`, `TMPDIR=~/.cache/claude-tmp`, `bazel shutdown` before ending the worktree session. Don't edit sources while a build runs.
- **Feature flag**: requirement says default off. A test that never enables the flag proves nothing about the new path; every new Compose test must set the flag explicitly, and one test should assert the flag-off path still equals today's behaviour (`TableBlockLayoutTest` unchanged).
- **UI verification gate**: CLAUDE.md requires a phone-size render opened as a PNG and a web build check (`wasmJsBrowserDevelopmentRun -PenableJs=true`, resize to about 390 px and desktop) before claiming done. Check a long cell, the largest font setting, and keyboard-only reachability.

## 10. Other risks worth a line

- **Accessibility**: custom grid needs semantics (`Role`, row/column index via `collectionInfo`/`collectionItemInfo`) or TalkBack reads nothing useful. A custom selection model must expose selection to semantics.
- **Performance**: 200 × 12 = 2400 cells each hosting `WikiLinkText` is expensive. In the maximized sheet render non-editing cells as plain `Text` with cached `AnnotatedString` per (cell text), and measure only visible cells; the current "measure the tallest cell per row" layout is O(all cells) per pass and is not lazy (`TableGrid` is a custom `Layout`, VERIFIED as 47 lines in `TableColumnLayout.kt`). Per-row height varies, which fights `LazyColumn` item height caching: scroll-jump when wrapped rows measure late. Use stable keys and avoid `animateItem` on rows with variable heights.
- **Column resize state**: view-only (width, freeze, sort) is not representable in GFM. Persisting it in a block property changes the file and may surprise Logseq; keep in-memory per session unless the owner decides otherwise (Open Question #1). Resize while the 500 ms debounce is pending must not trigger a content write (width changes don't change markdown).
- **Feature interplay**: cross-block selection (`CrossBlockSelection.kt`) and block drag-select/lasso (#303) already own pointer streams on block rows. The table's range-select must not conflict with lasso.
- **Release risk**: a bad serializer silently corrupts user notes. The "untouched byte-identical" dirty check, the feature flag default-off and a one-step "edit as raw markdown" fallback are the three mitigations; ensure raw edit stays reachable from inside the sheet.

## Top priorities for the plan

1. Decide the model: keep raw per-row/delimiter spans and re-serialize only edited rows; add a serializer with escape rules and a round-trip property test first (everything else rests on it).
2. One gesture owner for the table (post-#240 rule), custom range selection rather than platform `SelectionContainer`; hoist editing-cell state outside lazy items and never swallow Enter/Tab while an IME composition is active.
3. Formula engine with hard limits (clamped ranges, parse depth, iterative dependency evaluation, cycle detection, `Throwable` guard), AST-based reference rewriting, and test registration done via `scripts/kmp-module-tests-sync.py` plus the manual registries.
