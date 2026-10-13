# Pitfalls research: demo-help-feature-registry

Labels: VERIFIED = file read or command run this session. INFERRED = reasoned from verified facts. EXTERNAL-UNFETCHED = general industry knowledge with a URL, not re-opened this run (web fetch skipped; treat as hypotheses to confirm).

## 1. Why doc/coverage gates get ignored or gamed

| Pitfall | Mechanism | Mitigation to adopt | Source |
|---|---|---|---|
| Alert fatigue | Warnings on every PR train people to scroll past; a gate with >~5% false positives gets bypassed or disabled | Per-rule severity; warn-only soak with measured FP rate before enforcing (matches requirements noise budget of <1 FP per ~20 PRs) | Vale adoption guidance: start with a minimal ruleset at `suggestion`/`warning`, promote to `error` later. https://vale.sh/docs/ (EXTERNAL-UNFETCHED) |
| Rubber-stamp exemptions | Escape hatch with a free-text reason becomes the default path. Already visible here: 10 of 15 `Screen` subclasses carry `@HelpExempt` (requirements.md appendix) and `HelpExempt.kt:11` points at a CLAUDE.md "approved list" that does not exist (requirements.md appendix; grep confirmed no match) | Exemption needs reason from a closed enum + owner + expiry date; test fails when expiry passes; exemption count is itself ratcheted | INFERRED from the repo; Detekt/lint baseline practice https://detekt.dev/docs/introduction/baseline (EXTERNAL-UNFETCHED) |
| Touch-a-doc-to-pass | A "docs changed in this PR" check is satisfied by whitespace edits, so it measures activity, not accuracy | Prefer existence + parse + content checks (non-empty, headings, links resolve) over diff-touch; if diff-touch is kept, make it a warning only, ignore whitespace-only diffs, and require the touched file to be the one the registry maps to the feature | Danger-style "changelog/docs touched" rules are widely reported as easily satisfied: https://danger.systems/ (EXTERNAL-UNFETCHED) |
| Baseline that never shrinks, or can be reset by regeneration | Regenerating the baseline file silently absorbs new violations | Ratchet in CI by comparing against the merge-base (the repo already does this for detekt, `ci.yml:157-166`, VERIFIED); fail if the baseline gains an entry; baseline entries keyed by stable feature id, not line numbers | VERIFIED pattern exists; reuse it |
| Whole-codebase enforcement on day one | Thousands of legacy failures, everyone adds exemptions | New-code-only enforcement: gate applies to features added after a registry "cutoff" commit, legacy gaps tracked in the shrink-only baseline. This is the SonarQube "Clean as You Code" new-code model | https://docs.sonarsource.com/sonarqube-server/latest/user-guide/about-new-code/ (EXTERNAL-UNFETCHED) |
| Flaky external link checks | Network/rate-limit/transient 5xx failures turn red for reasons unrelated to the PR; people re-run until green or delete the check | Check internal links and anchors only in the blocking gate; run external-URL checks on a scheduled workflow (not PR-blocking), with retries and an allowlist; never gate PRs on third-party availability | lychee/markdown-link-check CI guidance https://lychee.cli.rs/ (EXTERNAL-UNFETCHED) |
| Message does not name the fix | Failures that say "coverage missing" with no path get exempted instead of fixed | Failure text must contain the exact file path to create and the registry line to add (requirements success metric 1) | INFERRED |

## 2. Repo hazards (each verified)

