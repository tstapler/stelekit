# Architecture research: demo-help-feature-registry

Labels: VERIFIED = file opened/command run this session; INFERRED = reasoned, not run.

## 0. Findings that change the framing (VERIFIED)

- A gate already exists for the Screen slice, and it is mostly wired. `checkDocCoverage` (`kmp/build.gradle.kts:1253-1262`) is part of `ciCheck` (line ~1276). It runs `DemoGraphCoverageTest` + `WebsiteDocsCoverageTest` + `DemoFileSystemSyncTest` inside jvmTest. The requirements appendix does not mention `WebsiteDocsCoverageTest` (`kmp/src/jvmTest/.../docs/WebsiteDocsCoverageTest.kt:1-80`). It asserts every `@HelpPage` Screen has `site/src/content/docs/user/<slug>.mdx`. Website coverage for Screens therefore exists. The gap is every other feature kind.
- `@HelpExempt` has SOURCE retention (`docs/HelpExempt.kt:14`), so reflection cannot see it. `DemoGraphCoverageTest.kt:86-91` mirrors the exempt list by hand in `KNOWN_EXEMPT_SCREENS`. That list already omits some names, and its "coverage summary" test never fails (lines 61-84). The existing design is self-stale at exactly the point a registry should fix.
- `HelpExempt.kt:11` points to an "approved list in CLAUDE.md" that does not exist (requirements appendix; not re-grepped here).
- Bazel parity is probably broken today. `kmp/src/jvmTest/kotlin/BUILD.bazel:35-60` declares `jvm_tests` with no `data`/`resources` for `site/` or the demo-graph dir. `WebsiteDocsCoverageTest` walks up from the `demo-graph/pages` classpath resource looking for a dir with `kmp/` and `site/` (lines 11-26). Under the Bazel sandbox that likely fails or errors (INFERRED; not run). Moving checks to `businessTest` needs an explicit locator and Bazel resource bundling, like the `demo_filesystem_as_resource` pattern in `kmp/src/businessTest/kotlin/BUILD.bazel:11-16`.
- Help menu is a stub. `ui/components/TopBar.kt:355-366` renders `t("menu.help")` as a `TextButton(onClick = {}, enabled = false)` with 38% alpha. There is no help UI.
- Demo graph is surfaced only as a demo graph: `isDemoActive` drives a banner in `Sidebar.kt:240`, and `GraphContentVaultSetup.kt:49-50` picks `DemoFileSystem`. Nothing links Help to demo pages.
- `FeatureDocRegistry`/`AppDocsInitializer` consumers: only `docs/` itself, `FeatureDocRegistryTest`, and `AllJvmTests.kt` (grep of `kmp/src androidApp`). Dead in production. Its KDoc says "Register all features at app startup" and that is not true.
- Demo vs site vs Kotlin duplicates are three different shapes of the same content:
  - Demo `pages/Flashcards.md` is 23 lines of Logseq outliner syntax (`- ` bullets, tab indent, `- tags:: #how-to`).
  - Site `flashcards.mdx` is 68 lines of Starlight markdown (frontmatter, `##` headings). It has details the demo lacks, such as Hard/Good ratings and the due badge.
  - `ScreenDocs.kt` `FlashcardsDocs` (lines 25-46) is a third copy as Kotlin strings.
  - The copies already disagree. The demo and Kotlin copies describe "Again/Easy". The site lists Again/Hard/Good/Easy.
- `release-please-config.json` is `release-type: simple` with no commit-type rules. Conventional Commits are only a release-note convention, not an enforced gate (no commitlint seen in `.github/workflows`; grep found none). No `.github/pull_request_template.md` exists. The Makefile has only Android targets (`Makefile:10-13`).

## 1. Registry shape

Options and scoring (INFERRED unless cited):

