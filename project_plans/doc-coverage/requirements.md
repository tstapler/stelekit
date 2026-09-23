# Documentation Coverage Enforcement — Requirements

## Problem Statement

SteleKit currently has no enforcement mechanism that prevents a new screen or user-facing feature from shipping without documentation. The `@HelpPage` annotation system exists (with `DiataxisDoc`, `HowToDoc`, `ReferenceDoc`) and `DemoGraphCoverageTest` is wired up, but **zero `Screen` subclasses are annotated** — the test passes vacuously. Additionally, `DemoFileSystem.kt` (web demo content) is manually maintained separately from the JVM resource files in `demo-graph/`, creating a persistent dual-maintenance burden.

The goal: make it structurally impossible to ship a new feature without (a) a demo-graph page, (b) a website /docs page, and (c) DemoFileSystem staying in sync automatically.

---

## Existing Infrastructure (do not redesign)

- `@HelpPage(docs = KClass<DiataxisDoc>)` — annotation targeting `AnnotationTarget.CLASS`
- `DiataxisDoc`, `HowToDoc`, `ReferenceDoc`, `MinimalFeatureDoc` — Diataxis content interfaces in `docs/`
- `DemoGraphCoverageTest` — jvmTest that reflectively finds `@HelpPage`-annotated `Screen` subclasses and asserts corresponding `.md` files exist in `demo-graph/pages/`
- `demo-graph/` — resource directory with 21 markdown files (16 pages + 5 journals)
- `DemoFileSystem.kt` — wasmJsMain in-memory filesystem with hardcoded Kotlin string content (the dual-maintenance problem)
- `site/src/pages/` — Astro site with `/docs` section already present

---

## Requirements

### R1 — @HelpPage annotation required on every user-facing Screen

Every `sealed class`/`data class`/`data object` nested inside `Screen` that represents a user-navigable destination must be annotated with `@HelpPage`. Unannotated Screen subclasses fail the build.

**Enforcement**: New detekt custom rule `MissingHelpPageAnnotation` in `buildSrc/`:
- Scans classes that are direct/nested members of `Screen`
- Fires on any that do not carry `@HelpPage`
- Exemption: `@HelpExempt` annotation for internal/diagnostic screens (e.g. debug menu, conflict resolution sub-step) that are never user-initiated entry points
- Active in `kmp/config/detekt/detekt.yml` — therefore caught by the `Lint (Detekt)` CI job on every PR

**Scope**: `Screen.kt` in `ui/`; covers navigation destinations, not sub-composables.

---

### R2 — @HelpElement annotation for sub-screen interactive elements

A new `@HelpElement` annotation marks individual UI interactions that users can discover and trigger — buttons, toolbar actions, settings toggles, menu items — at a finer granularity than a full screen.

```kotlin
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class HelpElement(val docs: KClass<out DiataxisDoc>)
```

**Applies to**: Composable functions or companion objects representing:
- Toolbar buttons (attach image, undo, redo, format actions)
- Settings page options (left-handed mode, theme, language, reindex)
- Sidebar items (All Pages, Git Sync, Flashcards)
- Inline editor actions (block menu, suggest link, link picker)

**Enforcement**: No compile-time enforcement in the first phase — `@HelpElement` is opt-in for now. A second detekt rule (`MissingHelpElementAnnotation`) can be enabled per-file as elements are documented, preventing regression.

---

### R3 — DemoFileSystem auto-generated from demo-graph/ resource files

The current `DemoFileSystem.kt` (~430 lines, manually maintained) duplicates content from `demo-graph/` resources. A Gradle `generateDemoFileSystem` task replaces it.

**What the task does**:
1. Reads every `.md` file under `kmp/src/commonMain/resources/demo-graph/journals/` and `demo-graph/pages/`
2. Emits a generated `DemoFileSystem.kt` into `kmp/src/wasmJsMain/kotlin/.../platform/DemoFileSystem.kt` with the file content embedded as raw Kotlin string literals
3. The journal list is static (all bundled journals embedded); today's journal entry is seeded separately via a small runtime override that replaces the most recent journal file with a date-substituted version of a `welcome.md` template

**Wire-up**:
- Task declared in `kmp/build.gradle.kts` and added to `tasks.named("compileKotlinWasmJs").configure { dependsOn(generateDemoFileSystem) }`
- The generated file is **not checked into git** (added to `.gitignore`); it is always regenerated at build time
- `DemoGraphCoverageTest` extended to also assert that every `.md` file in `demo-graph/pages/` appears in the generated file (i.e., the Gradle task is correct)

