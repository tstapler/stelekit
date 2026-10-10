# Adversarial Review — Documentation Coverage Enforcement Plan

**Verdict: CONCERNS**

**Issue count: 7**

**3 most critical concerns:**
1. The plan's annotation count is wrong — it claims 3 annotated Screens but `AppState.kt` has 4, and crucially `Screen.kt` does not exist as a standalone file (Screen is defined in `AppState.kt`), which means the detekt rule's parent-name check must target `AppState.kt`.
2. The requirements say docs live at `site/src/pages/docs/<slug>.astro` — that directory does not exist and never existed. The actual Starlight content collection is `site/src/content/docs/user/`. The plan silently switches to the correct path, but the requirements.md success criteria still reference the wrong path, creating a permanent discrepancy between R5/R6/success-criteria and the implementation.
3. The `DemoFileSystemSyncTest` walk-up that reads the generated file as a plain `File` path is fragile: if `generateDemoFileSystem` has not run (e.g. fresh clone, or developer ran `./gradlew clean`), the test emits a misleading `fail()` rather than a Gradle task dependency failure. The `jvmTest.dependsOn(generateDemoFileSystem)` wiring proposed in Story 2.5 partially addresses this but is in a different story from the test — if Story 2.5 is merged without that `dependsOn`, every CI `jvmTest` run fails until it is added.

---

## Issue 1 — CORRECTNESS: Plan says "3 Screens already annotated"; actual count is 4

**Finding:** plan.md §Overview says "Three Screen entries already carry `@HelpPage` (Journals, Flashcards, AllPages)". Reading `AppState.kt` directly shows four annotated entries: `Journals`, `Flashcards`, `AllPages`, and `PageView`.

**Evidence:** `AppState.kt` lines 28–47:
```
@HelpPage(docs = JournalsDocs::class)   data object Journals
@HelpPage(docs = FlashcardsDocs::class) data object Flashcards
@HelpPage(docs = AllPagesDocs::class)   data object AllPages
@HelpPage(docs = PageViewDocs::class)   data class PageView
```

**Impact:** Story 3.2 lists `Screen.PageView` as "Already annotated" under a buried note — but the overview mismatch will confuse any developer reading the plan. More importantly, `DemoGraphCoverageTest` is not vacuous: it already runs four assertions. The claim that "the test passes vacuously because findAnnotatedClasses() only finds what is already annotated" is true for the gate direction (no unannotated screens are caught), but false for the characterisation that nothing is being tested — four doc classes and four demo-graph pages are already under test.

**Patch to plan.md:** Change §Overview bullet from "Three Screen entries" to "Four Screen entries (Journals, Flashcards, AllPages, PageView)".

---

## Issue 2 — CORRECTNESS: `Screen.kt` does not exist as a standalone file

**Finding:** The plan references `Screen.kt` in `ui/` throughout (e.g. R1 scope, Story 1.2 detection algorithm, R6 inventory). There is no `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/Screen.kt`. The `sealed class Screen` is defined inside `AppState.kt`.

**Evidence:** `find . -name "Screen.kt"` returned no results. `AppState.kt` contains `sealed class Screen` at line 27.

**Impact:** Story 1.2's detection algorithm says `containingClassOrObject?.name == "Screen"` and verifies "the parent is a sealed class (`parent is KtClass && parent.isSealed()`) to guard against accidental name collisions." This logic is correct for PSI regardless of which file the class lives in — the detekt rule itself will work fine. But every reference in the plan to "Screen.kt in ui/" is wrong for navigation purposes, which is misleading during implementation.

**Secondary implication:** `VaultState` (also in `AppState.kt`) is a separate sealed interface with `data object Locked`, `data object Unlocking`, etc. The parent-name check guards correctly against these since their parent is `VaultState`, not `Screen` — no false positives. The sealed-class guard is still correct.

