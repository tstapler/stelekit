# ADR-002: Build a closed-grammar formula engine; literal-text formulas

**Status**: Proposed (accepted when Tasks 2.4a-2.4c are green and mutation-proven)
**Date**: 2026-11-24

## Context
Formulas are stored as literal cell text (`=SUM(B2:B5)`), no cached value, no hidden state (owner decision). Security NFR: closed arithmetic/aggregate set, no network or file access. research/build-vs-buy.md found no KMP evaluator with references, ranges, `IF`, error values and Wasm support (murzagalin: plain math, Wasm undocumented; HyperFormula GPLv3/JS; IronCalc Rust bridge).

## Decision
Build a pure commonMain engine in `dev.stapler.stelekit.table.formula`; no new dependency.

**Grammar and set.** Numbers, A1 refs (`A1`, `$A$1`), ranges (`A1:B5`), `+ - * / ^`, unary minus, `%` postfix, comparison (`= <> < <= > >=`), parentheses, text literals in double quotes (comparison and `IF` results only). Functions, case-insensitive, fixed allow-list: `SUM AVERAGE MIN MAX COUNT COUNTA IF ROUND ABS`. This extends the requirement's list by `COUNTA`, `ROUND`, `ABS` (needed for totals rows and currency display; no other surface). Anything else is `#NAME?`.

**Addressing.** Row 1 is the header row, so `A1` is the first header cell and `=SUM(B2:B5)` sums body rows. Columns A..ZZ (702). A single-cell reference outside the table is `#REF!` at evaluation time. A range that lies wholly outside the table is clamped to nothing, not an error (Excel semantics, owner decision): `SUM` and `COUNT` give 0, `AVERAGE` gives `#DIV/0!`, `MIN` and `MAX` give 0.

**Operator precedence (Excel semantics, owner decision).** Highest to lowest: unary minus, postfix `%`, `^`, `* /`, `+ -`, comparisons. `^` is left-associative, so `=2^3^2` is 64 (not 512). Unary minus binds tighter than `^`, so `=-2^2` is 4; write `=-(2^2)` for -4. All other binary operators are left-associative.

**What is a formula.** A cell is formula-shaped when its raw text starts with `=` at index 0 and the next non-space token can start an expression (number, ref, function name, `(`, unary sign, quote, or the literal error token `#REF!`). The `#REF!` start is required so that the text ADR-003 writes after a delete (`=#REF!*2`, `=SUM(#REF!)`) stays a formula: it re-parses to a `RefError` node, evaluates to `#REF!`, prints back unchanged and is still rewritten by later edits. Any other `#` start (`=#nope`) is not formula-shaped. Formula-shaped text that fails to parse shows `#PARSE!` (source text unchanged). Text such as `=== heading ===` or a lone `=` is not formula-shaped and renders as literal text. There is no escape character; a leading apostrophe is not Logseq-safe and a rule that needs one is worse than the rare false positive.

**Numbers.** Locale-independent: optional sign, digits with optional strict `,` thousands groups, optional `.` fraction, optional exponent, optional trailing `%` (divide by 100). `NaN`, `Infinity`, hex-floats and `1d`/`1f` suffixes are not numbers. `007` evaluates as 7 and is never rewritten on display or save. Display rounds to 12 significant digits so `=0.1+0.2` shows `0.3`; the file never stores localized text.

**Semantics.** Blank cell: skipped by aggregates, 0 in arithmetic. Text in arithmetic: `#VALUE!`; text inside a range for `SUM/AVERAGE/MIN/MAX/COUNT` is skipped. `AVERAGE` of nothing: `#DIV/0!`; `SUM`, `MIN`, `MAX` of nothing: 0. Errors propagate to dependents. A cycle marks every cell in it `#CYCLE!` (including a cell inside its own range) and is not cached.

**Limits (hard).** Formula length 1000 chars; parser nesting depth 64; ranges clamped to table bounds before iteration; a per-formula budget of 10 000 operand cell reads (after range clamping), exceeded means that formula is `#LIMIT!`; table cap 20 000 cells. The budget counts only the formula's own operand reads, never the cached values of its precedents, so it does not depend on how many cells a recompute pass touches: an incremental pass and a full pass produce the same `#LIMIT!` cells and the `incremental == full` property holds at the limit. Above the 20 000-cell cap no `TableCalc` is built: cells show their raw text, the sheet shows "Formulas are off: this table has more than 20,000 cells", and editing stays allowed (inline and sheet alike). No engine function recurses past the parser depth bound of 64 (printer, rewriter and `TabularText` included); the `Throwable` guard is a backstop, and is not relied on for `StackOverflowError`/`OutOfMemoryError`, which are not reliably catchable on Wasm/JS. Evaluation is iterative (topological order over the dirty subgraph), never recursive cell-to-cell, and the whole evaluation boundary catches `Throwable` and returns an error value.

**Incremental.** Dependency graph stores ranges as rectangles; an edit recomputes only transitive dependents; an evaluation counter is exposed to tests (`incremental == full` property).

## Consequences
Positive: no supply-chain or licence exposure; one test surface; closed set auditable. Negative: we own correctness; mitigated by the independent golden corpus, property tests and a mutation pass (build-vs-buy section 3). Formulas appear as plain text in Logseq (accepted by the owner).

## Alternatives rejected
Buy or port an evaluator (see Context); murzagalin as a runtime dependency; a differential oracle library even test-only (adds a test dependency for a JVM-only subset; the independent golden corpus authored from Sheets/Excel documented behaviour replaces it).
