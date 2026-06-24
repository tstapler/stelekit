# Documentation Coverage Enforcement — Validation Plan

## 1. Requirement-to-Test Traceability Matrix

| Req | Description | Test(s) | Test Type | Confidence |
|-----|-------------|---------|-----------|-----------|
| R1 | `@HelpPage` required on every user-facing Screen | `MissingHelpPageAnnotationRuleTest` — 8 cases; `./gradlew detekt` scenario A (Story 5.3) | detekt rule test (buildSrc:test) + manual | HIGH — detekt PSI visitor catches annotation absence at compile time; test exercises all structural variants including `data object` vs `data class` and the sealed-parent guard |
| R2 | `@HelpElement` annotation for sub-screen elements | No automated test in this plan — R2 is explicitly opt-in with no enforcement in Phase 1 | none (manual convention only) | LOW — requirements state "no compile-time enforcement in the first phase"; the annotation can be declared but its absence is invisible to CI until `MissingHelpElementAnnotation` rule is enabled per-file |
| R3 | DemoFileSystem auto-generated from demo-graph/ | `DemoFileSystemSyncTest.every demo-graph page appears in generated DemoFileSystem`; Gradle idempotency check (run task twice, diff output) | businessTest / manual | HIGH for sync correctness; MEDIUM for generation correctness (test reads generated file as text, does not compile or execute it) |
| R4 | DemoGraphCoverageTest extended to DemoFileSystem sync | `DemoGraphCoverageTest.all HelpPage annotations reference existing non-empty pages` (already exists); `DemoFileSystemSyncTest` (new, Story 2.5) | jvmTest + businessTest | HIGH — existing test covers HowTo/Reference title→file mapping; new test covers demo-graph→generated-file mapping |
| R5 | Website /docs coverage CI gate | `WebsiteDocsCoverageTest.every HelpPage-annotated Screen has a website docs page` | jvmTest | HIGH — reflective scan of all `@HelpPage` annotations; asserts file exists and is non-empty; fails fast with actionable message naming the missing path |
| R6 | All existing Screens annotated with @HelpPage or @HelpExempt | `MissingHelpPageAnnotationRuleTest` (static rule verification); `./gradlew detekt` passing (integration verification) | detekt rule test + integration | HIGH — detekt enforces this structurally; the rule fires on every unannotated Screen member; coverage summary test (Story 3.1) provides CI-visible progress count |
| R7 | Website /docs pages authored per Screen | `WebsiteDocsCoverageTest` non-empty assertion (`.readText().isBlank()` check) | jvmTest | MEDIUM — tests file existence and non-blankness but does not validate content quality (correct frontmatter, minimum section count, etc.); content quality is a manual review concern |
| R8 | `./gradlew checkDocCoverage` subsumes all gates | `checkDocCoverage` Gradle task depends on `generateDemoFileSystem` + `jvmTest`; `ciCheck` depends on `checkDocCoverage` | integration (Gradle task graph) | HIGH — Gradle task dependency is structural; `ciCheck` fails if any of the three sub-gates fail |

**R2 gap note:** R2 has no automated test path in this plan. The requirement itself defers enforcement ("opt-in for now"). The traceability row correctly reflects LOW confidence because the feature can ship without `@HelpElement` and no CI gate will fire.

---

## 2. Full Test Suite Design

### 2.1 `MissingHelpPageAnnotationRuleTest`

**Source set:** `buildSrc/src/test/kotlin/dev/stapler/detekt/`
**Test class:** `MissingHelpPageAnnotationRuleTest`
**Pattern to follow:** `MissingDirectRepositoryWriteRuleTest` (already in buildSrc)

**Setup:** Each test case compiles a small inline Kotlin snippet using the detekt `RuleAssert` / `KtTestFactory` helpers (same pattern as existing buildSrc tests). No external fixtures needed.

---

**TC-RULE-01: fires on unannotated data object Screen subclass**