**(a) DemoFileSystem.kt generated but committed; no CI regeneration check.**
- VERIFIED: file is tracked (`git ls-files` lists `kmp/src/commonMain/.../platform/DemoFileSystem.kt`; `git check-ignore` returns nothing, so it is not ignored). Last touched by commit `b3c2fa12c1`.
- VERIFIED: the generator task is `generateDemoFileSystem` (`kmp/build.gradle.kts:560`) and is wired before `compileKotlinJvm`/`compileKotlinWasmJs` and `jvmTest` (lines 714-731).
- VERIFIED: `.github/workflows/ci.yml` has a `sqldelight-sync` job that runs the generator and `git diff --exit-code` (about lines 83-114). No grep hit for `DemoFileSystem` or `generateDemoFileSystem` anywhere under `.github/`. So CI regenerates the file in its checkout during the build but never fails on drift; the committed copy can go stale without any red check.
- VERIFIED: the doc-coverage plan (Story 2.4, `project_plans/doc-coverage/implementation/plan.md:400`) said to add the file to `.gitignore`, but it shipped tracked. The plan and reality diverge.
- Why it matters here: adding registry-driven demo pages means every demo page edit must regenerate this file; forgetting causes an unresolved or stale-demo mismatch on Bazel (which reads committed sources). Mitigation: add a `demo-fs-sync` CI job cloning `sqldelight-sync`, or decide to gitignore it and generate in Bazel. `DemoFileSystemSyncTest` only checks the file after the task has already regenerated it, so it cannot detect a stale committed copy.

**(b) Demo graph bloat and wasm size.**
- VERIFIED: `du -sb kmp/src/commonMain/resources/demo-graph` = 35,666 bytes; `DemoFileSystem.kt` = 40,942 bytes. Negligible.
- VERIFIED: the only size limit is `pages.yml:107` (fails if any wasm output file > 90 MB) and a 10 MB per-file warning for site output (`pages.yml:237`). The demo graph is about 0.04% of that limit. Real risk is only if screenshots or large PNGs are embedded: `assets/stelekit-diagram.png` is a resource and `DemoFileSystem.kt` inlines text. Mitigation: a soft budget test (for example the demo-graph total under 1 MB) so growth is noticed.

**(c) Bazel vs Gradle parity for tests reading repo-root files.**
- VERIFIED: `DocRepoLocator` (`kmp/src/businessTest/kotlin/dev/stapler/stelekit/docs/JourneyDocsFrontmatterTest.kt`) walks up from the `demo-graph/pages` classpath resource to find a dir with `kmp/`, `docs/`, `project_plans/`. Under Bazel this fails (jar URL not hierarchical), so each repo-root file set is bundled as classpath resources in `kmp/src/businessTest/kotlin/BUILD.bazel` (e.g. `rich_editing_backlog_docs`, `journey_docs_as_resources`).
- VERIFIED: `DemoGraphCoverageTest` and `WebsiteDocsCoverageTest` live in `kmp/src/jvmTest/.../docs/`, and a grep of the Bazel BUILD files found no reference to them or to `site/`. `WebsiteDocsCoverageTest` walks up to find `kmp/` and `site/` as plain files (lines 21-38), which would not work under Bazel runfiles. So the existing coverage gate has no Bazel parity today.
- Consequence for this project: any new check that reads `site/**` or `docs/**` must either live in jvmTest (Gradle-only, acceptable only if Gradle CI keeps running it) or add a Bazel resource-bundling target for `site/src/content/docs/user/*.mdx`. Registry-vs-code checks that need only `commonMain` classes and the demo-graph resource are portable to businessTest. Also `AllBusinessTests.kt` and `AllBusinessTestsCompletenessTest.kt` require new businessTest classes to be registered, or Bazel silently skips them (memory note: businessTest suite gap).

**(d) pages.yml path filters.**
- VERIFIED: `pages.yml` triggers on every push to main except `CHANGELOG.md`, `.release-please-manifest.json`, `Formula/**`, `Casks/**` (`paths-ignore`). `check-changes` greps the diff for `^(kmp/|gradle|build\.gradle|buildSrc/|settings\.gradle|e2e/)`; the wasm build is skipped when none match.
- Hazard: `site/` and `docs/` are not in the wasm rebuild filter (correct, they feed the separate site build), but a registry file placed outside `kmp/` (e.g. `docs/feature-registry.yml` or `project_plans/`) would not trigger a wasm rebuild even though the demo embeds from it. Keep the demo source of truth under `kmp/`. Also note `version.txt` is not in `paths-ignore`, so release PR merges do run pages.yml.

