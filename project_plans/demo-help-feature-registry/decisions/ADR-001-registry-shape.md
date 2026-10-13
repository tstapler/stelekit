# ADR-001: Registry shape: typed Kotlin per-area manifest

**Status**: Proposed
**Date**: 2026-10-11
**Deciders**: Repo owner (Tyler Stapler)
**Related**: ADR-002 (derive vs declare), ADR-003 (enforcement), `../implementation/plan.md` Stories A1.1, A3.1, B3.1

## Context

Three explainers (demo graph, in-app help, website) drift from the app. Only `Screen` is enforced today, through `@HelpPage`/`@HelpExempt` annotations, the detekt rule `MissingHelpPageAnnotationRule`, and the jvmTest classes `DemoGraphCoverageTest` and `WebsiteDocsCoverageTest` (`../requirements.md` appendix; `../research/architecture.md` section 0).

Constraints that decide the shape:
- Annotations do not generalise: a `SettingsCategory` enum entry, an `EditorCommand(...)` instance and a `SyncState` member cannot all carry a class annotation (`../research/architecture.md` section 1).
- `@HelpExempt` has SOURCE retention (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/HelpExempt.kt:15`), so tests cannot see exemptions; `DemoGraphCoverageTest.kt:88-91` hand-mirrors the list and already omits `AssetDetail`.
- The PR-time gate is `bazel test //kmp:business_tests` (`.github/workflows/bazel-ci.yml:138`); Gradle CI runs no jvmTest and never calls `checkDocCoverage`. A registry must therefore be readable from a businessTest on both runners without reflection, KSP or Konsist (none on the classpath: `../research/stack.md` section 0).
- commonMain must stay KMP-portable (`JvmOnlyApiInCommonMainRule`).
- Many agent worktrees edit in parallel; a central list file is a known conflict hotspot (`AllBusinessTests.kt`, `AppDocsInitializer.initialize()`; `../research/pitfalls.md` 2f).

## Decision

Declare feature rows as **typed Kotlin data (jvmMain per Addendum, originally commonMain), split into one file per area**, under `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/featureregistry/`:

```kotlin
data class FeatureEntry(
    val id: FeatureId,            // stable ASCII, never t() output
    val title: String,
    val kind: FeatureKind,        // SCREEN | SETTING | COMMAND | SYNTAX | SYSTEM
    val audience: Audience,       // MARKETED | DEMO_ONLY | INTERNAL
    val covers: Set<String>,      // derived candidate ids this row accounts for (many-to-one)
    val demoPage: String?,        // "Flashcards" -> demo-graph/pages/Flashcards.md
    val sitePage: String?,        // "flashcards" -> site/src/content/docs/user/flashcards.mdx
    val sources: List<String>,    // repo-relative globs for the staleness gate
    val exempt: Exemption? = null // reason enum + detail + owner + ISO expiry
)
```

Files: `ScreenFeatures.kt`, `SettingsFeatures.kt`, `CommandFeatures.kt`, `SyncFeatures.kt`, concatenated by `FeatureRegistry.all`; one entry per line, trailing commas. `@HelpPage`/`@HelpExempt` fold into rows (Story A1.3) and are deleted in Story B3.1 together with `FeatureDocRegistry`, `AppDocsInitializer`, `ScreenDocs`, `DiataxisDoc` (no UI consumer outside `docs/` and tests: `../research/architecture.md` section 2). The detekt rule stays until the registry test covers Screens on both runners.

Scripts read the registry through a Gradle `JavaExec` export to JSON (`:kmp:exportFeatureRegistry`, spike S0.3), so there is one parser, the Kotlin compiler.

## Alternatives considered

| Alternative | Why not |
|---|---|
| YAML/JSON manifest (`registry/<id>.yml`) | Needs a parser in tests or Bazel-bundled files for the registry itself; no compile-time link to enums; stringly typed `covers`. Per-feature files solve conflicts but a typed per-area file solves them almost as well and keeps the type checker. Home Assistant's per-integration manifest is the closest precedent (`../research/features.md` Part B 2) and is the model for the *fields*, not the format |
| Annotations on declaring code (extend `@HelpPage`) | Cannot annotate enum entries/instances uniformly; `@HelpExempt` SOURCE retention makes exemptions invisible to tests; annotation scanning needs JVM reflection (kotlin-reflect not in Bazel `business_tests` deps). Zero merge conflicts is its real advantage, not enough to offset |
| KSP generating the registry | No KSP plugin or Bazel path in this repo (`../research/stack.md` KSP section); highest cost for no extra capability here |
| Konsist | New dependency, source staging under Bazel, Kotlin 2.4.10 compatibility unverified; adds nothing that an exhaustive `when` plus a test does not (`../research/stack.md` Konsist section) |
| One central `FeatureRegistry.kt` list | Guaranteed worktree conflicts; per-area split costs nothing extra |

## Consequences

Positive:
- Compile-time checked, KMP-portable, readable by any businessTest and by the export task; exemptions become visible to tests and carry a reason enum, owner and expiry.
- Single bookkeeping system after B3.1; removes the `GlobalUnlinkedReferencesDocs` unregistered / `SearchDocs` orphan class of bug.
- Per-area files limit (not eliminate) conflicts; two worktrees adding to the same area still conflict on adjacent lines.

Negative:
- A new feature requires editing a registry file in addition to the feature code; the failure message must name that file (Story A1.1 T-A1.1.4, A5.1.3).
- `sources` globs are hand-maintained and can rot; mitigated by a glob-matches-something check and the staleness job's warn-first rollout.
- Export-to-JSON adds a Gradle invocation to the staleness CI job (fallback if S0.3 shows it too slow: a `grep` extractor over `*Features.kt`).


## Addendum (adversarial review A-3, A-9)

- Location: new jvmMain package `dev.stapler.stelekit.featureregistry` (outside Bazel Tier 1 `docs`, not shipped to non-JVM targets); data moves to commonMain only if the optional Help-menu story (D4) is approved.
- In-app help definition: in-app help is the demo-graph page named by `demoPage`; the Kotlin `*Docs` strings are retired. "Help-doc coverage" = a registered, existing, non-empty, parsing demo page.