```
Input:
  sealed class Screen {
    data object Foo : Screen()
  }
Expected findings: 1
Assertion: assertThat(findings).hasSize(1)
            .first().hasMessage("Screen subclass 'Foo' must carry @HelpPage or @HelpExempt")
Red state: Before the rule exists, detekt returns 0 findings — test fails on hasSize(1).
```

---

**TC-RULE-02: fires on unannotated data class Screen subclass**

```
Input:
  sealed class Screen {
    data class Bar(val x: Int) : Screen()
  }
Expected findings: 1
Red state: Rule does not override visitClassOrObject (uses visitClass only) — data object variants
           are missed but data class also missed if rule is absent entirely.
```

---

**TC-RULE-03: silent on @HelpPage-annotated Screen subclass**

```
Input:
  import dev.stapler.stelekit.docs.HelpPage
  sealed class Screen {
    @HelpPage(docs = FakeDocs::class) data object Foo : Screen()
  }
Expected findings: 0
Assertion: assertThat(findings).isEmpty()
Red state: If the rule ignores annotations and fires unconditionally, test fails.
```

---

**TC-RULE-04: silent on @HelpExempt-annotated Screen subclass**

```
Input:
  import dev.stapler.stelekit.docs.HelpExempt
  sealed class Screen {
    @HelpExempt(reason = "debug only") data object Foo : Screen()
  }
Expected findings: 0
Red state: If the rule only checks for @HelpPage and ignores @HelpExempt, findings = 1 — test
           fails on isEmpty().
```

---

**TC-RULE-05: silent on class NOT nested in Screen**

```
Input:
  sealed class OtherClass {
    data object Foo : OtherClass()
  }
Expected findings: 0
Red state: If the parent-name check is absent, the rule fires on all sealed subclasses — test
           fails on isEmpty().
```

---

**TC-RULE-06: silent on sealed Screen class itself**

```
Input:
  sealed class Screen
Expected findings: 0
Red state: If the rule fires on the parent class (not only on its members), test fails.
```

---

**TC-RULE-07: silent on class nested inside an annotated Screen subclass (state class)**

```
Input:
  sealed class Screen {
    @HelpPage(docs = FakeDocs::class) data object Foo : Screen() {
      data class State(val x: Int)
    }
  }
Expected findings: 0 (the inner State class is not a Screen subclass)
Red state: If containingClassOrObject check only verifies name == "Screen" without checking
           the sealed parent guard, State would be flagged — test fails.
Note: The parent-sealed guard (parent is KtClass && parent.isSealed()) handles this because
      State's containing class is Foo, not the sealed Screen.
```

---

**TC-RULE-08: fires on data object inside full sealed class Screen declaration**

```
Input (full sealed class, not just a snippet):
  sealed class Screen {
    @HelpPage(docs = FakeDocs::class) data object Annotated : Screen()
    data object Unannotated : Screen()
  }
Expected findings: 1 (only Unannotated, not Annotated)
Assertion: assertThat(findings).hasSize(1)
            findings[0].entity.name == "Unannotated"
Red state: If rule fires on all Screen members regardless of annotation, findings = 2 — fails.
```

---

### 2.2 `DemoGraphCoverageTest` Extensions

**Source set:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/`
**Test class:** `DemoGraphCoverageTest` (extension of existing class)

**Setup:** Classpath resource `demo-graph/pages/` (already wired via `processTestResources`). Reflective scan of `Screen::class.java.declaredClasses`. No additional fixtures beyond what already exists.

---

**TC-DEMO-01: every @HelpPage Screen has a matching demo-graph .md file (HowToDoc path)**

This test already exists as `all HelpPage annotations reference existing non-empty pages`.

```
Assertion (HowToDoc branch):
  docs.howTo.title → File("demo-graph/pages/${docs.howTo.title}.md").exists() == true
  File.readText().isNotBlank() == true