**(e) Gate must not block release-please or Dependabot PRs.**
- VERIFIED: `.github/dependabot.yml` does not exist (no Dependabot config; `ls` failed). Dependabot is therefore not currently a hazard, but if added later its PRs would run the gate.
- VERIFIED (CLAUDE.md "Release Process"): the release-please PR touches only `version.txt`, `CHANGELOG.md`, `.release-please-manifest.json`. A registry gate keyed to "feature source changed" will not fire on those paths; a gate keyed to "docs must change" must not use PR-wide rules. `ci.yml` runs on `pull_request` to main/master (lines 5-7), so a new job would apply to release PRs unless it has a paths filter or `if:`.
- Mitigation: stateless gates (registry vs code) are safe; diff-based staleness checks must skip when no `kmp/src/commonMain` feature file changed, and skip actors `release-please[bot]`/`dependabot[bot]`.

**(f) Merge conflicts on a shared registry file with many agent worktrees.**
- INFERRED: a single central file (one YAML/Kotlin list) edited by every feature PR is a guaranteed conflict hotspot when many agents work in parallel worktrees (CLAUDE.md describes `.claude/worktrees/`). Already the case for `AllBusinessTests.kt`, `AppDocsInitializer.initialize()` (a central list, requirements.md appendix) and `DemoFileSystem.kt` (one generated blob; any two PRs adding demo pages conflict on it).
- Mitigations: derive the registry from code (annotations on the declaring class, as `@HelpPage` does) so entries sit next to the feature; or one file per feature (`registry/<feature-id>.yml`) auto-collected by a glob; keep generated artifacts out of conflict-prone shared files (gitignore + generate, or sorted deterministic output so rebase conflicts are mechanical; `git merge -s ours` style `.gitattributes` merge driver is an option).

**(g) project_plans/doc-coverage: planned vs shipped.**
- VERIFIED shipped (present in tree): `@HelpPage`/`@HelpExempt` annotations; `MissingHelpPageAnnotationRule` (detekt); `DemoGraphCoverageTest`, `WebsiteDocsCoverageTest` (jvmTest); `DemoFileSystemSyncTest` (businessTest); `generateDemoFileSystem` task; `checkDocCoverage` Gradle task (`kmp/build.gradle.kts:1257`, depends on generator + jvmTest); `welcome-journal.md`; `site/src/content/docs/user/*.mdx`.
- VERIFIED deviations: Story 2.4 (gitignore `DemoFileSystem.kt`) not done, file is tracked. `checkDocCoverage` is referenced in `ciCheck` (line 1284) but grep found no use in `.github/workflows/*.yml`; whether CI jvmTest covers it indirectly is INFERRED (jvmTest already runs these tests). Story 5.3 "verify full CI pipeline" has no artifact I could confirm.
- VERIFIED unfinished / deferred by that project: R2 `@HelpElement` for sub-screen elements, explicitly deferred to Phase 2 (`validation.md:341`, "7/8 requirements, R2 intentionally deferred"). Adversarial review issue 4 flagged the silent drop of R2. Scope covered only `Screen` subclasses; commands, settings, sync states, shortcuts are exactly what this project adds.
- VERIFIED loose ends this project inherits: `AppDocsInitializer` does not register `GlobalUnlinkedReferencesDocs` and registers `SearchDocs` which no Screen uses; the `*Docs` classes have no UI consumer (requirements.md appendix); `HelpExempt.kt:11` references a non-existent CLAUDE.md list.
- Contradiction risk: doc-coverage's adversarial review said requirements' `site/src/pages/docs/` path was wrong and the real path is `site/src/content/docs/user/`. This project must use the content-collection path and extend, not replace, `@HelpPage`/`MissingHelpPageAnnotationRule` (ADR should state the relationship: registry subsumes or composes with the annotations).

**(h) i18n vs help text.**
- VERIFIED: `ui/i18n/I18n.kt` exposes `t(key)` (lines 178-186); menu uses `t("menu.help")` (`TopBar.kt:362`). Demo pages and `*Docs` content are plain English strings/markdown, not keyed. Requirements put doc translation out of scope.
- Hazard: registry entries holding display titles that double as demo-page filenames (`DemoGraphCoverageTest` maps doc title to `demo-graph/pages/<title>.md`) couple a user-visible title to a file name; later localisation of titles would break lookups. Mitigation: registry uses stable ids and a separate English `title`/`demoPage` field; never derive the id from `t()` output; do not make i18n keys part of the gate (avoid scope creep).

