# ADR-001: Hand-Rolled Parser for `{{query ...}}` Argument Grammar

**Status**: Accepted
**Date**: 2026-09-15
**Feature**: Live Query Blocks

---

## Context

`InlineParser.parseMacro()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt:110-141`) captures everything after a macro name as a single opaque string (`MacroNode.arguments == listOf("(task now)")` for `{{query (task now)}}`). No tokenization of that inner string happens anywhere in the codebase today. Live query blocks need to parse it into a `SimpleQuery` AST covering four base forms (`task`/`todo`, `page-property`, `between`, bare `[[page]]`/`#tag`) plus one level of `and`/`or`/`not`.

### Options considered

**Option A — `h0tk3y/better-parse` parser-combinator library**

The only Kotlin-native parser-combinator library with any Kotlin Multiplatform claim.

Rejected because:
- Does not publish artifacts for two of this project's five enabled KMP targets (`androidTarget()` via AGP, and `wasmJs`) — verified against its own `build.gradle.kts`, which only targets `jvm()`, legacy `js(BOTH)`, and Kotlin/Native presets. Adding it to `commonMain` would fail dependency resolution for Android and Wasm.
- Unmaintained since April 2022 (latest release `0.4.4`), no K2/Kotlin 2.x compatibility evidence.
- The grammar is small and fixed (4-6 forms, one level of nesting) — a general combinator library's abstraction cost isn't repaid at this scale.

**Option B — Full Datalog/S-expression engine (e.g. via `kuzu-jdbc`/`neo4j-java-driver`, already evaluated and commented out in `kmp/build.gradle.kts:134-137` for unrelated work)**

Rejected as wildly disproportionate: these are JVM-only, not KMP, and a full graph-query engine is overkill for parsing 4-6 s-expression forms. Logseq itself only reaches for full Datalog in "advanced query" mode, explicitly out of scope for v1 (`requirements.md`).

**Option C — Hand-rolled recursive-descent parser matching `InlineParser.kt`'s existing style (chosen)**

The macro/highlight/link grammar `InlineParser.kt` already hand-rolls is materially more complex (nested brackets, escape sequences, multiple node types) than a 4-6-form s-expression grammar. A ~150-200 line tokenizer + parser producing a `SimpleQuery` sealed type, in the same package style, compiles identically on all 5 enabled targets with zero new dependencies.

## Decision

Hand-roll a small recursive-descent tokenizer/parser (`dev.stapler.stelekit.query.QueryParser`) over the `MacroNode.arguments` string for `query`-named macros, producing a `dev.stapler.stelekit.query.SimpleQuery` sealed type or `DomainError.ParseError.InvalidSyntax` on failure. No new dependency is added to `kmp/build.gradle.kts`.

## Consequences

- Zero cross-target compatibility risk; compiles on JVM, Android, iOS, and `wasmJs` identically.
- Bespoke error handling (no free diagnostics/syntax-tree tooling) is acceptable because the feature's own hard requirement is graceful fallback-to-literal-text for anything unparseable, not rich diagnostics.
- Grammar semantics (argument order, `between` scoping to journal-date-range, `task`/`todo` aliasing) are sourced from Logseq's own upstream `rules.cljc` and `docs/Queries.md` as a reference, not ported as code — see `research/build-vs-buy.md` and `research/features.md`.
- If a future feature needs a materially larger/more-nested grammar (arbitrary Datalog nesting, `sort-by`, additional filters), re-evaluate parser-combinator libraries at that time — the crossover point is real operator precedence or dozens of forms, neither of which applies here.