Red state (pre-implementation): Zero @HelpPage annotations exist → test passes vacuously
                                 (0 annotated classes → 0 assertions → trivially passes).
  After Story 3.2 annotates screens: if a demo-graph page is absent for a title, test fails
  with: "[AllPages] Reference page missing: 'All Pages.md' in demo-graph/pages/"
```

---

**TC-DEMO-02: every @HelpPage Screen has a matching demo-graph .md file (ReferenceDoc path)**

Same test, `ReferenceDoc` branch (already implemented). Listed separately for traceability since `MinimalFeatureDoc` implements both `HowToDoc` and `ReferenceDoc`.

```
Red state: MinimalFeatureDoc title mismatch between howTo.title and reference.title vs
           actual filename → test fails for the reference.title path.
```

---

**TC-DEMO-03: coverage summary — all Screen subclasses (informational)**

Story 3.1 adds this. Always passes.

```
Setup: Screen::class.java.declaredClasses; KNOWN_EXEMPT_SCREENS companion set.
Assertion: None — prints summary block and returns.
Red state: Cannot fail by design. Value is CI-visible log output.
```

---

### 2.3 `WebsiteDocsCoverageTest`

**Source set:** `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/`
**Test class:** `WebsiteDocsCoverageTest`

**Setup:**
- Repo root resolved via classpath walk-up from `demo-graph/pages` resource URL (not `user.dir`)
- `siteDocsDir = repoRoot.resolve("site/src/content/docs/user")`
- Slug derived from `reference.title` (or `howTo.title` for HowToDoc-only) via:
  `title.lowercase().replace(' ', '-').replace(Regex("[^a-z0-9-]"), "")`
- Reflective scan: `Screen::class.java.declaredClasses.mapNotNull { it.getAnnotation(HelpPage::class.java) }`

---

**TC-SITE-01: site docs directory exists**

```
Assertion: siteDocsDir.isDirectory == true
Red state: If site/src/content/docs/user/ does not exist, test fails immediately with path
           in message before evaluating any Screen.
```

---

**TC-SITE-02: every @HelpPage Screen has a non-missing .mdx (or .md) file**

```
For each (screenName, annotation) in annotated screens:
  slug = slugFor(docs.reference.title or docs.howTo.title)
  Assertion: siteDocsDir.resolve("$slug.mdx").exists()
          OR siteDocsDir.resolve("$slug.md").exists()
Failure message: "[$screenName] Missing: site/src/content/docs/user/$slug.mdx"
Red state: Before Story 4.3 creates the .mdx files, every @HelpPage screen produces a
           failure entry → test fails listing all missing paths.
```

---

**TC-SITE-03: every matched site docs file is non-empty**

```
For each matched file (from TC-SITE-02):
  Assertion: file.readText().isNotBlank()
Failure message: "[$screenName] Empty: site/src/content/docs/user/$slug.mdx"
Red state: If a developer creates a placeholder empty file to silence TC-SITE-02, this
           assertion catches it.
```

---

**TC-SITE-04: @HelpExempt screens do not appear in the annotated scan (no false positives)**

```
Verification (implicit in scan logic): Screen::class.java.declaredClasses scans for
  @HelpPage annotation. @HelpExempt has SOURCE retention — invisible at runtime.
  Therefore: LibraryStats, Notifications, Logs, Performance, VaultUnlock, Import,
  AnnotationEditor, Gallery, AssetBrowser do NOT appear in the annotated list.
Assertion: None explicit — validated by the scan returning only @HelpPage-carrying classes.
Red state: If @HelpExempt were given RUNTIME retention and accidentally scanned, the test
           would demand docs pages for debug screens. SOURCE retention prevents this.
