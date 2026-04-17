# ADR-003: Checked-In Demo Graph Files with Test-Time Validation (vs. KSP Code Generation)

**Status**: Accepted  
**Date**: 2026-04-13  
**Deciders**: Tyler Stapler  
**Feature**: Robust Demo Graph

---

## Context

The requirements specify that every `@HelpPage`-annotated feature must have corresponding `.md` files in the demo graph, and that omitting them must fail CI. Two approaches were evaluated for enforcing this:

**Option A — KSP code generation**: A Kotlin Symbol Processing (KSP) processor reads `@HelpPage` annotations at compile time, discovers the docs class, and either generates the `.md` files or fails the build if they are missing.

**Option B — Checked-in `.md` files validated at test time**: Markdown files are human-authored and committed alongside the code. A `DemoGraphCoverageTest` (jvmTest) uses JVM reflection to scan for `@HelpPage` annotations and asserts the corresponding `.md` files exist and are non-empty in the classpath resources.

The following dimensions were examined for each option:

| Dimension | KSP Generation | Checked-In + Test Validation |
|-----------|---------------|------------------------------|
| Build infrastructure change | High — new Gradle plugin, per-target config | None |
| Cross-platform support | Problematic — KSP/Native limited for iOS | Full — JVM test is sufficient gate |
| Incremental compilation safety | Risk — cache invalidation edge cases | N/A |
| Human readability of docs | Low — generated output only | High — authored Markdown, reviewable diffs |
| Reviewer experience | Harder — must read generated diffs | Easier — diff is the actual Markdown |
| Implementation effort | High — custom SymbolProcessor + per-target wiring | Low — single test class |
| Flexibility of docs content | Low — template-bound | Full — freeform Markdown |

KSP was specifically evaluated against the current project configuration:

- No KSP plugin in `build.gradle.kts` (confirmed by research).
- Kotlin 2.0.21 requires KSP `2.0.21-1.0.21` — exact version pinning required.
- KMP targets JVM, Android, iOS, and JS. KSP in KMP requires explicit per-target declarations (`kspJvm`, `kspAndroid`, `kspIosX64`, `kspIosArm64`, `kspIosSimulatorArm64`, `kspJs`).
- Kotlin/Native (iOS) KSP support is experimental and incomplete as of Kotlin 2.0.x.
- All three research dimensions (stack, architecture, pitfalls) independently recommended skipping KSP.

---

## Decision

**Adopt Option B** — human-authored `.md` files checked in to `kmp/src/commonMain/resources/demo-graph/`, with `DemoGraphCoverageTest` enforcing completeness at JVM test time.

### File Location

```
kmp/src/commonMain/resources/demo-graph/
├── pages/
│   ├── Start Here.md           ← index / welcome page
│   ├── Block Editing.md        ← HowToDoc
│   ├── Block Editor Reference.md  ← ReferenceDoc
│   ├── Page Linking.md
│   ├── Properties.md
│   ├── Daily Notes.md
│   └── ...
├── journals/
│   ├── 2026_04_08.md
│   ├── 2026_04_09.md
│   ├── 2026_04_10.md
│   ├── 2026_04_11.md
│   └── 2026_04_12.md
└── assets/
    └── stelekit-diagram.png
```

All files are committed to git and appear in pull request diffs. Reviewers evaluate content quality alongside the code change.

### Test-Time Validation

`DemoGraphCoverageTest` (jvmTest) enforces two properties:

1. **Completeness**: Every class annotated with `@HelpPage` has corresponding `.md` files present in `demo-graph/` for each Diataxis interface it implements.
2. **Non-emptiness**: No help page file is empty or whitespace-only. A stub file passes the parser but has zero blocks — this is detected and reported as a test failure.

```
FAIL: JournalsDocs implements HowToDoc but demo-graph/pages/How to use Daily Notes.md is missing.
FAIL: BlockEditorDocs: demo-graph/pages/Block Editor Reference.md exists but has zero blocks after parsing.
```

Reflection is confined to jvmTest; no reflection runs in production code on any target.

### What "Validation" Covers

`DemoGraphIntegrationTest` (separate from `DemoGraphCoverageTest`) loads the entire demo graph through `GraphLoader` and verifies:
- All pages parse without errors.
- No blocks are empty strings.
- All `[[wiki links]]` within the demo graph resolve to pages that exist in the graph.
- Page count matches the number of `.md` files in `pages/` and `journals/`.

`DemoGraphCoverageTest` then additionally verifies the annotation-to-file mapping.

### PR Workflow

Any PR that adds a new `@HelpPage`-annotated feature must include:
1. The docs class implementation (HowToDoc + ReferenceDoc at minimum).
2. The corresponding `.md` file(s) in `demo-graph/pages/`.
3. If a new screen: `@HelpPage` annotation on the `Screen` sealed class variant.

CI will catch missing files; PR review catches quality and content.

---

## Consequences

### Positive
- Zero build infrastructure changes — no Gradle plugin additions, no per-target KSP configuration.
- Documentation diffs are human-readable in pull requests.
- Authors have full creative freedom in Markdown content (not constrained to template fields).
- Works identically on all platforms and all Kotlin versions.
- `DemoGraphIntegrationTest` also serves as a parser regression test — if a parser change breaks demo graph loading, CI fails immediately.

### Negative
- A developer could theoretically write a minimal stub `.md` file (1 line) that passes the non-empty check but contains no useful documentation. Mitigated by PR review and the requirement that pages parse into non-empty block trees.
- Keeping docs content synchronized with the code it describes requires discipline — it is possible for Markdown content to become stale (describing old behavior) without a test failure. This is inherent to all documentation approaches.
- JVM reflection in `DemoGraphCoverageTest` does not provide enforcement on Android or iOS test runs. Acceptable: the JVM gate is sufficient for CI, and these targets share the same compiled annotation classes.

### Neutral
- The checked-in files are also the user-facing help graph. This dual role (test fixture + user docs) is intentional: it creates pressure to keep documentation high quality and up to date.
- If the project later adopts KSP for other purposes (e.g., database schema generation, API clients), the enforcement strategy can be revisited. The `@HelpPage` annotation design (ADR-001) is forward-compatible with KSP processing.

---

## Alternatives Rejected

**KSP Code Generation**: Rejected for the reasons enumerated in the context. The highest-weight reasons are: (1) per-target KSP configuration complexity in KMP, (2) Kotlin/Native KSP limitations on iOS, and (3) generated Markdown is harder to review and less flexible than human-authored content. All three research agents independently recommended against KSP.

**Gradle custom task (non-KSP)**: A Gradle task could scan compiled bytecode for `@HelpPage` annotations and fail the build if `.md` files are missing. This would run before tests, making failures faster. Rejected because it requires build script complexity, needs access to compiled classes (post-compilation lifecycle), and provides only marginal benefit over a JVM test (which also runs early in CI). Future upgrade path if faster feedback is needed.