| Criterion | (a) hand manifest | (b) derived from code | (c) hybrid |
|---|---|---|---|
| Self-staleness | High. Nothing forces a new feature into the file. | None for discovery. Coverage links live in annotations, so they are fine. | Low. Candidates are derived, so discovery cannot go stale. The manifest holds only links and exemptions, and the structural test forces entries. |
| KMP fit | Pure data. Parsed in commonMain or any test. | Annotation scanning needs reflection (JVM-only), as `DemoGraphCoverageTest.kt:23-29` shows. `SOURCE`-retention annotations are invisible to tests. KSP/Konsist not on the classpath (appendix). | Candidate enumeration uses plain Kotlin lists (`SettingsCategory.entries`, `EditorCommand` list, a sealed-subclass list). `sealedSubclasses` works on JVM only. The code must expose a KMP-safe list (see below). |
| Bazel | File must be a bundled resource. | Reflection works under JVM test, but needs the jvm_tests target to see the classes. | Same as (a) for the manifest, plus (b) for candidates. Put the check in `businessTest` where possible. |
| Merge conflicts | One file conflicts across the 19 worktrees under `.claude/worktrees/`. | Zero (annotations ride with the feature code). | Per-feature files avoid conflicts. |
| Review friction | Low. | Annotations scattered across files. | Manifest diff shows coverage in review. |

Key point for (b): `@HelpPage` is the right model for Screens because the annotation sits on the thing that changes. It does not generalize. A `SettingsCategory` enum entry, an `EditorCommand(` site or a `SyncState` subclass cannot all carry a class annotation. Enum entries can carry constructor args, and `EditorCommand` is an instance, not a class.

Recommendation: (c), with a Kotlin-typed registry (not YAML) for links and one declarative file per feature.

- Candidate sources: a `FeatureKind` enumerator in commonMain/`docs/` returns `List<FeatureCandidate(kind, id)>` from:
  - `Screen`: explicit `Screen.all` list or the existing reflective path on JVM.
  - `SettingsCategory.entries`.
  - `EditorCommand` ids from `EssentialCommands.kt`.
  - `SyncState` subclasses.
  - `ShortcutTable` groups.
  A KMP-safe enumeration via exhaustive `when` helpers is better than reflection. Adding a subclass breaks compilation of the `when`, which is a compile-time gate, stronger than a test.
- Coverage links in a Kotlin `FeatureRegistry` (commonMain, pure data, no reflection):

```kotlin
data class FeatureEntry(
    val id: FeatureId,              // "screen:Flashcards", "settings:Sync", "cmd:todo"
    val title: String,
    val demoPage: String?,          // "Flashcards" -> demo-graph/pages/Flashcards.md
    val sitePage: String?,          // "flashcards" -> site/.../user/flashcards.mdx
    val sources: List<String>,      // repo-relative globs for the staleness gate
    val audience: Audience,         // MARKETED | DEMO_ONLY | INTERNAL
    val exempt: Exemption? = null   // reason + owner; replaces @HelpExempt
)
```

- Why Kotlin and not YAML/JSON: type-checked, no parser dependency in commonMain, same pattern as `ShortcutTable`. Cost: the structural test needs the repo file locator only for demo/site file existence, not for the registry itself.
- Merge conflicts: split the registry into per-area `object`s, one file per area (`ScreenFeatures.kt`, `SettingsFeatures.kt`, ...) concatenated by a top-level `FeatureRegistry.all`. Conflicts only occur when two worktrees add to the same area. Appending a one-line `listOf` entry conflicts in git; keep entries one per line with trailing commas to limit this.
- Structural test (businessTest, pure commonMain data plus file existence): fail when a candidate has no entry and no exemption, or an entry names a candidate that no longer exists, or an exemption lacks a reason. This mirrors `MigrationRunnerSchemaSyncTest` (cited in CLAUDE.md) and the `MissingHelpPageAnnotationRule` detekt rule. Keep the detekt rule for Screens as a fast first signal, and have it also accept a registry entry.

## 2. FeatureDocRegistry / AppDocsInitializer / `*Docs`: recommendation

Recommendation: retire `FeatureDocRegistry` and `AppDocsInitializer` (delete). Evolve `@HelpPage(docs=...)` into the declared manifest. Do not wire the `*Docs` classes to the UI.