```

This is a design invariant, not a separate test. It is documented here for traceability to R1's `@HelpExempt` mechanism.

---

### 2.4 `DemoFileSystemSyncTest`

**Source set:** `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/`
**Test class:** `DemoFileSystemSyncTest`

**Setup:**
- `demoGraphPagesDir` via `classLoader.getResource("demo-graph/pages")` (classpath resource)
- `generatedFileSource` via walk-up from `demoGraphPagesDir` to find
  `src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt`
- `jvmTest` task `dependsOn(generateDemoFileSystem)` ensures file exists at test time

---

**TC-SYNC-01: every demo-graph page key appears in generated DemoFileSystem.kt source**

```
Setup: List all *.md files in demo-graph/pages/
Assertion: For each f: "\"pages/${f.name}\"" in generatedFileSource
Failure message: "These demo-graph pages are missing from the generated DemoFileSystem.kt:\n
                  pages/Unlinked References.md\n
                 Run :kmp:generateDemoFileSystem to regenerate."
Red state: Before Story 2.2 implements the Gradle task, DemoFileSystem.kt is the hand-
           written version and does not have key strings matching the file list format
           "pages/${f.name}" → test fails for any page not in the legacy file.
```

---

**TC-SYNC-02: generated file not absent (generation task must have run)**

```
Assertion: generatedFileSource loaded successfully (lazy val fails with fail() if file absent)
Failure message: "Generated DemoFileSystem.kt not found — run :kmp:generateDemoFileSystem first"
Red state: On a fresh checkout before `generateDemoFileSystem` runs (and before Story 2.4
           removes DemoFileSystem.kt from git), the file would be the old hand-written one.
           After Story 2.4, a fresh checkout has no file → test fails clearly.
```

---

**TC-SYNC-03: generated file contains no unescaped triple-quote sequences (regression guard)**

```
Assertion: Count occurrences of `"""` in generated source; subtract the expected structural
           occurrences (one per map entry opening + closing + journalTemplate). If any
           occurrence appears inside a map value string (between key and trimIndent), flag it.