**Patch to plan.md:** Replace all references to `Screen.kt` with `AppState.kt`. Add a note to Story 1.2 that `sealed class Screen` is declared in `AppState.kt`, not a standalone file.

---

## Issue 3 — CORRECTNESS: Requirements.md and plan.md path mismatch for website docs

**Finding:** `requirements.md` §R5, §R7, and the success criteria all reference `site/src/pages/docs/<slug>.astro`. This path does not exist — `site/src/pages/docs/` is empty/absent. The actual Starlight content directory is `site/src/content/docs/user/`, containing `backlinks.mdx`, `getting-started.mdx`, `journals.mdx`, `outliner.mdx`, `search.mdx`.

`plan.md` correctly uses `site/src/content/docs/user/` throughout (Story 4.1, 4.2, 4.3), but `requirements.md` is never corrected. The `WebsiteDocsCoverageTest` in Story 4.1 hardcodes `site/src/content/docs/user` — correct for the real filesystem, but it means the test would pass while requirements.md's success criterion #3 ("Adding `@HelpPage` without a `site/src/pages/docs/<slug>.astro` file fails `./gradlew jvmTest`") is structurally impossible to satisfy as written.

**Impact:** If someone uses `requirements.md` as the acceptance gate for the feature, they will look for `.astro` files in the wrong directory. Plan passes; requirements fail. This is an auditing hazard.

**Patch to plan.md:** Add an explicit note in Story 4.1 or Story 4.2: "Note: requirements.md §R5 and the success criteria reference `site/src/pages/docs/` — this path does not exist. The actual Starlight content collection is `site/src/content/docs/user/`. The test and implementation use the correct path; requirements.md is out of date and should be updated."

---

## Issue 4 — SCOPE CREEP: Plan silently drops R2 (@HelpElement) with no recorded decision

**Finding:** `requirements.md` §R2 defines a `@HelpElement` annotation for sub-screen interactive elements (toolbar buttons, settings toggles, sidebar items). R5 includes a second assertion: "For every `@HelpElement`-annotated element: Assert the parent screen's /docs page exists." The plan contains zero mention of `@HelpElement`, `HelpElement`, or R2 in any story.

**Impact:** This is not necessarily wrong — R2 is marked as "no compile-time enforcement in the first phase" and is explicitly opt-in. But the plan never states "R2 is deferred" or "R2 is out of scope." A reviewer comparing plan.md against requirements.md will find a silently dropped requirement with no decision record. The WebsiteDocsCoverageTest in Story 4.1 also omits the R5 second assertion about `@HelpElement`.

**Patch to plan.md:** Add an explicit "Out of Scope / Deferred" section (or add to the existing "Architecture Decisions" section) stating: "R2 (@HelpElement) is deferred to a follow-on phase. The `WebsiteDocsCoverageTest` covers only `@HelpPage`-annotated Screens in this iteration. The R5 `@HelpElement` assertion is also deferred."

---

## Issue 5 — MISSING FAILURE MODE: Screen subclass outside `Screen`'s nested class scope

**Finding:** The plan asks about this failure mode in the review brief, and the plan's detection algorithm correctly handles it by checking `containingClassOrObject?.name == "Screen"`. However, there is an unaddressed risk: what if a developer defines a class that extends `Screen` at the top level (not nested inside it) — either by design or by mistake?

**Evidence:** `sealed class Screen` in Kotlin prevents extension outside the sealed hierarchy's module boundary in Kotlin 1.5+. Since the project uses KMP with all Screen subclasses in `commonMain`, a top-level `class FooScreen : Screen()` in the same module is syntactically valid. The rule as designed only fires when `containingClassOrObject?.name == "Screen"` — a top-level `FooScreen : Screen()` has no containing class, so `containingClassOrObject` is null and the rule is silent.

**Impact:** Someone could add `class FooScreen : Screen()` in a separate file, bypass the detekt rule, and ship without `@HelpPage`. The detekt rule only catches nested subclasses.

