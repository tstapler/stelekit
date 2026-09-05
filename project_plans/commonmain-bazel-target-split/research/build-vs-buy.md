# Build vs. Buy Research: commonmain-bazel-target-split

**Date**: 2026-09-04

## Piece 1: Starlark macro

No generic "buy" option exists — a macro standardizing `kotlinc_opts`, Compose/serialization
plugin selection, `associates` wiring to `:platform-core`, and visibility is inherently
repo-specific (it encodes *this* repo's target-boundary conventions, not a general one). The
research question was narrower: is there prior art worth adapting rather than writing from
scratch?

- **rules_kotlin's own docs** (`docs/kotlin.md`) explicitly document the "wrap a `kt_jvm_library`
  in a macro" pattern as the sanctioned way to add project-specific conventions (e.g. bundling a
  `proto_library` + `genrule` + `kt_jvm_library`) — there's no separate "macro-authoring SDK,"
  just the rule attrs themselves (`associates`, `plugins`, `kotlinc_opts`, `common_srcs`) exposed
  for a macro to set consistently. [rules_kotlin kotlin.md](https://github.com/bazel-contrib/rules_kotlin/blob/master/docs/kotlin.md)
- **This repo already forked rules_kotlin once** for exactly this class of problem:
  `third_party/patches/rules_kotlin_kmp.patch` (44 lines) adds the `common_srcs` attribute to
  `kt_jvm_library`/`compile.bzl` to emit `-Xcommon-sources` for expect/actual resolution — the
  same mechanism the planned `:platform-core` split still needs. That patch is the direct,
  in-repo precedent for "extend rules_kotlin's Starlark surface," not something to source
  externally.
- **No public reference macro was found** matching this repo's specific shape (KMP `common_srcs`
  + Compose + serialization + `associates`-to-a-shared-core, in one macro). Searches for
  Slack/Square/other large Bazel+Kotlin OSS tooling repos surfaced only the generic
  `kt_jvm_library`/`kt_compiler_plugin` rule docs, not a comparable macro to fork. `rules_kotlin`
  friend-visibility (`associates`, formerly a singular `friends=`) is itself young — [issue #211](https://github.com/bazelbuild/rules_kotlin/issues/211)
  tracks broadening it — so multi-target `associates`-at-scale patterns aren't yet a solved,
  copy-pasteable community problem.
- **A distinct, asymmetric requirement inside the macro**: only `:platform-core` needs
  `common_srcs`/`-Xcommon-sources` wiring (it carries the real `expect` declarations); the ~600
  leaf-package files need none of that — just `associates = [":platform-core"]` plus whichever of
  the two compiler plugins their package uses. The macro should make `common_srcs` an
  optional/rarely-used parameter, not a required one, to avoid every leaf-package `BUILD.bazel`
  needing to reason about it.

**Verdict: build, in-repo.** There is nothing to buy. Treat the existing
`rules_kotlin_kmp.patch` as the reference precedent for "how much Starlark surface we're willing
to fork," and scope the macro to the pilot's actual needs (9 zero-risk packages, no
Compose/serialization, no `common_srcs`) before generalizing it for the 18 hub-touching packages
where plugin/common_srcs variance is real.

## Piece 2: cross-package internal-visibility audit