Evidence and reasoning:
- Nothing consumes the registry (section 0). Its `KClass`-keyed map exists to work "without reflection" but the only reflection users are JVM tests, so it solves nothing.
- Wiring it up means building a Help UI (`TopBar.kt:355-366` is a disabled stub) from Kotlin string literals. That duplicates the demo pages. The demo graph is already rendered by the real outliner with live links, which is a stronger help surface than a Kotlin-string renderer.
- A better Help target: the Help menu opens the demo graph page for the current screen, using `FeatureEntry.demoPage`. This is out of scope for the gate, but the registry shape supports it (INFERRED). It is a UI decision for the owner (open question 1).
- `HowToContent`/`ReferenceContent` text in `ScreenDocs.kt` duplicates demo pages and has already drifted (section 0). `DemoGraphCoverageTest` only uses the `title` field of each `*Docs` class to find the demo page. Replace with `FeatureEntry.demoPage` and delete `ScreenDocs.kt`, `DiataxisDoc.kt`, `FeatureDocRegistry.kt`, `AppDocsInitializer.kt`.
- Migration: `@HelpPage` on 5 Screens and `@HelpExempt` on 10 move into entries (the `@HelpExempt` reasons are already written in `AppState.kt:46-88`). Keep a thin `@HelpPage`/`@HelpExempt` shim during phase A if the detekt rule is kept, then remove.
- Also fix: `GlobalUnlinkedReferencesDocs` is annotated but unregistered, and `SearchDocs` is registered but used by no Screen. These are symptoms of the dual-bookkeeping the registry removes.

## 3. Content single source

Options:
1. Parallel copies plus drift check (status quo + a check).
2. Generate site `user/*.mdx` from demo pages.
3. Generate the demo pages from site mdx.

Evaluation:
- Conversion cost, demo to site (INFERRED): the demo is outliner bullets with tab nesting, `key:: value` properties, `[[links]]`, `#tags`, and no headings. Site mdx needs frontmatter (`title`, `description`), `##` sections, and rewritten links. A lossless converter is non-trivial: bullet nesting to headings/lists is lossy and heuristics are needed. Link targets differ (wiki titles vs site slugs). `Flashcards.md` vs `flashcards.mdx` show the content is different in depth, not just syntax: the site pages are richer, written for web readers and SEO.
- Reverse (site to demo) is easier mechanically (headings to top-level bullets) but demo pages are used as an in-app tutorial graph with interlinks and `tags:: #how-to`, which a converter cannot synthesize.
- A generator like `generateDemoFileSystem` (`kmp/build.gradle.kts:560-620`) is cheap when the transform is a byte-for-byte embed (it just inlines text into Kotlin). It is not analogous for a semantic transform.

Recommendation: option 1, lightly. Keep two hand-written copies, since they serve different readers, and make the drift check cheap and structural:
- Each registry entry names both pages (`demoPage`, `sitePage`) and the structural test checks existence and non-emptiness, which is the existing behavior generalized beyond Screens.
- Add a shared-fact drift check only where facts are enumerable and checkable: the keyboard-shortcut tables (already single-sourced in `ShortcutTable`, pinned by `ShortcutTableConsistencyTest`), and optionally generating a "Keyboard Shortcuts" site page from `ShortcutTable`. This is the one place a generator pays off.
- Do not generate site pages from demo pages. Revisit if open question 3 is answered "single source" by the owner; the cost class is a large agent wave (converter + golden tests over 9 pages) with an owner decision as the blocker.

## 4. Staleness detection

Compared (INFERRED except where cited):
- Content hash of declared source files stored in the manifest: deterministic and offline, but every refactor/format/rename of a source file flips the hash. Needs a bump commit per touch. Noisy, and the manifest becomes a merge-conflict hotspot (hash fields change in many PRs). Reject as a gate.
- git-diff gate, "a file in `sources` changed but neither `demoPage` nor `sitePage` nor the registry file changed": no stored state, so no extra conflicts. Same mechanism as the detekt ratchet (`ci.yml:157-166`: merge-base from `github.event.pull_request.base.sha`, needs `fetch-depth: 0`, only runs on `pull_request`). Noise sources: refactors, test-only changes, comment edits. Mitigate by restricting `sources` to the feature's UI/command files (not shared infra), and ignoring `*Test*.kt` and files whose diff is whitespace/comment-only (`git diff -w`).
- Conventional-commit `feat:` gate: aligned with release-please (Release Process in CLAUDE.md), and `feat:` is a strong signal for "user-visible change". But there is no commit lint today (`release-please-config.json` has no rules), so adopting it means adding commitlint. It is also coarse: a `feat:` commit with no mapped source file cannot say which feature. Use as an escalation signal, not the primary check.