**Severity:** Medium — the Kotlin sealed class design strongly discourages this pattern but does not prevent it in the same module. The coverage test (`DemoGraphCoverageTest`) uses `Screen::class.java.declaredClasses` which also only finds nested classes, so the test would also miss it.

**Patch to plan.md:** Add to §Known Issues: "The `MissingHelpPageAnnotation` rule only checks direct nested members of `Screen`. A top-level class extending `Screen` in the same module escapes both the detekt rule and `DemoGraphCoverageTest`. Mitigation: the sealed class pattern makes this unusual; add a secondary rule or extend the detection to also check any class whose supertype list contains `Screen`."

---

## Issue 6 — DEPENDENCY RISK: `generateDemoFileSystem` wiring when `enableJs=false`

**Finding:** Story 2.3 proposes:
```kotlin
if (project.findProperty("enableJs") == "true") {
    afterEvaluate {
        tasks.named("compileKotlinWasmJs") { dependsOn(generateDemoFileSystem) }
    }
}
```

But the `generateDemoFileSystem` task itself (Story 2.2) is declared unconditionally — it always registers regardless of `enableJs`. The task reads from `demo-graph/` resources and writes to `src/wasmJsMain/kotlin/...`. When `enableJs=false`, the wasmJs source set may not be configured, but the task still writes to that path.

More importantly, Story 2.5 proposes:
```kotlin
tasks.named("jvmTest") {
    dependsOn(generateDemoFileSystem)
}
```

This wiring is unconditional — `jvmTest` depends on `generateDemoFileSystem` even when `enableJs=false`. This causes every `./gradlew jvmTest` (which CI runs as part of `ciCheck` and benchmarks) to run the generation task on machines where WASM is disabled, writing a file that is gitignored and belongs to a disabled source set. The file also survives `./gradlew clean` on the source tree location (`src/wasmJsMain/...` is not under `build/`), so `clean` does not restore the test to a failing state — but a fresh clone without running `generateDemoFileSystem` will cause `DemoFileSystemSyncTest` to fail regardless of `enableJs`.

**Patch to plan.md:** In Story 2.5, note that the `jvmTest.dependsOn(generateDemoFileSystem)` should be unconditional (the generation task is cheap and the test requires it), but the `generateDemoFileSystem` task must not fail when `src/wasmJsMain/` does not exist when `enableJs=false`. The task must create parent directories — the plan already states `out.parentFile.mkdirs()`, which handles this. The real risk is test failure on fresh clone: document that developers must run `./gradlew :kmp:generateDemoFileSystem` or `./gradlew jvmTest` (which triggers it via `dependsOn`) before the test passes on a clean checkout.

---

## Issue 7 — TEST RELIABILITY: `WebsiteDocsCoverageTest` uses `DiataxisDoc` interface cast with `else -> continue`

**Finding:** Story 4.1's test code:
```kotlin
val title = when (docs) {
    is ReferenceDoc -> docs.reference.title
    is HowToDoc     -> docs.howTo.title
    else            -> continue
}
```

`AllPagesDocs` implements only `ReferenceDoc` (not `HowToDoc`). `PageViewDocs` implements `MinimalFeatureDoc` which is `HowToDoc + ReferenceDoc`. The `when` expression evaluates branches in order — for `PageViewDocs`, `is ReferenceDoc` matches first and uses `reference.title` ("Block Editor Reference"), not `howTo.title` ("Block Editing").

The slug generated from "Block Editor Reference" is `block-editor-reference`, but Story 4.3 creates `site/src/content/docs/user/block-editing.mdx`. The test would therefore fail for `PageView` even after Story 4.3 creates the file, because the slug derivation picks the reference title rather than the howTo title.

**Evidence:**
- `PageViewDocs.howTo.title = "Block Editing"` → slug `block-editing`
- `PageViewDocs.reference.title = "Block Editor Reference"` → slug `block-editor-reference`
- Story 4.3 creates `block-editing.mdx` (the howTo title)
- Story 4.1 `when` checks `is ReferenceDoc` first → picks `block-editor-reference` → looks for `block-editor-reference.mdx` → file not found → test fails

