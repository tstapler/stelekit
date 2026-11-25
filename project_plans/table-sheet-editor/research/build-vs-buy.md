# Build vs Buy: table-sheet-editor

**Date**: 2026-11-24. **Scope**: grid UI, formula engine, SaaS, fork candidates.
**Evidence labels**: VERIFIED = page fetched this session; INFERRED = reasoning or search snippet only. Library status was checked once, not monitored; re-check before any adoption.

Context: the project prefers hand-rolled Compose renderers over third-party libs, with LaTeX as the only carve-out (`memory/project_render_all_markdown.md`). `requirements.md` already says to "confirm no KMP option fits". The constraints that drive the verdicts are: Wasm + Android + Desktop + iOS from commonMain; byte-identical markdown round trip; formulas stored as literal cell text; a closed function set (no execution); rows composed lazily.

## 1. Existing KMP/Compose grid and spreadsheet libraries

### 1a. Compose data-table / grid libraries

Candidates found by search ([klibs.io](https://klibs.io/project/windedge/compose-table), [Maven Central data-table](https://central.sonatype.com/artifact/com.seanproctor/data-table), [Table KMP](https://klibs.io/project/mamon-aburawi/Table-KMP), [excel-compose](https://github.com/RidvanGNC/excel-compose/wiki), [dataTable](https://central.sonatype.com/artifact/io.github.efe-egbevwie/dataTable)):

| Library | Notes | License | Targets |
|---|---|---|---|
| sproctor/compose-data-table | 223 stars. Material-spec table, paginated. No editing or range selection (row `onClick` only). VERIFIED: [repo](https://github.com/sproctor/compose-data-table) | Apache-2.0 | Tagged KMP; targets not listed on the page |
| windedge/compose-table | Static + paginated tables | not checked | Android, Desktop, iOS, WasmJS per klibs.io (INFERRED) |
| mamon-aburawi/Table-KMP | Drag-drop, selection engine, theming (listing text only) | not checked | not checked |
| excel-compose | Dense grid, resizable columns, sort, filters | not checked | Android and Desktop only (INFERRED) |
| efe-egbevwie/dataTable v0.6.1 | Table | not checked | Android, iOS, Wasm, Desktop (INFERRED) |

**Pros**
- Several are Apache-2.0 and cover Wasm.
- They save a little header/column layout code.

**Cons**
- All are small, single-maintainer projects with unverified maintenance status.
- None targets the required behaviour: cell-level in-place edit, rectangular range selection, TSV clipboard, keyboard grid navigation (arrows/Tab/Enter/Esc), frozen header, structural row/column operations.
- A Slack thread notes there is no official Compose data-table ([kotlinlang slack](https://slack-chats.kotlinlang.org/t/28049663/due-to-lack-of-official-data-table-component-and-almost-ever)).
- SteleKit already has `TableBlock.kt` and `TableModel.kt` with wrapping cells, tallest-cell row measurement, `WikiLinkText` link taps and the `splitTableRow` escaping. Adopting a library means re-implementing or fighting all of that.
- The new dependency would need Bazel `rules_kotlin` wiring (`maven_install`) and the module manifest rules, plus an audit of each source set.
- The `Table KMP` and `excel-compose` feature descriptions are marketing-level; I did not read their source.

**Verdict: Not recommended.** The library would own layout and selection, which is where the hard problems are (IME, selection vs tap race, lazy rows).

### 1b. Formula engines

| Option | Status | License | KMP fit |
|---|---|---|---|
| [multiplatform-expressions-evaluator](https://github.com/murzagalin/multiplatform-expressions-evaluator) (murzagalin) | VERIFIED: 46 stars, 109 commits, no GitHub releases listed, 1 open issue. README lists JVM/JS/iOS as "TBD" and does not mention Wasm. Functions: abs, avg, ceil, floor, ln, log, max, min, round, sum, trig; custom functions. | Apache-2.0 | Partial. It has no cell references, ranges, `IF`, `COUNT`, strings or error values. Wasm is not documented. |
| exp4j | Java only ([Wikipedia](https://en.wikipedia.org/wiki/Exp4j)) | Apache-2.0 | JVM only. Not usable from commonMain. |
| mXparser | Java/C#/Kotlin; no native KMP build found ([mathparser.org](https://www.mathparser.org), [nuget license](https://packages.nuget.org/packages/MathParser.org-mXparser/4.4.3/License)) | Dual license; commercial use needs a purchased license. Older versions were BSD. | JVM only, and commercial licensing is a poor fit. |
| Apache POI formula evaluator | JVM only, heavy, OOXML-oriented (INFERRED, well known) | Apache-2.0 | Not KMP. Evaluates against workbooks, not our cell model. Large dependency for a few functions. |
| [HyperFormula](https://hyperformula.handsontable.com/docs/guide/licensing) | TypeScript, 380+ functions | GPLv3 or paid commercial license | JS only. Would need a Wasm/JS-only bridge, and not available on Android/iOS/Desktop. GPLv3 conflicts with distribution unless SteleKit is GPL. Check SteleKit's own license before treating this as a hard no. |
| [IronCalc](https://github.com/ironcalc/IronCalc) | VERIFIED: Rust, early stage, ~4.3k stars, 243 open issues, bindings for Python/JS/Wasm; no Kotlin/Swift/JVM binding mentioned | MIT / Apache-2.0 | Needs Rust toolchain + JNI/cinterop/Wasm bridges across four targets. Disproportionate build complexity. |
| Other (CellarKt, Kexcel) | Pure-Kotlin KMP XLSX *file I/O* only; formulas are written, not evaluated ([klibs.io CellarKt](https://klibs.io/project/VizGhar/CellarKt)) | not checked | Irrelevant to this need. |

**Pros of buying an evaluator**
- Fewer lines of code.
- A pre-existing function library (HyperFormula, IronCalc).

**Cons**
- No candidate gives us all of: KMP incl. Wasm, A1 references and ranges, error values (`#REF!`, `#CYCLE!`), `IF`, and reference rewriting on structural edits. The closest (murzagalin) is plain math only.
- Reference rewriting on insert/delete/sort is *our* logic regardless of the evaluator.
- Locale-dependent number parsing, dependency graph and cycle detection also stay with us.
- A closed, auditable function set is a stated security constraint. A big engine with 380 functions widens the surface.

**Verdict: Not recommended** for any of them. murzagalin is the only plausible option; it is Viable only as a differential-test oracle for the pure-arithmetic subset (JVM test classpath), not as a runtime dependency, because Wasm is undocumented and the project is low-activity.

## 2. SaaS (Google Sheets embed)

**Pros**: full spreadsheet semantics for free.

**Cons**
- Notes live in local markdown files and must stay offline-capable and Logseq-compatible; an embed requires network and an account.
- It cannot edit a pipe table inside an outliner block; there is no block write path (`BlockStateManager` to `GraphWriter`).
- Sending user notes to a third party contradicts the internal classification, even though no data-residency rule is stated.
- Not available in a desktop/iOS native Compose surface without a WebView.

**Verdict: Not recommended.** Interop with Sheets and Excel is still met through TSV clipboard paste/copy, which is in scope.

## 3. Bespoke (LLM-generated) formula parser/evaluator

Scope is small: tokenizer, recursive-descent/Pratt parser, AST, evaluator over `+ - * / ^`, unary minus, parentheses, A1 refs, ranges, `SUM AVERAGE MIN MAX COUNT IF`, comparison operators, error values, a dependency graph with cycle detection and incremental recalculation. This is on the order of 600 to 900 lines of pure commonMain Kotlin, with no platform API.

**Pros**
- Pure commonMain: runs identically on JVM, Android, iOS and Wasm, tested once in `commonTest`.
- Closed function set by construction (no reflection, no I/O), which matches the security constraint.
- Owns the structure we need anyway: reference rewriting, `#REF!`/cycle errors, incremental recompute.
- No new dependency, Bazel or licensing work. Consistent with project preference.

**Cons / correctness risk** (INFERRED; these are the usual failure modes of generated evaluators)
- Operator precedence/associativity slips (`-2^2`, `2^3^2`, `a-b-c`), unary minus on ranges.
- Floating-point and number-format handling (`0.1+0.2` display, locale `,` vs `.`, `1e3`).
- Reference rewriting on row/column insert/delete/sort and range edges (off-by-one, deleted endpoint becoming `#REF!`).
- Cycle detection with ranges (a cell inside its own range).
- Empty-cell and text-in-range semantics for `SUM`/`COUNT`/`AVERAGE`; divide by zero.
- LLM-written code looks plausible and passes the examples it also wrote, so tests must not be derived from the same generation.

**Test strategy that makes bespoke safe** (Kotest property + `kotlin.test`, per CLAUDE.md; commonTest, registered per the module-test generator)
1. **Property: parse/print round trip.** Generate ASTs, pretty-print, re-parse, assert equal AST. Catches precedence and parenthesization bugs.
2. **Property: evaluator vs a naive model.** Generate random arithmetic expressions over small integers/exactly-representable doubles; compare to an independent straight-line evaluation built from the same AST in the test (no shared code).
3. **Differential tests against a reference.** JVM-only test (`jvmTest`) running the same corpus through murzagalin's evaluator for the arithmetic/`sum/min/max/avg` subset (or exp4j), and a golden corpus of cases with expected values taken from the Google Sheets/Excel documented behaviour (hand-checked, committed as fixtures, not generated by the implementer). LibreOffice headless can produce oracle values for broader cases offline (INFERRED tooling, to confirm).
4. **Property: incremental == full.** After any random edit sequence, incremental recalculation must equal a full recompute from scratch. Directly guards the "no O(cells^2) recompute" SLO and stale-dependency bugs.
5. **Property: structural-edit invariance.** Insert/delete/sort rows or columns, then evaluate: values of unaffected formulas are unchanged (reference shifting is semantics-preserving); deleted-endpoint references become `#REF!` and never a silent wrong number.
6. **Property: round trip of markdown.** Formulas are literal text; parse -> edit -> serialize is lossless for untouched cells, and a never-edited table is byte-identical (already in requirements).
7. **Fuzz / safety**: random garbage and deep nesting must return an error value, not throw or stack-overflow (iterative or depth-limited parser); wasm stack is small.
8. **Mutation-prove the tests** (see memory `feedback_review_rounds_catch_fix_regressions`): flip a precedence rule and confirm tests fail before accepting.
9. **Independent review**: a second agent writes the golden corpus from the spec without seeing the implementation.

**Verdict: Recommended**, conditional on the test strategy above landing in the same PR as the evaluator.

## 4. Fork / adapt candidates

| Candidate | Pros | Cons |
|---|---|---|
| Fork murzagalin/multiplatform-expressions-evaluator (Apache-2.0) as the base of our evaluator | Real KMP code, tokenizer/parser/function-registry shape to crib from; permissive license | Low activity; no refs/ranges/errors; JVM/JS/iOS support documented as TBD, Wasm unknown; we would carry a fork and still write the spreadsheet layer. Reading it for design ideas gets most of the value with none of the maintenance. |
| Adapt sproctor/compose-data-table (Apache-2.0) | Column alignment, header layout ideas | Pagination-oriented, no edit/selection; our `TableBlock` is ahead on wrapping and links. |
| Port HyperFormula or IronCalc | Rich function coverage | GPLv3 (HyperFormula), or a Rust build chain (IronCalc); porting hundreds of functions contradicts the "closed small set" goal. |
| Compose Foundation lazy layouts (`LazyColumn`/`LazyRow`, `foundation` text fields) | Already in the stack; lazy row composition in the maximized sheet comes free | This is "build", not a fork, but it is the base the custom grid should sit on. |

**Verdict**: not recommended to fork; read murzagalin's code for structure (Viable as reference only).

## Recommendation

- **Grid UI: build** on Compose foundation, extending `TableBlock.kt` / `TableModel.kt`.
- **Formula engine: build** a small pure-commonMain evaluator with the property + differential test plan in section 3. The evaluator is separable and can be spiked first.
- **No SaaS.** Spreadsheet interop is via TSV/CSV clipboard.
- Revisit only if the formula scope grows past the set in `requirements.md` (e.g. VLOOKUP, dates), at which point re-evaluate this section's table rather than growing the bespoke engine unchecked.

## Sources
- https://github.com/murzagalin/multiplatform-expressions-evaluator
- https://github.com/sproctor/compose-data-table
- https://github.com/ironcalc/IronCalc
- https://hyperformula.handsontable.com/docs/guide/licensing
- https://klibs.io/project/windedge/compose-table
- https://klibs.io/project/mamon-aburawi/Table-KMP
- https://klibs.io/project/VizGhar/CellarKt
- https://github.com/RidvanGNC/excel-compose/wiki
- https://central.sonatype.com/artifact/io.github.efe-egbevwie/dataTable
- https://www.mathparser.org
- https://packages.nuget.org/packages/MathParser.org-mXparser/4.4.3/License
- https://en.wikipedia.org/wiki/Exp4j
- https://slack-chats.kotlinlang.org/t/28049663/due-to-lack-of-official-data-table-component-and-almost-ever
