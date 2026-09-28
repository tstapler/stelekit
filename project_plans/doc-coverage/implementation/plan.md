# Documentation Coverage Enforcement — Implementation Plan

## Overview

This plan implements a three-layer enforcement net that makes it structurally impossible to ship a
new user-facing feature without (a) a demo-graph page, (b) a website `/docs` page, and (c)
`DemoFileSystem.kt` staying in sync automatically with the markdown source files.

**Current state:**
- `@HelpPage` annotation and `DiataxisDoc` interfaces exist
- Three Screen entries already carry `@HelpPage` (Journals, Flashcards, AllPages) with docs classes
- `DemoGraphCoverageTest` passes vacuously because `findAnnotatedClasses()` only finds what is
  already annotated — no enforcement against *missing* annotations
- `DemoFileSystem.kt` is 430+ lines of manually maintained Kotlin, duplicating `demo-graph/` content
- `site/src/content/docs/user/` has 5 existing pages (getting-started, outliner, journals,
  backlinks, search); no coverage gate exists

**Enforcement gaps closed by this plan:**
- Unannotated Screen subclasses: caught by new `MissingHelpPageAnnotation` detekt rule
- Annotated Screen without demo-graph page: already caught by `DemoGraphCoverageTest` (extended)
- Annotated Screen without website docs page: caught by new `WebsiteDocsCoverageTest`
- `DemoFileSystem.kt` out of sync: eliminated as a problem by making the file generated

---

## Dependency Order

Tasks within each epic are ordered to minimize integration pain. The correct execution sequence
across epics is:

```
Epic 1 (annotation infra) → Epic 2 (generation task) → Epic 3 (Screen annotations + demo-graph)
    → Epic 4 (website docs) → Epic 5 (CI wiring)
```

Epic 3 and Epic 4 can proceed in parallel once Epic 1 is complete.

---

## Epic 1: Annotation Infrastructure

**Goal:** Create the `@HelpExempt` annotation and the `MissingHelpPageAnnotation` detekt rule so
that any new `Screen` subclass lacking either `@HelpPage` or `@HelpExempt` fails `./gradlew detekt`.

---

### Story 1.1: Add @HelpExempt Annotation

**What to create/modify:**
- Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/HelpExempt.kt`

**Implementation:**

```kotlin
package dev.stapler.stelekit.docs