**Patch to plan.md:** In Story 4.1, fix the `when` branch order to prefer `howTo.title` (or explicitly handle `MinimalFeatureDoc` first), OR change the plan to create both `block-editing.mdx` and `block-editor-reference.mdx`. The requirements.md §R5 says "Derive the slug from `DiataxisDoc.howTo.title`" — so `howTo` should take precedence. Fix:
```kotlin
val title = when (docs) {
    is HowToDoc     -> docs.howTo.title   // check HowToDoc first — preferred per R5
    is ReferenceDoc -> docs.reference.title
    else            -> continue
}
```

---

## Secondary Observations (non-blocking)

**Coverage summary test uses KNOWN_EXEMPT_SCREENS hardcoded set:** The plan acknowledges this in Known Issues. The set in Story 3.1 lists `GlobalUnlinkedReferences` as exempt, but Story 3.2 assigns it `@HelpPage` instead. The `KNOWN_EXEMPT_SCREENS` set in the informational test would incorrectly count `GlobalUnlinkedReferences` as "known exempt" even after it is annotated with `@HelpPage`, double-counting it. Since the summary test always passes, this is cosmetic but will show a wrong count.

**Story 3.2 annotation plan vs. requirements.md §R6:** R6 lists `Screen.GitSync` → "Git Sync.md" as a Screen to annotate. `AppState.kt` has no `Screen.GitSync` — the git feature uses composable functions in `GitSetupScreen.kt` without a dedicated `Screen` subclass. Plan.md is correct in omitting it; requirements.md is wrong. No action needed in the plan, but it is evidence that requirements.md was written from a predicted inventory, not a read of the actual file.

**`@HelpExempt` SOURCE retention creates a permanent KNOWN_EXEMPT_SCREENS maintenance burden:** The plan acknowledges this. An alternative worth recording: use `RUNTIME` retention for `@HelpExempt` and drop `KNOWN_EXEMPT_SCREENS`. The claim that "SOURCE is needed because enforcement is build-time only" is correct for the detekt rule, but SOURCE is not required — RUNTIME retention is compatible with PSI-based detekt rules and would allow the summary test to use reflection instead of a manual list. This is a design choice worth capturing in the ADR.

**Story 2.2 triple-quote escape logic:** The plan documents the escaping requirement and the Known Issues section calls it out. One additional edge case not mentioned: markdown files using backtick-triple code fences (`` ``` ``) followed by `kotlin` or `bash` on the same line contain no embedded `"""` — the risk is genuine only for files documenting Kotlin string literals. The existing demo-graph files do not appear to contain any `"""` sequences. A pre-generation scan is cheap insurance and should be unconditional, not conditional on a warning.

**`DemoGraphCoverageTest.findAnnotatedClasses()` scans `declaredClasses + listOf(screenClass)` — the second part is redundant.** `Screen` itself is not annotated with `@HelpPage` and never will be. The `listOf(screenClass)` addition means the test checks `Screen` for a `@HelpPage` annotation on every run — harmless but unnecessary noise.

---

## Patches Required Before Implementation

| Priority | Story | Patch |
|---|---|---|
| Critical | Overview | Change "Three Screen entries" to "Four Screen entries" |
| Critical | All | Replace `Screen.kt` references with `AppState.kt` |
| Critical | 4.1 | Fix `when` branch order: check `HowToDoc` before `ReferenceDoc` |
| High | Plan | Add explicit "R2 deferred" section with rationale |
| High | 1.2 | Add Known Issue: top-level Screen subclasses escape the rule |
| Medium | 2.5 | Document fresh-clone behavior; clarify unconditional `jvmTest.dependsOn` |
| Low | Requirements.md | Correct `site/src/pages/docs/` → `site/src/content/docs/user/` in R5, R7, success criteria |