Failure message: "Generated DemoFileSystem.kt contains unescaped triple-quote in a map value —
                  check demo-graph/*.md files for embedded \"\"\""
Note: This is the Known Issue mitigation from plan.md §Known Issues.
Red state: If a demo-graph .md file contains """ (e.g. Kotlin code fence) and escape logic
           is missing from the Gradle task, the generated file fails to compile. This test
           catches it before the compile step.
```

This test is additive to the plan (not explicitly named in Story 2.5 but called out in Known Issues). Recommend adding it alongside TC-SYNC-01 in the same test class.

---

### 2.5 Scenario Tests (Manual — Story 5.3)

These are not automated tests; they are scripted manual verification runs documented for the PR.

| Scenario | Trigger | Gate that fires | Expected failure message |
|----------|---------|----------------|--------------------------|
| A — Missing @HelpPage | Add `data object FakeScreen : Screen()` | `./gradlew detekt` | `MissingHelpPageAnnotation: Screen subclass 'FakeScreen' must carry @HelpPage or @HelpExempt` |
| B — Missing demo-graph page | Add `@HelpPage(docs = FakeDocs::class) data object FakeScreen : Screen()` with `FakeDocs.howTo.title = "Fake Feature"` | `./gradlew jvmTest` (DemoGraphCoverageTest) | `[FakeScreen] HowTo page missing: 'Fake Feature.md' in demo-graph/pages/` |
| C — Missing website docs page | Add @HelpPage + demo-graph page, no .mdx | `./gradlew jvmTest` (WebsiteDocsCoverageTest) | `[FakeScreen] Missing: site/src/content/docs/user/fake-feature.mdx` |

---

## 3. Implementation Readiness Gate

### Criterion 1: Requirements Coverage

| Requirement | Test(s) | Covered? |
|-------------|---------|---------|
| R1 — @HelpPage on every Screen | TC-RULE-01 through TC-RULE-08, Scenario A | YES |
| R2 — @HelpElement for sub-screen elements | None | NO — explicitly deferred to Phase 2 |
| R3 — DemoFileSystem auto-generated | TC-SYNC-01, TC-SYNC-02, TC-SYNC-03 | YES |
| R4 — DemoGraphCoverageTest extended to DemoFileSystem | TC-DEMO-01, TC-DEMO-02, TC-SYNC-01 | YES |
| R5 — Website /docs coverage CI gate | TC-SITE-01, TC-SITE-02, TC-SITE-03 | YES |
| R6 — All existing Screens annotated | TC-RULE-01..08 (enforcement); TC-DEMO-03 (visibility) | YES |
| R7 — Website /docs pages authored | TC-SITE-02 (existence), TC-SITE-03 (non-empty) | YES (content quality is manual) |
| R8 — `checkDocCoverage` Gradle task | Gradle task dependency graph (structural); Scenario A/B/C | YES |

**Coverage: 7/8 requirements have automated tests. R2 is intentionally deferred by the requirements document itself.**

---

### Criterion 2: Test Specificity

Each test in this document provides:
- Exact class name and source set path
- The specific assertion (including exact failure message text)
- The fixture or setup required (classpath resource, reflection scan, or inline Kotlin snippet)
- The rule algorithm details needed to implement it (e.g., `visitClassOrObject` not `visitClass`)

A developer can implement every test in this document without referencing any other artifact.

**Verdict: PASS**

---

### Criterion 3: Red-State Verifiability

Every test has an explicit "Red state" section above describing the pre-implementation condition that causes the test to fail. Key red states:

- `MissingHelpPageAnnotationRuleTest` cases: rule does not exist → 0 findings where 1 expected (TC-RULE-01/02); or rule fires unconditionally → findings where 0 expected (TC-RULE-03..07)
- `DemoGraphCoverageTest` existing test: zero annotations → vacuously passes; red state shifts to post-R6 state where annotations exist but demo-graph files are absent
- `WebsiteDocsCoverageTest`: no .mdx files exist for the slugs → every annotated Screen produces a failure entry
- `DemoFileSystemSyncTest`: hand-written DemoFileSystem.kt lacks the `"pages/${f.name}"` key format → fails for pages present in demo-graph/ but absent from legacy map
- TC-SYNC-03 triple-quote guard: escape logic absent from Gradle task → unescaped `"""` in generated source → test catches it before compile

One nuance: `DemoGraphCoverageTest` currently passes vacuously (0 annotations). The red state for TC-DEMO-01/02 is conditional — it only becomes red after R6 (Story 3.2) annotates Screens. The test provides zero enforcement until annotations exist. This is a known plan limitation, mitigated by the detekt rule (R1) which enforces annotations on every new Screen at write time.

**Verdict: PASS**

---

### Criterion 4: Plan Completeness — Task Accountability

| Story | Deliverable | Covered by test? |
|-------|-------------|-----------------|
| 1.1 — HelpExempt.kt | Annotation compiles; `reason` required | TC-RULE-04 (uses annotation); TC-RULE-01..03 (contrasts absence) |
| 1.2 — MissingHelpPageAnnotationRule | Detekt rule implementation | TC-RULE-01 through TC-RULE-08 |
| 1.3 — MissingHelpPageAnnotationRuleTest | Test file itself | TC-RULE-01..08 are the tests |
| 1.4 — Wire detekt.yml | `./gradlew detekt` fires rule | Scenario A (manual); implicit in TC-RULE-* via `buildSrc:test` |
| 2.1 — welcome-journal.md template | File exists with {DATE} placeholder | Not directly tested — content is embedded by the Gradle task verified in TC-SYNC-01/02 |
| 2.2 — generateDemoFileSystem Gradle task | Task generates valid DemoFileSystem.kt | TC-SYNC-01, TC-SYNC-02, TC-SYNC-03 |
| 2.3 — Wire compileKotlinWasmJs | Task runs before compile | Manual: `./gradlew compileKotlinWasmJs` is UP-TO-DATE on second run |
| 2.4 — gitignore DemoFileSystem.kt | File not tracked | Manual: `git status` check |
| 2.5 — DemoFileSystemSyncTest | Test class | TC-SYNC-01, TC-SYNC-02 (TC-SYNC-03 added as enhancement) |
| 3.1 — Extend DemoGraphCoverageTest | Coverage summary test | TC-DEMO-03 |
| 3.2 — Annotate Screen entries | All Screen members carry annotation | Verified by detekt passing (Story 1.4 + 3.2 shipped together) |
| 3.3 — DiataxisDoc implementations | Each doc class instantiatable | TC-DEMO-01/02 (reflective instantiation), TC-SITE-02 |
| 3.4 — Verify demo-graph pages exist | Files present and non-blank | TC-DEMO-01/02 |
| 4.1 — WebsiteDocsCoverageTest | Test class | TC-SITE-01, TC-SITE-02, TC-SITE-03 |
| 4.2 — Verify site/src/content/docs/user/ | Directory exists | TC-SITE-01 |
| 4.3 — Write 5 .mdx files | Files exist and non-empty | TC-SITE-02, TC-SITE-03 |
| 4.4 — Update astro.config.mjs sidebar | Links not 404 | Manual: `astro build` succeeds |
| 5.1 — checkDocCoverage Gradle task | Task exists and succeeds | Structural (task graph) |
| 5.2 — Add to ciCheck | `./gradlew ciCheck` subsumes gates | Structural (task graph) |
| 5.3 — Verify full CI pipeline | Scenarios A/B/C fail correctly | Manual scenarios |

All 20 stories are accounted for. Three stories (2.3 gitignore, 4.4 sidebar, 5.3 pipeline scenarios) are manual verification only — acceptable because they involve filesystem/toolchain concerns outside JVM test scope.

**Verdict: PASS**

---

### Readiness Gate Verdict

**CONCERNS**

The plan is implementable and well-specified. Two concerns warrant attention before coding begins:

**Concern 1 — R2 has no test path.** The requirements include `@HelpElement` (R2) as a named requirement, but neither the plan nor this validation document provides any automated gate for it. If the feature ships in this iteration, the lack of enforcement means elements can be added without documentation indefinitely. The plan's own text acknowledges this ("opt-in for now"). Recommend either: (a) explicitly exclude R2 from the scope of this iteration, updating the requirements document; or (b) add a stub `@HelpElement` annotation with a note that `MissingHelpElementAnnotation` rule is out of scope for this PR. The test suite as written covers 7/8 requirements.

**Concern 2 — DemoGraphCoverageTest vacuous pass window.** TC-DEMO-01/02 produce zero test value until Story 3.2 annotates existing Screens. There is a window between landing the detection infrastructure (Epics 1–2) and landing the annotations (Epic 3) where the test suite gives false confidence. This is inherent to the plan's sequencing. Mitigated by the detekt rule enforcing annotations on any new Screen written after Epic 1 lands.

Both concerns are pre-existing plan trade-offs, not test-design failures. No blocking issues exist that would prevent implementation from proceeding.

---

## Test Case Summary

| Type | Count | Test Class(es) |
|------|-------|---------------|
| detekt rule tests (buildSrc:test) | 8 | `MissingHelpPageAnnotationRuleTest` |
| jvmTest | 5 | `DemoGraphCoverageTest` (3), `WebsiteDocsCoverageTest` (2) |
| businessTest | 3 | `DemoFileSystemSyncTest` (TC-SYNC-01, 02, 03) |
| manual scenarios | 3 | Story 5.3 Scenarios A, B, C |
| **Total** | **19** | |

**Requirements coverage: 7/8 (87.5%). R2 intentionally deferred.**

**Readiness gate: CONCERNS (implementable with the two caveats documented above).**
