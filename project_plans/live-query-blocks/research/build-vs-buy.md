# Research: Build vs. Buy — Query Parser & Execution Engine

**Feature**: `project_plans/live-query-blocks/requirements.md`
**Question**: should the `{{query (...)}}` argument parser and/or the query-execution
engine be built from scratch, or sourced from an existing library?

## Context checked

- `kmp/build.gradle.kts:20-45` — enabled KMP targets: `jvm()`, `androidTarget()` (via
  `com.android.library`), `iosX64()`/`iosArm64()`/`iosSimulatorArm64()`, and
  `wasmJs { browser(); binaries.executable() }` gated behind `enableJs=true` in
  `gradle.properties:20` (confirmed set to `true`). Note it is **`wasmJs`**, not the
  legacy Kotlin/JS `js` target — this distinction matters below.
- No parser-combinator or grammar library is present anywhere in `commonMain`'s
  dependency block (`kmp/build.gradle.kts:48-92`). Arrow (`2.2.1.1`), SQLDelight
  (`2.3.2`), kotlinx-coroutines/datetime/serialization are the only relevant
  cross-cutting deps.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt:110-141` —
  the existing `{{...}}` macro parser is a **hand-rolled recursive-descent /
  token-accumulation parser** (`parseMacro()`), consistent with the rest of the file's
  style (token-type `when` dispatch, manual `advance()` calls, no external grammar
  DSL). This is the style precedent any new query-argument parser should match.
- Two commented-out graph-database dependencies exist in `kmp/build.gradle.kts:134-137`
  (`kuzu-jdbc`, `neo4j-java-driver`) — evaluated previously for unrelated performance
  work, not applicable here (JVM-only, not KMP; full Datalog/Cypher engines are wildly
  disproportionate to parsing 4-6 s-expression forms).

## Option 1 — Existing OSS parser-combinator library

**Candidate evaluated**: [`h0tk3y/better-parse`](https://github.com/h0tk3y/better-parse)
— the only actively-referenced Kotlin-native parser-combinator library with any KMP
claim ("A nice parser combinator library for Kotlin JVM, JS, and Multiplatform
projects").

Verified directly from source, not from memory:
- Target list in [`build.gradle.kts`](https://raw.githubusercontent.com/h0tk3y/better-parse/master/build.gradle.kts):
  `jvm()`, `js(BOTH) { browser(); nodejs() }` (**legacy JS target**, not `wasmJs`),
  plus `presets.withType<AbstractKotlinNativeTargetPreset>()` — this loop covers
  Kotlin/Native presets (iosX64/iosArm64/iosSimulatorArm64, macos*, mingw*, linux*,
  watchos*, tvos*, androidNativeX64/Arm64/Arm32) but **not** the AGP-based
  `androidTarget()` SteleKit uses (`com.android.library` + `androidTarget()` is a
  distinct, non-native KMP target that requires explicit Android Gradle Plugin
  wiring in the producing library — better-parse's build script has none).
- Release cadence, from [Maven Central listing](https://mvnrepository.com/artifact/com.github.h0tk3y.betterParse/better-parse):
  latest is `0.4.4`, published **Apr 21, 2022** — over 4 years stale as of this
  research (2026-09-15). 14 versions total across Central/JCenter/Spring Plugins;
  JCenter is itself defunct, indicating the version history predates that
  repository's 2021 shutdown.
- License: Apache 2.0 (fine, no conflict).

**Pros**
- Real combinator API (`and`/`or`/`map`/`separatedTerms`/`leftAssociative`) that
  reads close to a BNF grammar — would shorten grammar authoring for a slightly
  larger form set than v1's four/five.
- Apache 2.0, no licensing friction.

**Cons**
- **Does not publish artifacts for two of the five KMP targets this project
  actually ships** (`androidTarget()` and `wasmJs`) — adding it to `commonMain`
  would either fail dependency resolution for those source sets or force it into
  `jvmCommonMain`-only, defeating the purpose of a `commonMain` grammar shared
  across all targets.
- Unmaintained for 4+ years; no evidence of Kotlin 2.x / K2 compiler compatibility
  testing, no wasmJs support ever added.
- Binary-size and version-pinning cost is real per the constraint in
  `requirements.md:78` ("No new dependencies unless research shows the existing
  stack ... is insufficient") — and the existing stack (hand-rolled tokenizer,
  same pattern as `InlineParser.kt`) is not insufficient for a 4-6-form grammar.

**Verdict: Not recommended.**

No other Kotlin/JVM Datalog-subset or S-expression-specific parsing library with
any KMP multiplatform support was found (`kotlisp`/`parsekt`, `ParserKt` are
single-target JVM hobby projects, not KMP-published, not evaluated further — using
one would trade the same commonMain-portability problem for far less maturity than
better-parse). For a fixed ~4-6-form s-expression grammar
(`todo`, `page-property`, `between`, tag/page-ref), a general Datalog engine is
categorically overkill — Logseq itself only reaches for full Datascript/Datalog in
its "advanced query" mode, explicitly out of scope per `requirements.md:58`.

## Option 2 — SaaS / managed API

Not applicable. This is a parser over a small embedded DSL and a query executor
over the user's local, offline-first block/page graph (`SqlDelightBlockRepository`,
`DatalogBlockRepository` in-memory fake). There is no network-addressable service
that could stand in for either the string parser or the graph query; every existing
repository read path in this codebase (`BlockReadRepository`, `BlockSearchRepository`)
is local SQLDelight/Flow-based per `CLAUDE.md`'s dispatcher-matrix section. Moving on.

## Option 3 — LLM-generated / hand-rolled implementation vs. general-purpose dependency

The grammar is small and fixed: parenthesized head-symbol forms —
`(todo TODO [DONE ...])`, `(page-property key value)`, `(between date1 date2)`,
plus bare tag/page-ref tokens (`#tag`, `[[Page]]`). This is materially simpler than
the highlight/macro/link grammar `InlineParser.kt` already hand-rolls today (that
file handles nested brackets, escape sequences, and multiple macro types in the same
token-accumulation style used at `InlineParser.kt:110-141`).