**Today's journal**: A `demo-graph/welcome.md` template uses `{DATE}` placeholder. The Gradle task writes a generated journal entry for the build date. At runtime (WASM), the file already contains the correct date — no runtime substitution needed.

---

### R4 — Demo-graph coverage test extended to DemoFileSystem

Extend `DemoGraphCoverageTest` (jvmTest) with two new assertions:

1. **Resource completeness**: Every `@HelpPage`-annotated Screen's `HowToDoc.howTo.title` and `ReferenceDoc.reference.title` must have a non-empty corresponding file in `demo-graph/pages/`.

2. **DemoFileSystem sync**: After generating `DemoFileSystem.kt`, every file in `demo-graph/pages/` must appear in the generated `demoFiles` map. This catches any bug in the Gradle generation task.

The second assertion requires the test to read the generated `DemoFileSystem.kt` source file (as text, looking for key strings) OR to run the Gradle task as part of the test setup.

---

### R5 — Website /docs coverage CI gate

A new jvmTest `WebsiteDocsCoverageTest`:

```
For every @HelpPage-annotated Screen class:
  - Derive the slug from DiataxisDoc.howTo.title (lowercase, spaces → hyphens)
    Fall back to reference.title only if the doc does not implement HowToDoc.
  - Assert that site/src/content/docs/user/<slug>.mdx exists
  - Assert the file is non-empty
For every @HelpElement-annotated element:
  - Assert the parent screen's /docs page exists (element docs are inlined, not separate pages)
```

The test reads the `site/` directory from the repository root (relative to the test classpath). It does not build the site — just checks for file existence.

**CI**: This test runs in the standard `jvmTest` Gradle task, which is part of `ciCheck`. A missing `/docs` page therefore blocks the PR.

---

### R6 — Annotation all existing user-facing Screens

Once the enforcement rules are in place, annotate all current `Screen` subclasses with `@HelpPage`. This is a one-time catch-up task.

Inventory of Screens to annotate (from `AppState.kt`, where `sealed class Screen` lives):
- `Screen.Journals` → maps to demo-graph "Daily Notes.md" + site/src/content/docs/user/daily-notes.mdx
- `Screen.PageView` → maps to "Block Editing.md" + site/src/content/docs/user/block-editing.mdx
- `Screen.AllPages` → maps to "All Pages.md" + site/src/content/docs/user/all-pages.mdx
- `Screen.Flashcards` → maps to "Flashcards.md" + site/src/content/docs/user/flashcards.mdx
- ... (full list generated during planning from AppState.kt)

Screens that get `@HelpExempt`:
- Debug menu (internal)
- Conflict resolution sub-steps (shown automatically, not user-navigated)
- Annotation editor (advanced; entry from image, not from nav)

---

### R7 — Website /docs pages authored for each Screen

For each Screen annotated in R6, a corresponding `.mdx` page must be written in `site/src/content/docs/user/`. Content is separately authored from the demo-graph page (website docs can include screenshots, full tutorials, platform-specific notes; demo-graph is concise and example-driven).

**Minimum content per /docs page**:
- What this feature is (1–2 sentences)
- How to access it (navigation path or shortcut)
- Key interactions (the What To Do list)
- Keyboard shortcuts relevant to this feature
- Link to related features

---

### R8 — Gradle verification task for full doc coverage

A single `./gradlew checkDocCoverage` task that:
1. Runs `DemoGraphCoverageTest` (demo-graph page exists for every annotated Screen)
2. Runs `WebsiteDocsCoverageTest` (site/docs page exists for every annotated Screen)
3. Runs `generateDemoFileSystem` then verifies the output matches the source

This task is added to `ciCheck` so it runs on every PR.

---

## Out of Scope

- Auto-generating website docs from demo-graph content (docs are separately authored)
- Localization of docs
- Screenshots or video embeds (manual authoring)
- Enforcement on non-Screen composables (too broad; only explicit `@HelpElement` adoption)
- Versioned docs per app release

---

## Success Criteria

1. Adding a new `Screen` subclass without `@HelpPage` fails `./gradlew detekt`
2. Adding `@HelpPage` without a `demo-graph/pages/<title>.md` file fails `./gradlew jvmTest`
3. Adding `@HelpPage` without a `site/src/content/docs/user/<slug>.mdx` file fails `./gradlew jvmTest`
4. Editing any file in `demo-graph/` automatically updates `DemoFileSystem.kt` at next `wasmJs` build — no manual sync needed
5. `./gradlew ciCheck` subsumes all of the above
