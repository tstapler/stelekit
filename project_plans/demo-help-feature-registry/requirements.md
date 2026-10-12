# Requirements: demo-help-feature-registry

**Date**: 2026-10-11
**Type**: feature addition (repo tooling / quality gate) -- planning only, no implementation in this run

> Phase 1 note: the owner supplied the brief non-interactively (no AskUserQuestion channel in this
> run). Answers below are taken verbatim from the brief; anything not stated is listed under
> Open Questions rather than invented.

## Problem Statement
Three user-facing "explainers" of SteleKit's features -- the bundled **Demo graph**, the **in-app
help/docs**, and the **website** -- are maintained by hand and drift from the app as features are
added or changed. Nothing fails when a feature ships with no demo page, no help entry, or no website
mention, or when those go stale relative to the feature. Enforcement exists only for one slice
(Screens, see Verified Current State).

## Users / Consumers
- Contributors and coding agents adding/changing features (primary: the gate talks to them).
- End users reading demo graph pages, in-app help, and the site (secondary: the beneficiaries).
- CI (detekt, jvmTest/businessTest, GitHub Actions) as the enforcement runtime.

## Success Metrics
- A new Screen / command / settings category / git-sync state / shortcut with no declared coverage
  produces a warning (phase A) then a failing check (phase B) with a message naming the fix.
- Every registered feature's demo page exists, is non-empty and parses (loaded through the real
  demo-graph loader) -- verified by a test.
- Staleness signal: "feature source changed but its docs/demo/site pages did not" is reported per PR.
- Noise budget: fewer than 1 false-positive failure per ~20 feature-touching PRs (tracked in rollout).
- Existing gaps are baselined, and the baseline can only shrink (ratchet), mirroring the detekt
  baseline ratchet in `.github/workflows/ci.yml`.

## Constraints
- Owner direction: "some sort of feature registry with linting etc." -- single source of truth plus
  automated checks.
- Must run under both Gradle (`./gradlew jvmTest`) and Bazel (`bazel test //kmp:business_tests`) -- the
  repo is mid-migration (CLAUDE.md "Bazel Build Commands").
- commonMain code must stay KMP-portable (no JVM-only API in commonMain; detekt rule
  `JvmOnlyApiInCommonMainRule` exists).
- Owner standing rules: pursue full scope (no downscale questions); estimate in price-weighted agent
  cost classes + named wall-clock blockers, never hours/days/weeks.
- Do not implement, commit, or open PRs in this run.

## Scope
### In Scope
- A feature registry data model and its source-of-truth decision (derive vs declare).
- Lint/CI rules: demo-graph coverage, help-doc coverage, website coverage, staleness, link checks.
- Demo-graph verification test (load through real loader, assert pages parse).
- Rollout: warn-only, baseline/allowlist, ratchet, enforce.
- Contributor/agent notification: CLAUDE.md note, PR template, failure messages.
- ADRs for registry shape, derive-vs-declare, enforcement strictness.

### Out of Scope
- Writing the missing demo/help/website content itself (the gate reports gaps; filling them is separate).
- Redesigning the site, switching docs frameworks, or translating (i18n content) docs.
- Implementation of any of this (planning only).

## Open Questions
1. Is in-app help meant to be the Demo graph pages, the Kotlin `*Docs` classes, or both? (The `*Docs`
   classes are not wired to any UI today -- see Verified Current State.)
2. Which features are "marketed" (need a website mention) vs demo-only vs internal?
3. Should the website docs (`site/src/content/docs/user/*.mdx`) be generated from the demo graph pages
   (one source) or stay a hand-written parallel copy?
4. Staleness strictness: hash-of-source gate vs git-diff "touched but docs untouched" gate vs both.
5. Does the untracked ComponentGallery (jvmTest) get committed as-is? The registry plan references it
   as a sibling only.

---

## Appendix: Verified Current State (commit 5d00781169, branch feat/git-sync-branch-fix)

All claims below were obtained by reading files or running commands in this session.

**Demo graph**
- Source pages: `kmp/src/commonMain/resources/demo-graph/` -- 18 pages in `pages/`, 6 in `journals/`,
  plus `welcome-journal.md` and `assets/stelekit-diagram.png` (`git ls-files` count = 29 files).
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt` (720 lines) is GENERATED
  and tracked: header "GENERATED -- do not edit" (line 2 says "Source: .../demo-graph/");
  generator is the Gradle task `generateDemoFileSystem` (`kmp/build.gradle.kts:560`).
- Runtime wiring: `ui/GraphContentVaultSetup.kt:49-50` picks `DemoFileSystem()` when
  `activeGraphInfo?.isDemo == true`; wasm fallback at `kmp/src/wasmJsMain/.../browser/Main.kt:290-291`.
- Existing tests: `businessTest/.../db/DemoFileSystemSyncTest.kt` (every demo page appears in the
  generated file), `jvmTest/.../ui/GraphContentDemoFileSystemWiringTest.kt`,
  `jvmTest/.../ui/DemoBannerTest.kt`, `jvmTest/.../docs/DemoGraphCoverageTest.kt`.

**In-app help/docs**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/`: `FeatureDocRegistry.kt` (23 lines),
  `AppDocsInitializer.kt` (15), `DiataxisDoc.kt` (48), `HelpPage.kt` (15), `HelpExempt.kt` (16),
  `ScreenDocs.kt` (169, six `*Docs` classes).