## 3. Pre-mortem seed list

| # | Failure mode | L | Impact | Mitigation |
|---|---|---|---|---|
| 1 | Gate ships enforcing; legacy gaps cause immediate red on every PR; team adds blanket exemptions | H | Gate discredited | Warn-only soak, shrink-only baseline, new-code-only enforcement |
| 2 | Exemption list becomes the default path (already 10/15 Screens exempt) | H | Registry coverage meaningless | Reason enum + owner + expiry; ratchet exemption count |
| 3 | Committed `DemoFileSystem.kt` drifts (no CI drift check) | M | Stale or missing demo on Bazel/web; confusing failures | Add drift-check job like `sqldelight-sync`, or gitignore + generate everywhere |
| 4 | New tests only run in Gradle jvmTest, silently absent from Bazel (no `site/` bundling) | H | Gate passes locally, not on Bazel CI, or the reverse | Put portable checks in businessTest; add resource bundles for `site/`; register in `AllBusinessTests.kt` |
| 5 | Central registry file becomes a merge-conflict hotspot across agent worktrees | H | Agents stall or resolve conflicts incorrectly | Derive from code annotations or per-feature files; deterministic sorted generation |
| 6 | Touch-a-doc staleness gate gamed with whitespace edits | M | False assurance | Content-based checks first; diff gate warn-only and whitespace-insensitive |
| 7 | Staleness gate false-positives on refactors/renames of feature source | M | Noise, ignored warnings | Scope to registry-mapped files only; allow `docs-not-needed:` trailer with reason; measure FP rate during soak |
| 8 | Flaky external link check blocks PRs | M | Re-run culture, check deleted | Internal links in PR gate; external links scheduled, non-blocking |
| 9 | Gate fires on release-please/Dependabot PRs | L | Release pipeline blocked | Path/actor filters; stateless checks only on those PRs |
| 10 | Derived-from-code registry misses features (reflection finds only annotated classes; `SOURCE` retention hides `@HelpExempt` at runtime, noted in `doc-coverage/implementation/plan.md` Known Issues) | M | Silent gaps (the unannotated feature is never seen) | Enumerate sources the code owns (sealed subclasses via reflection/`sealedSubclasses`, enum entries, `ShortcutTable`) with a completeness test per source, as `MigrationRunnerSchemaSyncTest` does |
| 11 | Registry duplicates existing `@HelpPage`/`@HelpExempt`/detekt rule, producing two truths | M | Contradictory gates, double maintenance | ADR: registry composes with or replaces annotations; migrate, then delete the old rule |
| 12 | Demo graph/site copy diverges because parallel copy is hand-written (open question 3) | H | Drift continues despite gate | Decide single source (generate site pages from demo pages) or add a content-hash link between pair |
| 13 | Demo-graph verification test loads pages with a different parser path than the app, passing while real load fails | M | False green | Load through the real `GraphLoader` + `DemoFileSystem`, as requirements specify |
| 14 | Bloat: screenshots/assets added to demo graph inflate wasm and `DemoFileSystem.kt` | L | Slow web load (limit 90 MB is far off) | Soft size-budget test (e.g. 1 MB) |
| 15 | Registry ids tied to localizable titles; i18n later breaks demo-page lookups | L | Broken help links per locale | Stable ids; separate title field; keep i18n out of gate |
| 16 | Failure messages vague, so agents "fix" by exempting | M | Gaps persist | Message includes exact path to create, registry line to add, and the exemption syntax with its cost |
| 17 | Ratchet baseline regenerated wholesale in a PR, absorbing new gaps | M | Ratchet defeated | CI compares baseline against merge-base (reuse `ci.yml:157-166` pattern); key by feature id |
| 18 | Untracked ComponentGallery (`kmp/src/jvmTest/.../ui/gallery/`) confused with the feature registry or committed half-done | L | Scope blur | Keep it a documented sibling; separate decision (open question 5) |