### Option: Konsist
Konsist expresses architectural rules like "no internal symbol from package A referenced in
package B" via its declaration/architecture checks (`Konsist.scopeFromProduction()...assert {}`),
and per its own docs and multiple write-ups is commonly used for exactly this class of
Kotlin-Multiplatform boundary check. [Konsist docs](https://docs.konsist.lemonappdev.com/) ·
[Konsist + KMP](https://medium.com/@lahirujay/konsist-protect-kotlin-multiplatform-projects-from-architecture-guidelines-violations-d88db0614cbd)

- **Pros**: purpose-built for this exact rule shape; mature enough to have multiple
  independent adoption write-ups (Mercedes-Benz.io, ProAndroidDev); expresses the rule
  declaratively rather than needing a bespoke resolver.
- **Cons**: Konsist runs as a JUnit/Kotest test over a compiled/parsed source tree — it needs a
  JVM test-runtime entry point (a Gradle test task in practice; nothing in this repo currently
  runs Konsist, and this repo's stated direction is Bazel-canonical with Gradle kept only for
  iOS/screenshot-tests/benchmarks). Standing it up costs a new dependency + a new test target
  purely to run a **one-time, pre-migration** audit — a mismatch between setup cost and use
  frequency for this specific deliverable (it would be more justified as a *permanent* CI gate,
  which is not what's being asked for here).
- **Verdict**: good tool, wrong shape for a one-off pre-migration script in a Bazel-first repo.
  Worth reconsidering later if the org wants a standing "no internal leakage" CI gate after the
  migration (separate decision, out of scope here).

### Option: detekt custom rule
This repo already runs detekt via Gradle (`kmp/build.gradle.kts:19,1175-1202`, config at
`kmp/config/detekt/detekt.yml`, plus a custom `buildSrc.jar` ruleset and
`io.nlopez.compose.rules:detekt`) — so detekt is a live, already-integrated tool, not a new
dependency.

- Checked `detekt.yml` directly: it has no rule matching this need today — `DefaultsVisibility`
  and `NestedClassesVisibility` are unrelated style rules about explicit-modifier hygiene, not
  cross-file/cross-package reference detection.
- **Pros**: reuses existing infra (this repo's `buildSrc.jar` custom-ruleset mechanism proves
  custom detekt rules are already a supported pattern here); would run automatically going
  forward if kept as a rule, not just a one-off.
- **Cons**: a rule that actually resolves "does this reference point at an `internal` symbol
  declared in a *different* package" needs semantic type resolution (detekt's
  type-resolution/binding-context rules, which require a resolved classpath), not just PSI/AST
  matching — visitor-only detekt rules can't reliably tell where an identifier's declaration
  lives. That raises the implementation cost well above a syntactic script for what's fundamentally
  a one-time audit.
- **Verdict**: plausible if this were meant to be a permanent lint rule; over-built for a
  pre-migration, throwaway-after-first-use audit.

### Option: ktlint
Not evaluated further — ktlint is a formatting/style linter (PSI-only, no cross-file semantic
awareness) and has no architectural-boundary or visibility-crossing check category. Not a fit.

### Option: kotlinc `-Xexplicit-api=strict` / compile-and-read-errors
- `-Xexplicit-api=strict` only enforces that **public** API declarations carry explicit
  visibility/return-type modifiers — it says nothing about whether an `internal` declaration is
  referenced from outside its module. Not applicable to this check at all.
- The deeper question — "is the check just try-compiling the split and reading the errors" — is
  **valid as a verification step but not as the pre-migration audit itself**. Once a package is
  actually split into its own Bazel target, kotlinc *will* fail fast on any surviving
  cross-package `internal` reference (Kotlin's `internal` is module/compilation-unit scoped, so
  this is a real, cheap, zero-false-positive signal — see [BAZEL-429](https://youtrack.jetbrains.com/issue/BAZEL-429),
  which tracks the same "internal across Bazel-module boundary" gap generally, and
  [rules_kotlin#211](https://github.com/bazelbuild/rules_kotlin/issues/211) for the `associates`
  friend-visibility mechanism that's the actual fix once usage is found). But the requirement is
  explicitly to know the blast radius **before** attempting the split (`requirements.md`
  Feasibility Risk #1: "may force different groupings than the coarse public-import analysis" —
  discovered via a failed compile is exactly the outcome being designed against). So: compile-and-
  read-errors is the right **post-hoc confirmation** that a completed split is clean, not a
  substitute for the pre-migration audit.

### Option: bespoke ast-grep-based script
- `sg` is already a standard, pre-approved tool in this environment (confirmed installed:
  `ast-grep 0.44.0` at `/home/tstapler/.cargo/bin/sg`).
- The check decomposes into two tractable passes: (1) `sg` structural patterns over every
  `commonMain/**/*.kt` file to collect `internal` declarations (`class`, `fun`, `val`/`var`,
  `object`, `typealias`) tagged with their declaring package; (2) a cross-reference pass (import
  statements + fully-qualified references) that flags any file whose package differs from an
  internal symbol's declaring package but which still references that symbol's identifier. This
  is exactly the shape of a one-time analysis tool, not a long-lived enforcement mechanism —
  matching the project's own framing of piece 2 as a pre-migration blast-radius report.
- **Cons** (open honestly): purely syntactic — no real symbol-table resolution, so accuracy on
  edge cases (shadowed names, star imports, type aliases pointing at an internal type,
  companion-object-scoped `internal` members) is lower than Konsist/detekt-with-binding-context.
  Mitigated by the fact that a human reviews every `BUILD.bazel` diff and the actual compile
  errors are the final backstop (see previous section) — false negatives here cost a discovered-
  late compile error, not silent breakage; false positives cost a few minutes of a human
  double-checking a flagged reference.
- **Verdict: build this one.** It is genuinely the right call for a small, one-time-ish tool: no
  new runtime dependency, reuses a tool this repo's conventions already prefer over `grep` for
  structural code search, and its lower precision is an acceptable trade given the compile-time
  backstop and human review gate that already exist in this repo's process.

**Final recommendation for piece 2**: bespoke `sg`-based two-pass script (declare + cross-
reference). Do not adopt Konsist or a type-resolution detekt rule for this one-off use; revisit
Konsist only if the org later wants a standing "no internal leakage" CI gate post-migration.

## Piece 3: import-cycle detection

### `mcp__kibitzer__architecture_assessment`
Verified directly in this session (this repo had no `.claude/inspect.json` prior to this
research — one was created during this research pass by a parallel research agent; earlier
`architecture_assessment`/`list_checks` calls against the un-configured repo returned "no
`.claude/inspect.json` found above this path," confirming the tool **hard-requires** that config
file to exist, even though its own tool description doesn't say so).

- **Kotlin support is real, not a gap**: kibitzer's own source (`~/code/github.com/tstapler/kibitzer/src/import_graph.rs`)
  parses Kotlin via `tree-sitter-kotlin-ng`, extracts package identity from the
  `package_header` node, and walks `import` nodes to build package-to-package edges — the same
  "qualified name" resolution family used for Java (verified in the tool's own source, not just
  its description).
- **Ran it for real** against this codebase after fixing the config (`architecture_checker`,
  not `language`, is the correct schema field — the file I found mid-session had an invalid
  key and kibitzer errored on it until corrected):
  - Scoped to `repository/**` alone: 44 complexity/coupling findings across 37 files, no
    cycle findings (expected — cross-package cycles can't surface when only one package is in
    scope; the import graph only sees edges between in-scope files).
  - Unscoped, full `kmp/src/commonMain/kotlin/**` (615 files): **803 findings, 0
    `[import-cycle]` findings** — the checker ran successfully at full repo scale and found no
    package-level cycles today. The `coupling` checker (also configured) is a useful bonus
    signal for this same migration: `dev.stapler.stelekit.db` alone imports **174 distinct
    packages**, by far the largest fan-out in the tree — strong independent corroboration that
    `db` is correctly classified as a hub-touching package needing careful `:platform-core`
    dependency planning (cross-reference against whatever Research Agent 2's `architecture.md`
    /`pitfalls.md` independently concluded — this session did not read those files' cycle-
    specific findings in depth to avoid duplicating that agent's own verification work, but did
    confirm `research/features.md` was not yet written at the time of this research pass, so
    that hand-off could not be read).
  - The multi-path brace-glob syntax (`{repository,db,model,platform}/**`) is **not** supported
    by kibitzer's `scope` glob (returned 0 files/0 findings silently) — a single `**` glob or an
    unscoped run is required to see cross-package edges; this is a usability gotcha worth noting
    in the plan/tooling docs, not a functional gap.
- **Verdict: adopt it for import-cycle detection.** It is squarely in its advertised scope, is
  demonstrably Kotlin-aware (not a generic-language approximation), and already ran cleanly
  against the full 615-file `commonMain` tree. The one required action is committing a real
  `.claude/inspect.json` (this research left a minimal working one at the repo root:
  `{"checks":[{"name":"kotlin-import-cycles","architecture_checker":"import-cycles","severity":"advisory","triggers":["batch"]}, ...]}`)
  — without it the tool is silently unusable despite being "confirmed available."

### Alternatives (evaluated, not recommended as primary)
- **Gradle-level cycle detection**: this repo has no `dependency-analysis`/module-graph/
  `explicitApi` Gradle plugin configured (checked `kmp/build.gradle.kts`'s plugin block — only
  `com.android.library`, `org.jetbrains.compose`, `app.cash.sqldelight`, `roborazzi`,
  `kotlinx.benchmark`, `detekt`). More fundamentally, Gradle sees this code as **one** module
  (`:kmp`) — there's no existing Gradle-level package boundary to run a cycle check against in
  the first place, so there's nothing to adapt.
- **`bazel query "somepath(//A, //B)"`**: a real, standard Bazel idiom, but it's inherently
  **post-hoc** — it can only run once the target boundaries already exist as Bazel targets. It
  cannot answer "would splitting package A and B introduce a cycle" before either target exists.
  Useful as a CI-time regression check *after* the pilot slice lands (confirm the new targets
  form a DAG), not as the pre-migration planning tool risk #2 asks for.
- **Konsist / JetBrains tooling for import graphs**: Konsist can express "package A must not
  depend on package B," which is a layering rule, not general cycle detection across the whole
  graph; would need the same Gradle-test-runtime tradeoff as piece 2. Not preferred over kibitzer,
  which is already available, already Kotlin-aware, and already verified working.

**Final recommendation for piece 3**: adopt `mcp__kibitzer__architecture_assessment`'s
`import-cycles` checker as the primary tool, backed by `bazel query "somepath(...)"` as a
cheap, independent post-hoc regression check once real targets exist. No bespoke cycle-detection
script is needed.

## LLM-generated vs. battle-tested library assessment

Both remaining custom pieces (piece 2's audit script, and — if kibitzer's diagrammed report
isn't enough — any supplementary cycle-plan tooling) are **advisory, pre-migration, low-frequency
tools**: they run a handful of times per package during Phase 3/5 planning, their output is a
report a human reads before hand-writing a `BUILD.bazel` diff, and every actual guarantee is
backstopped by a real compiler error (Kotlin's `internal` enforcement) or a real Bazel graph
error (`bazel query`/a genuine cycle blocking the build) if the advisory tool is wrong in either
direction.

- **Correctness risk is real but bounded and cheap to catch.** A false negative in the
  `sg`-based internal-usage script means a migration PR discovers the issue at compile time
  instead of in the pre-flight report — annoying, not dangerous, and exactly the kind of failure
  this repo's own `CLAUDE.md` "no completion claim without proof" discipline already guards
  against (the human running the migration PR sees the compile error before merging, same as any
  other CI failure). A false positive costs a few minutes of the human double-checking a flagged
  line.
- **The cost side of "adopt a library instead" is not free either.** Konsist specifically would
  add a new JVM test-runtime dependency and a new Gradle test target in a repo whose explicit,
  written direction is "Bazel is the canonical build system" — a real, ongoing maintenance cost
  (keeping that Gradle test wired, keeping Konsist's version current) for a tool used a handful of
  times during one migration effort.
- **Verdict: hand-roll both, with LLM assistance, is the right call here.** Use an LLM to write
  the `sg` two-pass script quickly, but treat its output as *advisory* input to human-reviewed
  `BUILD.bazel` diffs, not as a gate that blocks or silently approves a migration PR by itself.
  Do not invest in Konsist or a type-resolution detekt rule unless the org decides, separately
  from this migration, that it wants a standing "no internal leakage" enforcement mechanism for
  the long term — a different problem with a different frequency profile than what Phase 2 is
  scoping.

## Overall recommendation

Build the Starlark macro in-repo (there is no external "buy" option; use the existing
`rules_kotlin_kmp.patch` as the precedent for how much of rules_kotlin's Starlark surface this
repo is willing to extend, and keep `common_srcs` an optional macro parameter used only by
`:platform-core`). For the internal-visibility audit, build a small `sg`-based two-pass script
(declare-internal-symbols, then cross-reference usage) rather than adopting Konsist or writing a
type-resolution detekt rule — the setup cost of either library outweighs the value for a one-time,
human-reviewed, compile-error-backstopped audit. For cycle detection, adopt
`mcp__kibitzer__architecture_assessment`'s `import-cycles` checker as the primary mechanism — it
is verified Kotlin-aware, ran cleanly across the full 615-file `commonMain` tree with zero cycle
findings today, and only required a corrected `.claude/inspect.json` to unlock (the file it needs
is now in place at the repo root); pair it with `bazel query "somepath(...)"` as a cheap post-hoc
regression check once real Bazel targets exist. Do not build a bespoke cycle-detection script —
that would duplicate a tool that's already available, already correct, and already tested against
this exact codebase.

## Sources
- [rules_kotlin `docs/kotlin.md`](https://github.com/bazel-contrib/rules_kotlin/blob/master/docs/kotlin.md) — macro-wrapping pattern, rule attribute reference
- [rules_kotlin issue #211 — broaden `associates`/friend support](https://github.com/bazelbuild/rules_kotlin/issues/211)
- [rules_kotlin issue #454 — expose Starlark APIs for custom rules](https://github.com/bazelbuild/rules_kotlin/issues/454)
- [Konsist docs](https://docs.konsist.lemonappdev.com/)
- [Konsist for KMP architecture guidelines (Medium)](https://medium.com/@lahirujay/konsist-protect-kotlin-multiplatform-projects-from-architecture-guidelines-violations-d88db0614cbd)
- [ArchUnit vs Konsist comparison (Medium)](https://medium.com/the-house-of-code/archunit-vs-konsist-in-android-kotlin-oriented-codebase-b72c6c698b0b)
- [detekt extension docs](https://detekt.dev/docs/introduction/extensions/)
- [Kotlin KEEP-0045 — explicit API mode](https://github.com/Kotlin/KEEP/blob/main/proposals/explicit-api-mode.md)
- [JetBrains YouTrack BAZEL-429 — internal visibility across Bazel module boundaries](https://youtrack.jetbrains.com/issue/BAZEL-429)
- [Bazel `visibility` concept docs](https://bazel.build/concepts/visibility)
- In-repo: `third_party/patches/rules_kotlin_kmp.patch`, `kmp/build.gradle.kts:19,1175-1202`,
  `kmp/config/detekt/detekt.yml`, `kmp/src/jvmMain/kotlin/BUILD.bazel`,
  `kmp/src/{jvmTest,commonTest,businessTest,androidUnitTest}/kotlin/BUILD.bazel` (`associates`
  precedent), `~/code/github.com/tstapler/kibitzer/src/{import_graph.rs,architecture_checks.rs,
  declaration_checks.rs,config.rs,mcp.rs}` (verified Kotlin support + config schema directly)