Recommended combination (low noise):
1. Warn-only comment on PRs from the git-diff gate ("feature X sources changed; docs untouched") in phase A. It becomes a failing check in phase B only for PRs whose title or commits start with `feat` and touch a `sources` file.
2. Escape hatches, evaluated in the CI step: a PR label `docs-exempt`, a commit trailer `Docs-Exempt: <reason>` (trailer parsed with `git log --format=%B base..HEAD`), or a registry entry `exempt`. Require a non-empty reason; log it in the job summary so exemptions are reviewable and countable (tracks the noise budget from requirements).
3. Ratchet: a baseline file of currently-undocumented entries (`docs-coverage-baseline.txt`, one id per line); CI fails if it grows against the merge-base, exactly like the detekt baseline step.
4. Skip the hash approach. Avoid commit-lint as a hard dependency.

## 5. Where checks run

| Check | Location | Why |
|---|---|---|
| Candidate enumeration vs registry (every candidate has entry or exemption; no stale entries) | `businessTest` | Pure commonMain data; runs on Gradle jvmTest and Bazel `//kmp:business_tests` (suite gap RESOLVED per memory, 101/101 classes) with no display. |
| Demo/site file existence + non-empty | `businessTest` with a repo-file locator | Reuse `DocRepoLocator` from `JourneyDocsFrontmatterTest` (appendix says it handles Bazel-vs-Gradle). Bazel needs the demo pages and `site/src/content/docs/user/*.mdx` as `data`/resources. Move `WebsiteDocsCoverageTest` and `DemoGraphCoverageTest` here and fix their Bazel parity at the same time. |
| Demo pages parse through the real loader | `businessTest` (or `jvmTest` if the loader needs JVM) | Uses `DemoFileSystem` (already in commonMain) plus `GraphLoader`/the parser. |
| Detekt `MissingHelpPageAnnotationRule` | detekt (keep during migration) | Fast IDE feedback for Screens; note detekt does not scan wasmJs sources (memory). |
| Staleness (git-diff) | New job in `.github/workflows/ci.yml` (shell script in `scripts/`, like `generate-readme.sh`) | Needs the merge base and PR metadata; cannot run in a unit test. |
| Ratchet | Same CI job | Mirrors `ci.yml:157-166`. |
| Local entry point | `Makefile` target `docs-check` calling `./gradlew :kmp:checkDocCoverage` plus the script; keep `checkDocCoverage` in `ciCheck` | The Makefile currently has no check targets (`Makefile:10-13`); add one rather than a new system. |
| Bazel | `bazel test //kmp:business_tests` for structural checks; the staleness script stays CI-only (no Bazel equivalent needed) | Parity requirement from requirements. |

## 6. Recommended design (summary)

- Hybrid registry: derived candidates, plus per-area Kotlin manifest files with `FeatureEntry` (id, demoPage, sitePage, sources, audience, exempt).
- Delete the dead `FeatureDocRegistry`/`AppDocsInitializer`/`*Docs` strings. `@HelpPage`/`@HelpExempt` fold into the manifest. Keep the detekt rule during migration.
- Two hand-written content copies, linked by the manifest. Generator only for shortcut reference.
- Staleness via git-diff gate with `Docs-Exempt:` trailer, label, and an id-baseline ratchet. The hash approach is rejected.
- Checks run in `businessTest` (Bazel-compatible) plus one CI job. Add a `make docs-check` target.

## Open decisions for the owner (not resolved by research)

- Whether Help should open demo pages (UI work, out of scope for the gate).
- Which kinds are MARKETED (needs `audience` classification per feature; open question 2).
- Whether to adopt commit linting (changes contributor workflow).