- `Screen` (sealed class, `ui/AppState.kt:36-90`): 15 subclasses; 5 carry `@HelpPage`, 10 carry
  `@HelpExempt(reason=...)` (counts via grep on `^    @HelpPage` / `^    @HelpExempt`).
- Enforcement already present: detekt rule `buildSrc/src/main/kotlin/dev/stapler/detekt/MissingHelpPageAnnotationRule.kt`
  (registered at `SteleKitRuleSetProvider.kt:27`) -- new Screens need `@HelpPage` or `@HelpExempt`.
- `DemoGraphCoverageTest` (jvmTest) reflects over `Screen` subclasses and asserts each `@HelpPage`
  doc's HowTo/Reference title exists as a non-empty `demo-graph/pages/<title>.md`
  (`DemoGraphCoverageTest.kt:32-52`); its "coverage summary" test is informational only (line 83).
- GAP: `AppDocsInitializer`, `FeatureDocRegistry.get` and `HowToContent`/`ReferenceContent` have NO
  consumers outside `docs/` and their own test (grep over `kmp/` and `androidApp/`): the Kotlin docs
  classes are not rendered by any UI. `GlobalUnlinkedReferencesDocs` is annotated but not registered in
  `AppDocsInitializer.initialize()` (lines 9-13 register five classes, incl. `SearchDocs`, which no `Screen` uses).
- `HelpExempt.kt:11` says "See CLAUDE.md for the approved list"; the repo `CLAUDE.md` contains no such
  list (grep for "HelpExempt" in CLAUDE.md: no matches).
- Menu has `t("menu.help")` at `ui/components/TopBar.kt:362` (target not investigated here).

**Website**
- Astro Starlight site `site/` (`site/package.json`); 9 user docs `site/src/content/docs/user/*.mdx`
  + 4 developer docs; pages `index.astro`, `app.astro`, `demo.astro` (301 redirect to /app/).
- `.github/workflows/pages.yml`: builds wasm app + site on push to main, `check-changes` job diffs
  `kmp/ gradle build.gradle buildSrc/ settings.gradle e2e/`.
- Site docs are a hand-written parallel copy of demo pages (e.g. `site/.../flashcards.mdx` vs
  `demo-graph/pages/Flashcards.md`); no sync check exists.
- README sync: `scripts/generate-readme.sh` inlines `docs/install.md`; CI job `readme-sync` fails on drift
  (`.github/workflows/ci.yml:116-130`).

**Existing enforcement patterns**
- `MigrationRunnerSchemaSyncTest` (structural test, businessTest) -- cited in CLAUDE.md.
- Detekt ratchet: CI step "Detekt baseline ratchet" (`ci.yml:157-166`) fails if `kmp/config/detekt/baseline.xml`
  grows.
- `ShortcutTableConsistencyTest` (jvmTest) pins ShortcutTable single-source-of-truth.
- `JourneyDocsFrontmatterTest` (businessTest) validates `docs/journeys/*.md` YAML frontmatter, using a
  Bazel-vs-Gradle repo-file locator (`DocRepoLocator`).
- No Konsist/ArchUnit dependency found (grep of `kmp/build.gradle.kts`; `gradle/libs.versions.toml` does not exist, versions are inline).
- ALSO EXISTS (found in research, missed above): `jvmTest/.../docs/WebsiteDocsCoverageTest.kt` (every `@HelpPage` Screen has `site/src/content/docs/user/<slug>.mdx`) and Gradle task `checkDocCoverage` (`kmp/build.gradle.kts:1253`, part of `ciCheck`, not referenced by any workflow yml). Shipped by `project_plans/doc-coverage/`; its deferred items: `@HelpElement`, gitignoring the generated `DemoFileSystem.kt`.
- ComponentGallery (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/gallery/ComponentGallery.kt:36`,
  UNTRACKED in git) is a registry of visual `GalleryStory(id, content)` for screenshot tests --
  component-level, not feature-level; a sibling, not the same thing.

**Where "features" are defined today** (candidate sources of truth)
- `Screen` sealed class (15), `SettingsCategory` enum (13 entries, `SettingsDialog.kt:453-467`),
  `EditorCommand`s in `editor/commands/EssentialCommands.kt` (24 `EditorCommand(` sites),
  `SyncState` sealed class (`git/model/SyncState.kt`), `ShortcutTable` (`ui/components/ShortcutTable.kt:24`),
  I18n (`ui/i18n/I18n.kt`), sidebar nav items (`ui/components/Sidebar.kt:302-320`).