**Hand-rolled recursive-descent parser (matching `InlineParser.kt` style)**

Pros:
- Zero new dependency, zero cross-target compatibility risk (compiles identically
  on all 5 already-enabled targets since it uses only `kotlin.text`/`CharSequence`).
- Matches the existing lexer/parser idiom exactly — a future maintainer reading
  `InlineParser.kt` already knows how to read the query parser; no second parsing
  paradigm to learn.
- Scope is genuinely small: tokenize on whitespace/parens respecting quoted
  strings, recognize the head symbol, dispatch to one of 4-5 typed argument
  parsers. Realistically 100-200 lines plus a `sealed class SimpleQuery` model,
  well within the range estimated in the research prompt.
- Testable with the same unit-test style already used for `InlineParser` /
  `OutlinerExtensionsSpec.kt:390-401` (parse-only cases already exist for the
  `MacroNode` shape this parser will consume as input).

Cons:
- Hand-rolled means bespoke error handling and no free syntax-tree/diagnostics
  tooling (better-parse's `liftToSyntaxTreeGrammar()` gives that for free) — but
  v1's own requirement is graceful fallback-to-literal-text for anything
  unrecognized (`requirements.md:36-38, 55`), not rich diagnostics, so this
  capability isn't needed.
- Correctness risk is nonzero for a first-time author, but it's bounded: 4-6 forms,
  each independently unit-testable, and the "unsupported form" fallback (already a
  hard requirement) contains the blast radius of any grammar gap to "renders as
  literal text," not a crash.

**General-purpose parser-combinator dependency**

Already covered under Option 1 — ruled out primarily on KMP-target coverage, not
on abstraction cost. Even if better-parse covered all 5 targets, the abstraction
cost (learning `Tuple`/`Separated`/`leftAssociative` combinators, pulling a new
transitive dependency into `commonMain` across 5 published target artifacts, and
carrying a version-pin for a 4-year-unmaintained library) would still outweigh the
benefit for a grammar this small. The crossover point where a combinator library
starts paying for itself is roughly "the grammar has real operator precedence,
recursive nesting, or a form count in the dozens" — none of which applies to v1's
`(and (todo TODO) (page-property type task))`-style compound predicates being
explicitly out of scope (`requirements.md:113-115`).

**Verdict: Hand-rolled recommended.**

## Option 4 — Fork or adapt Logseq's own grammar

Logseq's simple-query rule semantics are defined as Datalog rules in
[`logseq/logseq: deps/db/src/logseq/db/frontend/rules.cljc`](https://github.com/logseq/logseq/blob/master/deps/db/src/logseq/db/frontend/rules.cljc)
(fetched and read directly, current `master` as of 2026-09-15). Key confirmed
grammar semantics, useful to adapt (not port — different language, different
runtime, Datascript entities vs. SQLDelight rows):

- **`:between`** is defined as:
  ```clojure
  :between '[(between ?b ?start ?end)
             [?b :block/page ?p] [?p :block/tags :logseq.class/Journal]
             [?p :block/journal-day ?d] [(>= ?d ?start)] [(<= ?d ?end)]]
  ```
  This directly confirms the requirements doc's open question at
  `requirements.md:31-33`: `(between ...)` operates on **journal-day range**
  (a journal page's `:block/journal-day`), not an arbitrary date property on any
  block. SteleKit's implementation should scope `(between date1 date2)` to blocks
  under journal pages whose journal date falls in the range, matching this
  upstream semantic exactly.
- **`:task`** is defined against a status property with a caller-supplied set of
  accepted statuses (`(task ?b ?statuses) ... (contains? ?statuses ?val)`) —
  confirms `(todo TODO)` should be modeled as "task marker ∈ requested status set,"
  matching the existing `TaskMarkerNode`/marker-set already hard-coded in
  `InlineParser.kt:97` (`TODO, DONE, NOW, LATER, WAITING, CANCELLED, DOING, WAIT,
  STARTED`).
- **`:page-property`** in this file is superseded by newer `:scalar-property` /
  `:ref-property` rules (this file is Logseq's *current* DB-graph query engine,
  post their 2025 Datascript-schema migration) — the simpler markdown-graph-era
  `page-property key value` semantic (property exists on the page and equals a
  value) is the one relevant to SteleKit's markdown-file-based model; this file
  still confirms `page-property`/`has-property`/`ref->val` as the right
  conceptual split (existence check vs. value-equality check).
- **`:page-ref`** confirms tag/page-ref queries reduce to a `:block/refs`
  containment check, i.e., "does this block's outgoing reference set contain the
  target page" — directly portable to a `blockRefs` join against SteleKit's
  existing reference-tracking tables.

**Caveat**: this file is Logseq's *current* (DB-graph) engine, which is a full
Datascript/Datalog rule system operating over a different entity model
(`:block/tags`, `:logseq.property/*` idents) than either SteleKit's SQLDelight
schema or classic markdown-graph Logseq's simpler frontend query DSL. It is useful
exclusively as a **semantic reference for what each query form means** (especially
resolving the `between` open question), not as code or a schema to port — the
actual execution must be written against SteleKit's own `BlockReadRepository`/
`BlockSearchRepository` SQL surface per the repo's Arrow/dispatcher conventions.

**Verdict: Viable — used for grammar/semantics reference, not code adaptation.**
Recommend citing `rules.cljc`'s `:between`/`:task`/`:page-ref` clauses in the plan
doc when defining SteleKit's own `SimpleQuery` sealed-class semantics, so the
argument order and range semantics match user expectations from existing Logseq
graphs.

## Final Recommendation

Hand-roll the query-argument parser as a small recursive-descent parser in the same
style as `InlineParser.kt`'s existing macro/token handling (Option 3) — no new
dependency. `better-parse` is the only KMP-adjacent parser-combinator library with
any real-world usage, but it fails to publish artifacts for two of this project's
five enabled targets (`androidTarget()`, `wasmJs`) and has been unmaintained since
April 2022, so it is disqualified on portability and maintenance grounds before
abstraction-cost tradeoffs even enter the discussion (Option 1: not recommended).
A managed/SaaS query API is inapplicable to a local, offline-first embedded query
over the user's own graph (Option 2: not applicable). No Kotlin Datalog-subset or
S-expression library exists with adequate KMP support either, and a full Datalog
engine would be wildly disproportionate to a fixed 4-6-form grammar (folded into
Option 1's verdict). Do use Logseq's own upstream Datalog rule definitions
(`logseq/logseq`'s `rules.cljc`) as the semantic source of truth for exact argument
meaning — most concretely, confirming `(between ...)` is a journal-date-range query,
not a generic date-property query — while writing the actual parser and execution
logic natively against SteleKit's own repository/dispatcher conventions (Option 4:
viable as a reference, not as ported code).