/**
 * Exempts a Screen subclass from the @HelpPage requirement.
 *
 * Use for internal/diagnostic screens that are never user-initiated entry points
 * (e.g. debug menu, conflict resolution sub-steps, annotation editor opened only
 * from an image block). The reason parameter is required to make exemptions
 * intentional and reviewable in code review.
 *
 * Prefer @HelpPage over @HelpExempt wherever possible. See CLAUDE.md for the
 * approved list of exempt screen categories.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class HelpExempt(val reason: String)
```

Key decisions:
- `@Retention(SOURCE)` — the detekt PSI visitor reads source-level annotations; `@HelpExempt` is
  not needed at runtime and must not appear in reflection-based coverage tests.
- `reason: String` is required (not `= ""`) — forces a written justification at the exemption site,
  making exemptions reviewable in diffs. A blank string does not compile.
- Declared in `docs/` package alongside `@HelpPage` — co-located for discoverability.

**Acceptance criteria:**
- `HelpExempt.kt` compiles across all KMP targets (it is `commonMain` with no platform deps)
- `@HelpExempt(reason = "...")` is usable on `data object` and `data class` members of `Screen`
- `@HelpExempt` (without `reason`) does not compile
- The annotation does not appear in JVM reflection scans (verified by `DemoGraphCoverageTest`)

**Dependencies:** None.

---

### Story 1.2: Add MissingHelpPageAnnotation Detekt Rule

**What to create/modify:**
- Create `buildSrc/src/main/kotlin/dev/stapler/detekt/MissingHelpPageAnnotationRule.kt`
- Modify `buildSrc/src/main/kotlin/dev/stapler/detekt/SteleKitRuleSetProvider.kt`

**Implementation notes:**

The rule must override `visitClassOrObject` (not `visitClass`), because `Screen` uses `data object`
declarations (`KtObjectDeclaration`) for most navigation destinations. `visitClass` only fires on
`KtClass` instances and would silently miss every `data object Journals : Screen()` entry —
defeating the rule's purpose.

Detection algorithm:
1. For every `KtClassOrObject`, check `containingClassOrObject?.name == "Screen"`.
2. Also verify the parent is a sealed class (`parent is KtClass && parent.isSealed()`) to guard
   against accidental name collisions with unrelated `Screen`-named classes elsewhere in the codebase.
3. Collect `annotationEntries.mapNotNull { it.shortName?.asString() }.toSet()`.
4. If neither `"HelpPage"` nor `"HelpExempt"` is in that set, report a `CodeSmell`.

The rule does NOT use a config-list exemption. All exemptions are co-located with the Screen class
via `@HelpExempt(reason = "...")`. This prevents stale config entries when Screen subclasses are
removed.

Add `MissingHelpPageAnnotationRule(config)` to the `listOf(...)` in `SteleKitRuleSetProvider.instance()`.

**Acceptance criteria:**
- Rule fires on `data object Foo : Screen()` with no annotation
- Rule fires on `data class Bar(val x: String) : Screen()` with no annotation
- Rule is silent on `@HelpPage(docs = FooDocs::class) data object Foo : Screen()`
- Rule is silent on `@HelpExempt(reason = "debug only") data object Foo : Screen()`
- Rule is silent on `data object Foo : SomeOtherClass()` (not a Screen subclass)
- Rule is silent on `sealed class Screen` itself
- Rule is silent on classes defined inside a Screen subclass (e.g., nested state classes)
- `./gradlew buildSrc:test` passes

**Dependencies:** Story 1.1 (must know `@HelpExempt` short name).

---

### Story 1.3: Add Detekt Rule Tests

**What to create/modify:**
- Create `buildSrc/src/test/kotlin/dev/stapler/detekt/MissingHelpPageAnnotationRuleTest.kt`

**Test cases required** (follow `MissingDirectRepositoryWriteRuleTest` as the pattern):

| Test name | Input snippet | Expected findings |
|---|---|---|
| `reports data object Screen subclass without annotation` | `data object Foo : Screen()` | 1 finding |
| `reports data class Screen subclass without annotation` | `data class Foo(val x: Int) : Screen()` | 1 finding |
| `silent on HelpPage-annotated Screen subclass` | `@HelpPage(docs = D::class) data object Foo : Screen()` | 0 findings |
| `silent on HelpExempt-annotated Screen subclass` | `@HelpExempt(reason = "debug") data object Foo : Screen()` | 0 findings |
| `silent on non-Screen subclass` | `data object Foo : OtherClass()` | 0 findings |
| `silent on sealed Screen class itself` | `sealed class Screen` | 0 findings |
| `silent on nested class inside Screen subclass` | inner `data class State` inside annotated Screen entry | 0 findings |
| `fires on data object in named sealed class Screen` | full `sealed class Screen { data object Foo : Screen() }` | 1 finding |

**Acceptance criteria:**
- All 8 test cases pass
- `./gradlew buildSrc:test` is green

**Dependencies:** Story 1.2.

---

### Story 1.4: Wire Rule into detekt.yml

**What to modify:**
- `kmp/config/detekt/detekt.yml` (or wherever the project's detekt config lives — verify path with
  `find . -name "detekt.yml"`)

**Entry to add:**

```yaml
stelekit:
  MissingHelpPageAnnotation:
    active: true
    excludes: ['**/buildSrc/**', '**/*Test.kt', '**/test/**']
```

The `excludes` pattern uses standard detekt glob syntax. No custom per-file filtering is needed
beyond test exclusion because the rule's parent-name check already limits scope to Screen children.

**Acceptance criteria:**
- `./gradlew detekt` reports `MissingHelpPageAnnotation` findings for any Screen entry missing both
  `@HelpPage` and `@HelpExempt`
- After Story 3.2 annotates all existing Screen entries, `./gradlew detekt` passes with zero
  `MissingHelpPageAnnotation` findings
- The rule does not fire on test source sets

**Dependencies:** Stories 1.2, 1.3. Must be wired *after* all existing Screen entries are
annotated (Story 3.2) to avoid a detekt failure state between Stories 1.4 and 3.2.

**Ordering note:** Wire the rule in detekt.yml in the same PR that adds `@HelpExempt` and
`@HelpPage` to all existing Screen entries (Story 3.2). Enabling the rule before annotating
existing screens will break detekt for other developers on the branch.

---

## Epic 2: DemoFileSystem Auto-Generation

**Goal:** Replace the manually maintained `DemoFileSystem.kt` (~430 lines) with a Gradle-generated
version that reads directly from `demo-graph/` resource files, eliminating the dual-maintenance
burden. The generated file must preserve the runtime date-injection logic for today's journal entry.

---

### Story 2.1: Add demo-graph/welcome-journal.md Template

**What to create:**
- `kmp/src/commonMain/resources/demo-graph/welcome-journal.md`

**Template content requirements:**
- Must contain `{DATE}` placeholder used by the `DemoFileSystem` class at **runtime** to inject
  today's date (format: `YYYY-MM-DD`). Do NOT resolve this at Gradle generation time.
- Must contain the same welcome content currently hardcoded in `DemoFileSystem.kt` lines 24–55
  (the existing journal content with `date::`, `tags::`, welcome blocks, etc.)
- Replace the existing Kotlin string template expressions (`${today.year}`, etc.) with `{DATE}`
  and individual `{YEAR}`, `{MONTH}`, `{DAY}` placeholders for the date:: property line

**Example structure:**
```
- date:: {DATE}
- tags:: #journal #welcome
- Welcome to SteleKit! ...
  - ...
```

The Gradle generation task embeds this file's content as a raw string literal (`journalTemplate`)
in the generated `DemoFileSystem.kt`. At runtime, `DemoFileSystem` replaces `{DATE}` etc. with
`Clock.System.todayIn(...)` values — preserving the existing behavior exactly.

**Acceptance criteria:**
- File exists at the specified path
- Contains `{DATE}`, `{YEAR}`, `{MONTH}`, `{DAY}` placeholders
- Content is substantively identical to the current hardcoded journal content in `DemoFileSystem.kt`
- No `${}` Kotlin template syntax present (would interfere with generation)

**Dependencies:** None.

---

### Story 2.2: Write generateDemoFileSystem Gradle Task

**What to modify:**
- `kmp/build.gradle.kts`

**Task design:**

Declare an inline task (no `buildSrc` needed — single module, no external dependencies, single file):

```kotlin
// ── Demo filesystem generator ──────────────────────────────────────────────────
// Reads: kmp/src/commonMain/resources/demo-graph/{pages,journals}/
// Writes: kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt
// Up-to-date: Gradle skips if no .md file in demo-graph changed since last run.

val generateDemoFileSystem by tasks.registering {
    val demoGraphDir = layout.projectDirectory.dir(
        "src/commonMain/resources/demo-graph"
    )
    inputs.dir(demoGraphDir).withPathSensitivity(PathSensitivity.RELATIVE)

    val outputFile = layout.projectDirectory.file(
        "src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt"
    )
    outputs.file(outputFile)

    doLast {
        val graphDir = demoGraphDir.asFile
        val out = outputFile.asFile

        // Read all .md files from pages/ and journals/, sorted for idempotency
        val pageEntries = graphDir.resolve("pages").walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.name }
            .map { "pages/${it.name}" to it.readText() }
            .toList()

        val journalEntries = graphDir.resolve("journals").walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.name }
            .map { "journals/${it.name}" to it.readText() }
            .toList()

        // Read journal template; embedded with placeholders intact for runtime substitution
        val journalTemplate = graphDir.resolve("welcome-journal.md").readText()

        // Emit Kotlin source
        fun String.escapeForTripleQuotedString(): String =
            this.replace("\$", "\${'$'}")
                .replace("\"\"\"", "\${\"\\\"\\\"\\\"\"}") // handle embedded """

        val staticMapEntries = (pageEntries + journalEntries).joinToString(",\n") { (key, content) ->
            "        \"$key\" to \"\"\"\n${content.escapeForTripleQuotedString()}\"\"\".trimIndent()"
        }

        out.parentFile.mkdirs()
        out.writeText("""
            // GENERATED — do not edit. Run :kmp:generateDemoFileSystem to regenerate.
            // Source: kmp/src/commonMain/resources/demo-graph/
            package dev.stapler.stelekit.platform

            import kotlin.time.Clock
            import kotlinx.datetime.TimeZone
            import kotlinx.datetime.todayIn

            class DemoFileSystem : FileSystem {
                private val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
                private val journalFileName =
                    "${'$'}{today.year}_${'$'}{today.monthNumber.toString().padStart(2, '0')}_${'$'}{today.dayOfMonth.toString().padStart(2, '0')}.md"

                private val journalTemplate = \"\"\"
            ${journalTemplate.escapeForTripleQuotedString()}
                \"\"\".trimIndent()

                private val overrides = mutableMapOf<String, String>()

                private val demoFiles: Map<String, String> = buildMap {
            ${'$'}staticMapEntries
                    // Override with today's journal entry (date-substituted at runtime)
                    put("journals/${'$'}journalFileName",
                        journalTemplate
                            .replace("{DATE}", "${'$'}{today.year}-${'$'}{today.monthNumber.toString().padStart(2, '0')}-${'$'}{today.dayOfMonth.toString().padStart(2, '0')}")
                            .replace("{YEAR}", today.year.toString())
                            .replace("{MONTH}", today.monthNumber.toString().padStart(2, '0'))
                            .replace("{DAY}", today.dayOfMonth.toString().padStart(2, '0'))
                    )
                }

                // --- FileSystem interface implementation (unchanged from manual version) ---
                // ... copy existing read/write/list/exists implementations here
            }
        """.trimIndent())
    }
}
```

**Critical implementation details:**

1. **Sort all file lists by name before generating.** `File.walkTopDown()` iteration order is
   OS-dependent. Unsorted output causes Gradle's up-to-date check to invalidate `compileKotlinWasmJs`
   on every build even when no markdown changed.

2. **Do not embed build timestamps** in the generated file header. The `// GENERATED` comment is
   sufficient. A build timestamp would invalidate the Gradle compile cache on every run.

3. **Escape `$` as `${'$'}`** inside triple-quoted string literals. Any `$variable` in markdown
   content (e.g., shell examples) causes a Kotlin `StringTemplate` compile error otherwise.

4. **Escape `"""` in content** as `${"\"\"\""}`. Markdown code fences rarely contain three
   consecutive double-quotes, but the escaping must handle it to avoid silent truncation bugs.

5. **Preserve runtime date injection.** The template content (`welcome-journal.md`) is embedded as
   a `journalTemplate` property with `{DATE}` placeholders intact. Date substitution happens at
   runtime via `Clock.System.todayIn(...)` — identical to the existing manually written class.
   This ensures the demo always shows today's date regardless of when the WASM bundle was built.

6. **Copy all existing `FileSystem` interface method implementations** from the current
   `DemoFileSystem.kt` into the template string verbatim. The generated file must implement the
   same `FileSystem` interface as the current file.

**Acceptance criteria:**
- `./gradlew :kmp:generateDemoFileSystem` produces a valid `DemoFileSystem.kt`
- Generated file compiles as part of `compileKotlinWasmJs`
- Running the task twice with no input changes produces identical output (idempotency)
- Generated file contains one entry per `.md` file in `demo-graph/pages/` and `demo-graph/journals/`
- Generated file contains `journalFileName` computed at runtime (not hardcoded build date)
- Generated `DemoFileSystem` class passes the same runtime behavior as the existing manual version
  (today's journal entry shows today's date when loaded in the browser)

**Dependencies:** Story 2.1.

---

### Story 2.3: Wire Task as Dependency of compileKotlinWasmJs

**What to modify:**
- `kmp/build.gradle.kts` (after the `generateDemoFileSystem` task declaration)

**Wiring:**

```kotlin
// Only wire when wasmJs target is enabled
if (project.findProperty("enableJs") == "true") {
    afterEvaluate {
        tasks.named("compileKotlinWasmJs") {
            dependsOn(generateDemoFileSystem)
        }
    }
}
```

The `afterEvaluate` guard matches the existing pattern used by the sqlite-wasm copy task at lines
354–369 of `kmp/build.gradle.kts`. On Gradle 9 this is acceptable; an alternative using
`tasks.matching { it.name == "compileKotlinWasmJs" }.configureEach { ... }` avoids `afterEvaluate`
but both approaches work correctly.

**Acceptance criteria:**
- `./gradlew compileKotlinWasmJs` (when `enableJs=true`) automatically runs `generateDemoFileSystem`
  first if `DemoFileSystem.kt` is absent or any `.md` source file is newer
- `./gradlew compileKotlinWasmJs` skips `generateDemoFileSystem` when inputs are unchanged (Gradle
  reports task as `UP-TO-DATE`)
- `./gradlew wasmJsBrowserDistribution` produces a working WASM bundle with demo content

**Dependencies:** Story 2.2.

---

### Story 2.4: Add DemoFileSystem.kt to .gitignore

**What to modify:**
- Root `.gitignore` (or `kmp/.gitignore` if it exists)

**Entry to add:**
```
kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt
```

Delete the existing checked-in `DemoFileSystem.kt` from git tracking:
```bash
git rm --cached kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt
```

The file remains on disk (Gradle will regenerate it) but is no longer tracked by git.

**Acceptance criteria:**
- `git status` does not show `DemoFileSystem.kt` as a tracked or untracked file
- `.gitignore` contains the path
- A fresh `git clone` + `./gradlew compileKotlinWasmJs` (with `enableJs=true`) regenerates the file

**Dependencies:** Story 2.3 (the generation task must work before removing the manual file).

---

### Story 2.5: Write DemoFileSystemSyncTest

**What to create:**
- `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/DemoFileSystemSyncTest.kt`

The test reads the generated `DemoFileSystem.kt` source file as text and asserts every `.md` file
in `demo-graph/pages/` appears as a string key in the generated output. This catches bugs in the
Gradle task (e.g., a file being silently skipped, wrong path separator on Windows).

```kotlin
class DemoFileSystemSyncTest {

    private val demoGraphPagesDir: File by lazy {
        val url = javaClass.classLoader.getResource("demo-graph/pages")
            ?: fail("demo-graph/pages not found on classpath")
        File(url.toURI())
    }

    private val generatedFileSource: String by lazy {
        // Walk up from the classpath resource to the repo root, then locate the generated file.
        // The generated file is in wasmJsMain, not on the test classpath — read it as a File.
        var dir = demoGraphPagesDir
        while (dir != dir.parentFile) {
            val candidate = dir.resolve(
                "src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt"
            )
            if (candidate.exists()) return@lazy candidate.readText()
            dir = dir.parentFile
        }
        fail("Generated DemoFileSystem.kt not found — run :kmp:generateDemoFileSystem first")
    }

    @Test
    fun `every demo-graph page appears in generated DemoFileSystem`() {
        val mdFiles = demoGraphPagesDir.listFiles { f -> f.extension == "md" }
            ?: fail("demo-graph/pages is not a directory")

        val missing = mdFiles.filter { f ->
            "\"pages/${f.name}\"" !in generatedFileSource
        }
        assertTrue(missing.isEmpty(),
            "These demo-graph pages are missing from the generated DemoFileSystem.kt:\n" +
            missing.joinToString("\n") { "  pages/${it.name}" } +
            "\nRun :kmp:generateDemoFileSystem to regenerate.")
    }
}
```

Wire `jvmTest` to depend on `generateDemoFileSystem` so the generated file exists when the test
runs:

```kotlin
// In kmp/build.gradle.kts:
tasks.named("jvmTest") {
    dependsOn(generateDemoFileSystem)
}
```

**Acceptance criteria:**
- Test passes after `generateDemoFileSystem` has run
- Test fails with a clear message if a page `.md` file is in `demo-graph/pages/` but not in the
  generated `DemoFileSystem.kt`
- Test fails with a clear message if `generateDemoFileSystem` has not been run (file absent)

**Dependencies:** Stories 2.2, 2.4.

---

## Epic 3: Demo-Graph Coverage Enforcement

**Goal:** Annotate all existing `Screen` entries with `@HelpPage` or `@HelpExempt`, create any
missing `DiataxisDoc` classes, and ensure `DemoGraphCoverageTest` provides full visibility into
coverage status.

---

### Story 3.1: Extend DemoGraphCoverageTest for Full Visibility

**What to modify:**
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/DemoGraphCoverageTest.kt`

Add a test that scans ALL `Screen` subclasses (not only `@HelpPage`-annotated ones) and prints a
coverage summary — how many are annotated, how many are exempt, and how many are unannotated. This
test always passes (it is informational), but its output is visible in CI test logs and provides
a progress tracker during the rollout.

```kotlin
@Test
fun `coverage summary — all Screen subclasses`() {
    val screenClass = Screen::class.java
    val allSubclasses = screenClass.declaredClasses.toList()

    val annotated = allSubclasses.filter { it.getAnnotation(HelpPage::class.java) != null }
    val exempt = allSubclasses.filter {
        // @HelpExempt has SOURCE retention — not visible at runtime.
        // Count by absence: neither @HelpPage nor any known exempt annotation.
        it.getAnnotation(HelpPage::class.java) == null &&
        it.simpleName in KNOWN_EXEMPT_SCREENS
    }
    val unannotated = allSubclasses - annotated.toSet() - exempt.toSet()

    println("""
        Screen coverage summary:
          Total Screen subclasses : ${allSubclasses.size}
          @HelpPage annotated     : ${annotated.size}
          Known exempt            : ${exempt.size}
          Unannotated (gap)       : ${unannotated.size}
          Unannotated names       : ${unannotated.map { it.simpleName }}
    """.trimIndent())
    // This test always passes — it is informational only.
}

companion object {
    // Mirrors the @HelpExempt reason list. Keep in sync with AppState.kt.
    private val KNOWN_EXEMPT_SCREENS = setOf(
        "LibraryStats", "Notifications", "Logs", "Performance",
        "VaultUnlock", "Import", "AnnotationEditor", "Gallery", "AssetBrowser",
        "GlobalUnlinkedReferences"
    )
}
```

**Acceptance criteria:**
- `./gradlew jvmTest` output includes the coverage summary block
- Test always passes (it is informational, not a gate)
- Summary correctly reflects the state of `Screen` subclasses as of test time

**Dependencies:** Epic 1 (so `@HelpExempt` annotation exists and can be referenced in comments).

---

### Story 3.2: Annotate All Existing Screen Entries

**What to modify:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`

**Current state of `Screen` (from reading the file):**

Already annotated (no action needed):
- `Screen.Journals` — `@HelpPage(docs = JournalsDocs::class)` — title maps to "Daily Notes.md"
- `Screen.Flashcards` — `@HelpPage(docs = FlashcardsDocs::class)` — title maps to "Flashcards.md"
- `Screen.AllPages` — `@HelpPage(docs = AllPagesDocs::class)` — title maps to "All Pages.md"
- `Screen.PageView` — `@HelpPage(docs = PageViewDocs::class)` — title maps to "Block Editing.md"

Needs `@HelpPage` (user-navigable, require DiataxisDoc class from Story 3.3):
- `Screen.GlobalUnlinkedReferences` — requires new `GlobalUnlinkedReferencesDocs`

Needs `@HelpExempt`:
- `Screen.LibraryStats` — `@HelpExempt(reason = "Internal diagnostics screen; developer tooling only, not reachable from user nav")`
- `Screen.Notifications` — `@HelpExempt(reason = "System surface shown automatically; users do not navigate to it deliberately")`
- `Screen.Logs` — `@HelpExempt(reason = "Developer log viewer; reachable only from debug menu")`
- `Screen.Performance` — `@HelpExempt(reason = "Developer profiling screen; reachable only from debug menu")`
- `Screen.VaultUnlock` — `@HelpExempt(reason = "Shown programmatically when opening a paranoid-mode graph; not a user-initiated nav destination")`
- `Screen.Import` — `@HelpExempt(reason = "Transient wizard step shown during onboarding; not a standing navigation destination")`
- `Screen.AnnotationEditor` — `@HelpExempt(reason = "Entered via image tap, not from sidebar nav; advanced feature for image annotation")`
- `Screen.Gallery` — `@HelpExempt(reason = "Full-screen gallery shown from image blocks; not a primary navigation destination")`
- `Screen.AssetBrowser` — `@HelpExempt(reason = "Asset management surface; power-user feature not in primary nav")`

**Note on `Screen.Search` and `Screen.Settings`:** These do not appear in the current `AppState.kt`
as sealed class members (as of the file read). `SearchDocs` class exists in `ScreenDocs.kt` but
`Screen.Search` is not in the sealed class. Verify the complete sealed class hierarchy before the
PR — if search and settings are implemented as dialogs/state rather than `Screen` subclasses, no
annotation is needed.

**Acceptance criteria:**
- Every `Screen` subclass in `AppState.kt` carries either `@HelpPage` or `@HelpExempt(reason = "...")`
- `@HelpExempt` without a reason string does not compile
- `./gradlew detekt` passes with zero `MissingHelpPageAnnotation` findings after this story
- `./gradlew jvmTest` (DemoGraphCoverageTest) passes — all `@HelpPage`-annotated docs classes exist

**Dependencies:** Stories 1.1, 1.4, 3.3.

---

### Story 3.3: Create DiataxisDoc Implementations for Each @HelpPage Screen

**What to modify:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/ScreenDocs.kt`

**Already implemented** (no action needed):
- `JournalsDocs` (HowToDoc) — title "Daily Notes"
- `FlashcardsDocs` (HowToDoc) — title "Flashcards"
- `AllPagesDocs` (ReferenceDoc) — title "All Pages"
- `PageViewDocs` (MinimalFeatureDoc) — titles "Block Editing" + "Block Editor Reference"
- `SearchDocs` (HowToDoc) — title "Search" (exists but not yet wired to `@HelpPage` on Screen)

**Needs to be created:**
- `GlobalUnlinkedReferencesDocs` (MinimalFeatureDoc) — titles for howTo and reference matching
  existing demo-graph pages if present, or new pages if not

**GlobalUnlinkedReferencesDocs minimum viable implementation:**

```kotlin
class GlobalUnlinkedReferencesDocs : MinimalFeatureDoc {
    override val howTo = HowToContent(
        title = "Unlinked References",
        steps = listOf(
            "Open the Global Unlinked References view from the sidebar",
            "Review each suggested link — it shows a block that mentions a page name but has no [[wikilink]]",
            "Tap 'Link' to convert the mention to a wikilink, or 'Ignore' to dismiss it"
        )
    )
    override val reference = ReferenceContent(
        title = "Unlinked References",
        description = "Lists blocks across your entire graph that mention a page name as plain text " +
            "without creating a [[wikilink]]. Linking these improves graph connectivity and backlink coverage."
    )
}
```

**Acceptance criteria:**
- All `@HelpPage`-annotated Screen entries have a corresponding `DiataxisDoc` implementation class
- Each `MinimalFeatureDoc` has both `howTo.title` and `reference.title` matching existing
  demo-graph page filenames (or Story 3.4 creates the missing pages)
- `DemoGraphCoverageTest` passes for all annotated entries

**Dependencies:** Story 3.1.

---

### Story 3.4: Verify All Referenced Demo-Graph Pages Exist

**What to verify (no creation needed unless a page is missing):**

Cross-reference the `howTo.title` and `reference.title` values from each `DiataxisDoc` against the
`demo-graph/pages/` directory. From the current file listing:

| DiataxisDoc title | demo-graph/pages/ file | Status |
|---|---|---|
| "Daily Notes" | `Daily Notes.md` | Exists |
| "Flashcards" | `Flashcards.md` | Exists |
| "All Pages" | `All Pages.md` | Exists |
| "Block Editing" | `Block Editing.md` | Exists |
| "Block Editor Reference" | `Block Editor Reference.md` | Exists |
| "Search" | `Search.md` | Exists |
| "Unlinked References" | `Unlinked References.md` | **Missing — must create** |

**What to create (if GlobalUnlinkedReferencesDocs is added in Story 3.3):**
- `kmp/src/commonMain/resources/demo-graph/pages/Unlinked References.md`

Minimum viable content (must be non-blank and have substantive content, not a stub):

```markdown
- Unlinked References shows every mention of a page name that is not yet a [[wikilink]].
- Open it from the sidebar to see suggested links across your entire graph.
  - Each entry shows the block text and which page it could link to.
  - Tap **Link** to convert the mention to a `[[wikilink]]` automatically.
  - Tap **Ignore** to dismiss the suggestion without linking.
- Linking unlinked references improves your graph's connectivity.
  - More wikilinks means more backlinks, which makes the graph view richer.
  - Use this view periodically to catch pages you reference by name but never formally linked.
```

**Acceptance criteria:**
- `DemoGraphCoverageTest` passes with zero failures for all `@HelpPage`-annotated entries
- Every referenced demo-graph page file exists and is non-blank

**Dependencies:** Story 3.3.

---

## Epic 4: Website Docs Coverage Enforcement

**Goal:** Create `WebsiteDocsCoverageTest` and write the minimum required `.mdx` doc pages in
`site/src/content/docs/user/` for every `@HelpPage`-annotated Screen. Gate CI on file existence.

---

### Story 4.1: Write WebsiteDocsCoverageTest

**What to create:**
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/docs/WebsiteDocsCoverageTest.kt`

**Repository root resolution (use classpath anchor, not `user.dir`):**

```kotlin
private val repoRoot: File by lazy {
    val resource = javaClass.classLoader.getResource("demo-graph/pages")
        ?: error("demo-graph/pages not found on classpath — is the test classpath misconfigured?")
    var dir = File(resource.toURI())
    while (dir != dir.parentFile) {
        if (dir.resolve("kmp").isDirectory && dir.resolve("site").isDirectory) return@lazy dir
        dir = dir.parentFile
    }
    error("Could not locate repository root (dir containing both kmp/ and site/) from: $resource")
}

private val siteDocsDir: File by lazy {
    repoRoot.resolve("site/src/content/docs/user")
}
```

This approach is robust to Gradle changing the `processedResources` path and works identically when
the test is run from IntelliJ, the command line, or CI.

**Slug derivation:** Use `howTo.title` from the `DiataxisDoc` (as specified in R5 — the how-to
title is the primary user-facing name). Fall back to `reference.title` only if the doc implements
`ReferenceDoc` but not `HowToDoc`.

```kotlin
private fun slugFor(title: String): String =
    title.lowercase()
        .replace(' ', '-')
        .replace(Regex("[^a-z0-9-]"), "")
```

**Test assertion:**

```kotlin
@Test
fun `every HelpPage-annotated Screen has a website docs page`() {
    assertTrue(siteDocsDir.isDirectory,
        "site/src/content/docs/user/ not found at $siteDocsDir — " +
        "create the directory and add at least one .mdx file")

    val annotated = Screen::class.java.declaredClasses
        .mapNotNull { it.getAnnotation(HelpPage::class.java)?.let { ann -> it.simpleName to ann } }

    val failures = mutableListOf<String>()
    for ((screenName, annotation) in annotated) {
        val docs = annotation.docs.java.getDeclaredConstructor().newInstance() as DiataxisDoc
        val title = when (docs) {
            is HowToDoc -> docs.howTo.title
            is ReferenceDoc -> docs.reference.title
            else -> continue
        }
        val slug = slugFor(title)
        val mdx = siteDocsDir.resolve("$slug.mdx")
        val md  = siteDocsDir.resolve("$slug.md")
        when {
            !mdx.exists() && !md.exists() ->
                failures += "[$screenName] Missing: site/src/content/docs/user/$slug.mdx"
            mdx.exists() && mdx.readText().isBlank() ->
                failures += "[$screenName] Empty: site/src/content/docs/user/$slug.mdx"
            md.exists()  && md.readText().isBlank() ->
                failures += "[$screenName] Empty: site/src/content/docs/user/$slug.md"
        }
    }
    assertTrue(failures.isEmpty(),
        "Website docs missing or empty:\n${failures.joinToString("\n")}")
}
```

**Acceptance criteria:**
- Test passes when all `@HelpPage`-annotated Screens have non-empty `.mdx` files in
  `site/src/content/docs/user/`
- Test fails with a clear, actionable message listing each missing file path
- Test resolves `site/` correctly when run from IntelliJ, `./gradlew jvmTest`, and CI
- Zero false positives from `@HelpExempt`-annotated screens (they are not in the annotated scan)

**Dependencies:** Epic 1 (so `@HelpPage`-annotated Screens exist to test), Story 4.2.

---

### Story 4.2: Create site/src/content/docs/user/ Directory Structure

The directory `site/src/content/docs/user/` already exists (confirmed: 5 files present). No
creation needed. Verify the directory is correct before writing files in Story 4.3.

**What to verify:**
- `site/src/content/docs/user/` contains `getting-started.mdx`, `journals.mdx`, `outliner.mdx`,
  `backlinks.mdx`, `search.mdx`
- The content.config.ts Starlight schema is compatible with plain `title:` frontmatter

**Acceptance criteria:**
- Directory exists and is recognized by Starlight's content collection
- A file added to `site/src/content/docs/user/` appears in the sidebar after `sidebar` config
  update in Story 4.4

**Dependencies:** None.

---

### Story 4.3: Write .mdx Files for Each Annotated Screen

**What to create** (files that do not already exist):

The slug derivation uses `reference.title` from the doc class. Cross-reference with existing files:

| Screen | DiataxisDoc title | Slug | Existing? |
|---|---|---|---|
| Journals | "Daily Notes" | `daily-notes` | No — `journals.mdx` exists but slug mismatch |
| Flashcards | "Flashcards" | `flashcards` | No |
| AllPages | "All Pages" | `all-pages` | No |
| PageView | "Block Editing" | `block-editing` | No (`outliner.mdx` covers editing but slug differs) |
| GlobalUnlinkedReferences | "Unlinked References" | `unlinked-references` | No |

**Slug mismatch resolution:** The test derives slugs from the `DiataxisDoc` title. If an existing
file (`journals.mdx`) uses a different slug than the title-derived slug (`daily-notes`), there are
two options:

1. Rename the existing `journals.mdx` to `daily-notes.mdx` and update `astro.config.mjs` — keeps
   docs consistent with the DiataxisDoc title used in the demo graph
2. Change the `JournalsDocs.howTo.title` to "Journals" to match the existing file slug

**Recommended:** Option 1 — rename the file. The DiataxisDoc title "Daily Notes" matches the
demo-graph page filename `Daily Notes.md` and is the user-facing feature name throughout the app.
The existing `journals.mdx` content can be preserved (rename only).

**Files to create:**

**`site/src/content/docs/user/daily-notes.mdx`** (rename from `journals.mdx`, update content):
```mdx
---
title: Daily Notes
description: Capture your daily thoughts, tasks, and ideas in SteleKit's journal view.
---

## What is the Journals view?

The Journals view is a reverse-chronological feed of your daily notes. Each day gets its own
page, stored as a plain Markdown file (`journals/YYYY_MM_DD.md`).

## How to access it

Tap the **Journals** icon in the sidebar. SteleKit opens today's entry automatically.

## Key interactions

- Type in any block to add content to today's journal
- Scroll down to see previous days
- Tap a date header to open that date as a full page
- Create a `[[2026-04-12]]` wikilink from any page to jump to that date's entry

## Keyboard shortcuts

| Shortcut | Action |
|---|---|
| Enter | Create a new block below the current block |
| Tab | Indent the current block |
| Shift+Tab | Unindent the current block |

## Related features

- [Backlinks](/user/backlinks/) — see which pages link back to your journal entries
- [Search](/user/search/) — search across all journal entries by keyword
```

**`site/src/content/docs/user/flashcards.mdx`**:
```mdx
---
title: Flashcards
description: Turn any block into a spaced-repetition flashcard for active recall practice.
---

## What are Flashcards?

SteleKit's Flashcards feature turns any block into a spaced-repetition review card.
The scheduler surfaces cards at increasing intervals — cards you know well appear less often,
while cards you find difficult appear more frequently.

## How to access it

Tap the **Flashcards** icon in the sidebar to open the review queue. Cards due for review
appear in order.

## Key interactions

- Add `card:: true` as a child block property to mark any block as a flashcard
- Review cards one at a time: read the front, recall your answer, then reveal the back
- Rate your recall: **Again** returns the card soon, **Easy** defers it longer
- The scheduler adjusts each card's next review date based on your rating history

## Keyboard shortcuts

Flashcard review is primarily a tap/click interface. No keyboard shortcuts in v1.

## Related features

- [Block Editing](/user/block-editing/) — how to create and nest blocks
- [Properties](/user/outliner/) — the `key:: value` block property syntax
```

**`site/src/content/docs/user/all-pages.mdx`**:
```mdx
---
title: All Pages
description: Browse every page in your graph alphabetically.
---

## What is the All Pages view?

All Pages lists every non-journal page in your graph, sorted alphabetically by title. It is
the fastest way to navigate to a page when you know its name but do not want to use Search.

## How to access it

Tap **All Pages** in the sidebar.

## Key interactions

- Tap any page name to open it
- Scroll to browse all pages alphabetically
- Journal entries (stored in `journals/`) are excluded — use the Journals view for those

## Related features

- [Search](/user/search/) — find pages by keyword when you only remember part of the content
- [Daily Notes](/user/daily-notes/) — browse journal entries chronologically
```

**`site/src/content/docs/user/block-editing.mdx`**:
```mdx
---
title: Block Editing
description: Create, indent, and organize blocks — the fundamental unit of content in SteleKit.
---

## What are blocks?

Every line of text in SteleKit is a block. Blocks can be nested to any depth, creating an
outline hierarchy. A page is a named list of blocks.

## How to access it

Tap any page name from All Pages, Search, or the sidebar to open it in the block editor.

## Key interactions

- Click or tap anywhere on the page to focus the editor and start typing
- Press **Enter** to create a new block below the current one
- Press **Tab** to indent a block (makes it a child of the block above)
- Press **Shift+Tab** to unindent (promotes the block up one level)
- Click the bullet triangle to collapse children under a parent block
- Press **Backspace** on an empty block to delete it

## Keyboard shortcuts

| Shortcut | Action |
|---|---|
| Enter | New block below |
| Tab | Indent block |
| Shift+Tab | Unindent block |
| Backspace on empty | Delete block |
| Shift+Enter | Line break within block (soft newline) |
| Arrow Up / Down | Move cursor between blocks |
| Ctrl+Home | Jump to first block |
| Ctrl+End | Jump to last block |

## Inline formatting

`**bold**`, `*italic*`, `` `code` ``, `~~strikethrough~~`, `[[wikilinks]]`

## Related features

- [Page Linking](/user/outliner/) — create links between pages with `[[wikilinks]]`
- [Search](/user/search/) — find content across all blocks
```

**`site/src/content/docs/user/unlinked-references.mdx`**:
```mdx
---
title: Unlinked References
description: Discover and link mentions of page names that are not yet wikilinks.
---

## What are Unlinked References?

Unlinked References shows every block in your graph that mentions a page name as plain text
without using a `[[wikilink]]`. Linking these improves graph connectivity and backlink coverage.

## How to access it

Open **Unlinked References** from the sidebar.

## Key interactions

- Each entry shows the block text and the page it could link to
- Tap **Link** to convert the plain-text mention to a `[[wikilink]]` automatically
- Tap **Ignore** to dismiss the suggestion
- Dismissed suggestions do not reappear

## Why link unlinked references?

More wikilinks create more backlinks. When you link mentions, the linked page's **Backlinks**
panel shows the context where it was mentioned — making your graph navigable in both directions.

## Related features

- [Backlinks](/user/backlinks/) — see all pages that link to the current page
- [Page Linking](/user/outliner/) — how `[[wikilinks]]` work
```

**Acceptance criteria:**
- All 5 files exist at their specified paths under `site/src/content/docs/user/`
- Each file has valid Starlight frontmatter (`title:` and `description:`)
- Each file contains at minimum: what the feature is, how to access it, key interactions, a
  keyboard shortcuts section (even if it says "N/A"), and a related features section
- Each file is non-blank (well over the minimum 15 non-blank lines)
- `WebsiteDocsCoverageTest` passes after all files are created

**Dependencies:** Stories 4.1, 4.2.

---

### Story 4.4: Add Sidebar Entries to astro.config.mjs

**What to modify:**
- `site/astro.config.mjs`

**Current sidebar `User Guide` items** (from file read):
```javascript
{ label: 'Getting Started', slug: 'user/getting-started' },
{ label: 'Outliner', slug: 'user/outliner' },
{ label: 'Journals', slug: 'user/journals' },      // rename to daily-notes after Story 4.3
{ label: 'Backlinks', slug: 'user/backlinks' },
{ label: 'Search', slug: 'user/search' },
```

**Additions required** (after Story 4.3 creates the files):
```javascript
{ label: 'Daily Notes', slug: 'user/daily-notes' },    // renamed from journals
{ label: 'Flashcards', slug: 'user/flashcards' },
{ label: 'All Pages', slug: 'user/all-pages' },
{ label: 'Block Editing', slug: 'user/block-editing' },
{ label: 'Unlinked References', slug: 'user/unlinked-references' },
```

Remove the old `{ label: 'Journals', slug: 'user/journals' }` entry if `journals.mdx` is renamed
to `daily-notes.mdx`.

**Acceptance criteria:**
- All new doc pages appear in the sidebar when `astro dev` is run
- No 404 errors for any sidebar link
- `astro build` succeeds with no content collection errors

**Dependencies:** Story 4.3.

---

## Epic 5: CI Wiring

**Goal:** Establish `checkDocCoverage` as a single verification target that subsumes all coverage
gates, and add it to `ciCheck` so documentation gaps block every PR.

---

### Story 5.1: Add checkDocCoverage Gradle Task

**What to modify:**
- `kmp/build.gradle.kts`

```kotlin
tasks.register("checkDocCoverage") {
    group = "verification"
    description = "Assert every Screen has a demo-graph page, website /docs page, " +
        "and that DemoFileSystem.kt is in sync. Subsumes generateDemoFileSystem + jvmTest."
    dependsOn("generateDemoFileSystem", "jvmTest")
}
```

`DemoGraphCoverageTest` and `WebsiteDocsCoverageTest` are ordinary `@Test` functions that run
inside `jvmTest`. `DemoFileSystemSyncTest` also runs inside `jvmTest` but requires
`generateDemoFileSystem` to have run first — the `dependsOn` on `jvmTest` task already set in
Story 2.5 ensures this.

**Acceptance criteria:**
- `./gradlew checkDocCoverage` runs `generateDemoFileSystem` then `jvmTest`
- If all coverage tests pass, the task succeeds
- If any coverage test fails, the task fails with the test output showing which files are missing

**Dependencies:** Stories 2.5, 4.1.

---

### Story 5.2: Add checkDocCoverage to ciCheck

**What to modify:**
- `kmp/build.gradle.kts` (the existing `ciCheck` task definition)

Find the `ciCheck` task (currently wires `detekt` + `jvmTest` + `testDebugUnitTest` +
`assembleDebug`) and add `checkDocCoverage`:

```kotlin
tasks.named("ciCheck") {
    dependsOn("checkDocCoverage")
}
```

Since `checkDocCoverage` already depends on `jvmTest`, this does not cause duplicate test runs —
Gradle's task execution graph deduplicates.

**Acceptance criteria:**
- `./gradlew ciCheck` includes `checkDocCoverage` in its execution graph
- `./gradlew ciCheck` fails if any Screen subclass is missing documentation
- `./gradlew ciCheck` passes when all coverage requirements are met

**Dependencies:** Story 5.1.

---

### Story 5.3: Verify Full CI Pipeline

**What to verify (manual verification tasks):**

Run each failure scenario locally to confirm the CI gate catches it:

**Scenario A — Missing @HelpPage:**
1. Add `data object FakeScreen : Screen()` (no annotation) to `AppState.kt`
2. Run `./gradlew detekt`
3. Expected: Fails with `MissingHelpPageAnnotation` finding pointing to `FakeScreen`
4. Revert the change

**Scenario B — Missing demo-graph page:**
1. Add `@HelpPage(docs = FakeDocs::class) data object FakeScreen : Screen()` and a corresponding
   `FakeDocs` class with `howTo.title = "Fake Feature"`
2. Run `./gradlew jvmTest`
3. Expected: `DemoGraphCoverageTest` fails with "HowTo page missing: 'Fake Feature.md' in demo-graph/pages/"
4. Revert the change

**Scenario C — Missing website docs page:**
1. Add `@HelpPage` + demo-graph page (so Scenario B passes) but no `.mdx` file in `site/`
2. Run `./gradlew jvmTest`
3. Expected: `WebsiteDocsCoverageTest` fails with "Missing: site/src/content/docs/user/fake-feature.mdx"
4. Revert the change

**Acceptance criteria:**
- All three failure scenarios fail at the correct CI gate with a clear, actionable error message
- After reverting each change, `./gradlew ciCheck` passes
- Document the results in a comment on the PR (not in a file)

**Dependencies:** Stories 1.4, 2.5, 4.1, 5.2.

---

## Known Issues

### Potential Bug: @HelpExempt SOURCE Retention Breaks Runtime Audit Count

**Description:** Story 3.1's coverage summary test estimates exempt screens via a `KNOWN_EXEMPT_SCREENS`
hardcoded set rather than runtime reflection, because `@HelpExempt` has `SOURCE` retention and is
invisible at runtime. If a Screen is added with `@HelpExempt` but not added to `KNOWN_EXEMPT_SCREENS`,
it appears in the "unannotated (gap)" count, making the summary misleading.

**Mitigation:**
- The summary test is informational only (always passes) — the gap count does not block CI
- The detekt rule (which reads PSI, not bytecode) correctly detects `@HelpExempt` at lint time
- Keep `KNOWN_EXEMPT_SCREENS` in sync manually; it is a display aid, not an enforcement mechanism

**Files affected:** `DemoGraphCoverageTest.kt`

---

### Potential Bug: Triple-Quote Escaping in Generated DemoFileSystem.kt

**Description:** Markdown files in `demo-graph/` that contain three consecutive double-quotes
(`"""`) — for example, in a code fence documenting Kotlin — will cause the Gradle generation task
to emit malformed Kotlin raw string literals that fail to compile.

**Mitigation:**
- The escape logic in Story 2.2 must handle `"""` by emitting `${"\"\"\""}` at generation time
- Add a test in `DemoFileSystemSyncTest` that asserts the generated file parses as valid Kotlin
  (a lightweight heuristic: the file contains no unescaped `"""` sequences other than the map
  entry delimiters)
- Alternatively, scan all `demo-graph/*.md` files for embedded `"""` before generation and emit
  a `warning` log if found

**Files affected:** `kmp/build.gradle.kts` (generation task), all `demo-graph/**/*.md` files

---

### Potential Bug: Slug Collision Between DiataxisDoc Titles

**Description:** If two `DiataxisDoc` implementations produce the same slug (e.g., "Block Editing"
and "Block editing" both → `block-editing`), `WebsiteDocsCoverageTest` would pass for both even
though only one file exists, and one Screen would go undocumented.

**Mitigation:**
- Slugs are derived from `reference.title` (capitalized consistently by convention); collisions
  are unlikely in practice
- `WebsiteDocsCoverageTest` checks for each Screen independently using its specific doc class;
  a collision would cause one test entry to pass and the other to share the file — the silent
  failure is acceptable for the current scope. Add a duplicate-slug assertion if this becomes a
  real concern.

**Files affected:** `WebsiteDocsCoverageTest.kt`

---

### Potential Bug: WebsiteDocsCoverageTest repo root resolution fails in monorepo/composite build

**Description:** The classpath walk-up that locates the repo root (`while dir contains both kmp/
and site/`) assumes a two-level depth. In a composite build or relocated project root, the `kmp/`
and `site/` sibling relationship may not hold.

**Mitigation:**
- The walk-up condition `dir.resolve("kmp").isDirectory && dir.resolve("site").isDirectory`
  is structural and not depth-dependent — it will find the correct root at any depth
- Add a clear error message that includes the starting classpath URL so the misconfiguration is
  diagnosable
- Alternatively, add `systemProperty("stelekit.repo.root", rootProject.projectDir.absolutePath)`
  to the `jvmTest` task in `kmp/build.gradle.kts` as a belt-and-suspenders fallback

**Files affected:** `WebsiteDocsCoverageTest.kt`, optionally `kmp/build.gradle.kts`

---

## Architecture Decisions Requiring ADR Recording

The following decisions made in this plan are sufficiently impactful and non-obvious to warrant
formal ADR documentation:

**ADR-1: @HelpExempt uses SOURCE retention, not RUNTIME**

Decision: `@Retention(AnnotationRetention.SOURCE)` on `@HelpExempt`. Consequence: exempt screens
are invisible to JVM reflection and cannot be counted or scanned at test time. The coverage summary
test uses a manually maintained `KNOWN_EXEMPT_SCREENS` set as a workaround. The enforcement
mechanism (detekt PSI) does not require runtime retention and is unaffected.

Rationale to document: Anyone adding a runtime-reflection-based audit of exempt screens will be
surprised that `@HelpExempt` is invisible. The ADR should record why SOURCE was chosen
(enforcement is build-time only; avoiding runtime annotation payload on production classes) and
what the trade-off is (runtime audit requires a separate list).

**ADR-2: DemoFileSystem.kt is generated into wasmJsMain source tree, not build/generated/**

Decision: The task output path is `kmp/src/wasmJsMain/kotlin/.../DemoFileSystem.kt` (source tree,
gitignored) rather than the Gradle convention of `kmp/build/generated/...`. Consequence: IDEs see
the file as a regular source file (autocomplete, navigation work), and the Gradle source set does
not need `srcDir(...)` wiring. Trade-off: The file is in the source tree and therefore visible in
file explorers, which may confuse contributors who try to edit it manually.

Rationale to document: The build/generated/ approach requires wiring the generated dir as a source
set via `kotlin.sourceSets["wasmJsMain"].kotlin.srcDir(...)`, which has caused configuration cache
issues in this project with the existing sqlite-wasm copy task. The source tree approach is simpler
and produces a better IDE experience at the cost of a gitignore entry.

---

## Summary

| Epic | Stories | Tasks |
|---|---|---|
| Epic 1: Annotation infrastructure | 4 | 1.1 Create HelpExempt.kt, 1.2 Create detekt rule, 1.3 Create rule tests, 1.4 Wire detekt.yml |
| Epic 2: DemoFileSystem auto-generation | 5 | 2.1 welcome-journal.md, 2.2 Gradle task, 2.3 Wire compileKotlinWasmJs, 2.4 gitignore, 2.5 DemoFileSystemSyncTest |
| Epic 3: Demo-graph coverage | 4 | 3.1 Extend DemoGraphCoverageTest, 3.2 Annotate Screen entries, 3.3 DiataxisDoc implementations, 3.4 Verify demo-graph pages |
| Epic 4: Website docs coverage | 4 | 4.1 WebsiteDocsCoverageTest, 4.2 Verify directory, 4.3 Write 5 .mdx files, 4.4 Update sidebar |
| Epic 5: CI wiring | 3 | 5.1 checkDocCoverage task, 5.2 Add to ciCheck, 5.3 Verify pipeline |
| **Total** | **20 stories/tasks** | — |

**5 epics, 20 implementation tasks across 5 stories per epic.**

**2 architecture decisions require ADR recording:**
1. `@HelpExempt` SOURCE retention vs. RUNTIME — implications for reflection-based auditing
2. Generated `DemoFileSystem.kt` in wasmJsMain source tree vs. `build/generated/` — IDE vs. Gradle idiom trade-off
