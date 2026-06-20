# Doc Coverage Enforcement — Stack Research

## 1. Detekt custom rule for `@HelpPage` absence

**Verdict: Use PSI visitor on `KtClass`, identical to `ClassLevelDirectRepositoryWriteOptInRule`.**

The rule needs to visit every nested class inside `sealed class Screen` and flag those missing `@HelpPage`. Looking at the existing rules:

- `ClassLevelDirectRepositoryWriteOptInRule` overrides `visitClass(klass: KtClass)` and inspects `klass.annotationEntries` — the exact same hook needed here.
- `MissingDirectRepositoryWriteRule` shows the pattern for checking `containingClassOrObject` to scope a rule to a specific parent type.
- The check for annotation absence follows `ClassLevelDirectRepositoryWriteOptInRule` line 73: `klass.annotationEntries.any { it.shortName?.asString() == "HelpPage" }`.

**Concrete implementation pattern:**

```kotlin
class MissingHelpPageAnnotationRule(config: Config = Config.empty) : Rule(config) {
    override val issue = Issue(
        id = "MissingHelpPageAnnotation",
        severity = Severity.Warning,
        description = "User-facing Screen subclasses must carry @HelpPage pointing to a DiataxisDoc.",
        debt = Debt.TEN_MINS,
    )

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        // Only nested classes (has a containing class) whose parent is Screen
        val parent = klass.containingClassOrObject as? KtClass ?: return
        if (parent.name != "Screen") return
        // Internal/infra screens to exempt:
        val exemptNames = setOf("VaultUnlock", "AnnotationEditor", "Import", "Logs",
                                "Notifications", "Performance")
        if (klass.name in exemptNames) return

        val hasHelpPage = klass.annotationEntries.any { it.shortName?.asString() == "HelpPage" }
        if (!hasHelpPage) {
            report(CodeSmell(issue, Entity.from(klass),
                "${klass.name} is a user-facing Screen but lacks @HelpPage. " +
                "Add @HelpPage(docs = SomeDocs::class) or add it to the exempt list."))
        }
    }
}
```

**Note:** `containingClassOrObject` is imported from `org.jetbrains.kotlin.psi.psiUtil` — same import already used in `MissingDirectRepositoryWriteRule` line 13.

Register the rule in `SteleKitRuleSetProvider.instance()` exactly like the 14 existing rules.

**KSP vs PSI:** PSI is the right choice. KSP runs at compile time and produces its own compilation round; Detekt runs PSI analysis without touching the Kotlin compiler. Adding KSP just for a lint rule would add a processor, a new Gradle plugin, and a generated-sources dependency — all of which KSP annotation processing then needs to be wired into the KMP module's wasmJs/jvm/android/ios targets separately. PSI rules are simpler to write, already on the classpath in `buildSrc`, and run during the existing `detekt` task that is already in `ciCheck`. No trade-off worth making.

---

## 2. Gradle task for file generation (`generateDemoFileSystem`)

**Verdict: Declare a `tasks.register<DefaultTask>` with `@InputDirectory` / `@OutputFile` using the lazy configuration API; wire it as a dependency of `compileKotlinWasmJs`.**

The project already uses this pattern — see lines 354–370 of `kmp/build.gradle.kts` for a `doLast` action on `wasmJsBrowserDistribution` that copies sqlite-wasm files. The idiomatic Gradle 9 approach with proper up-to-date checking is slightly different: declare inputs/outputs at task registration time (not in `doLast`) so Gradle's incremental build can skip the task when inputs are unchanged.

**Concrete wiring (inside `kmp/build.gradle.kts`, alongside existing task blocks):**

```kotlin
// ── Demo filesystem generator ─────────────────────────────────────────────────
// Reads: kmp/src/commonMain/resources/demo-graph/**
// Writes: kmp/build/generated/demoGraph/DemoFileSystem.kt
// Up-to-date: Gradle skips if no .md file in demo-graph changed since last run.

abstract class GenerateDemoFileSystemTask : DefaultTask() {
    @get:InputDirectory
    abstract val demoGraphDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val dir = demoGraphDir.get().asFile
        val entries = dir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .map { it.relativeTo(dir).path to it.readText() }
            .toList()

        val code = buildString {
            appendLine("// AUTO-GENERATED — do not edit. Source: demo-graph/**")
            appendLine("package dev.stapler.stelekit.demo")
            appendLine("val demoFileSystem: Map<String, String> = mapOf(")
            entries.forEach { (path, content) ->
                val escaped = content.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"")
                appendLine("  \"$path\" to \"\"\"$escaped\"\"\",")
            }
            appendLine(")")
        }
        outputFile.get().asFile.also { it.parentFile.mkdirs() }.writeText(code)
    }
}

val generateDemoFileSystem by tasks.registering(GenerateDemoFileSystemTask::class) {
    demoGraphDir.set(layout.projectDirectory.dir("src/commonMain/resources/demo-graph"))
    outputFile.set(layout.buildDirectory.file("generated/demoGraph/DemoFileSystem.kt"))
}

// Make compileKotlinWasmJs depend on the generator
if (project.findProperty("enableJs") == "true") {
    afterEvaluate {
        tasks.named("compileKotlinWasmJs") {
            dependsOn(generateDemoFileSystem)
        }
    }
}

// Wire the generated source directory into wasmJs compilation
kotlin {
    sourceSets {
        if (project.findProperty("enableJs") == "true") {
            val wasmJsMain by getting {
                kotlin.srcDir(generateDemoFileSystem.map { it.outputFile.get().asFile.parentFile })
            }
        }
    }
}
```

**Up-to-date checking:** Because `demoGraphDir` is declared `@InputDirectory`, Gradle fingerprints every file in `demo-graph/` at task-end. If no `.md` file changes between runs, the task is `UP-TO-DATE` and skipped. This is built into Gradle's incremental build — no custom `outputs.upToDateWhen` needed.

**Configuration cache compatibility:** The task uses only `DirectoryProperty` / `RegularFileProperty` (value objects, not `project.file()` calls in `doLast`) so it is fully configuration-cache safe. Unlike the existing sqlite-wasm copy (which uses `project.copy()` in `doLast` and explicitly marks itself `notCompatibleWithConfigurationCache`), this approach works with `--configuration-cache`.

**Alternative with `afterEvaluate`:** The `afterEvaluate { tasks.named("compileKotlinWasmJs") }` guard matches the project's existing pattern (lines 354–369). On Gradle 9 you can also use `tasks.matching { it.name == "compileKotlinWasmJs" }.configureEach { dependsOn(...) }` to avoid `afterEvaluate`, but both work.

---

## 3. Astro docs section structure

**Verdict: No `/docs` subdirectory under `site/src/pages/`. Docs live in `site/src/content/docs/` as `.mdx` files served by Astro Starlight. A new help page is a `.mdx` file in `site/src/content/docs/user/` or `site/src/content/docs/developer/`.**

**Actual layout discovered:**

```
site/src/pages/
  index.astro      ← marketing landing page (raw HTML, no Starlight layout)
  demo.astro       ← WebAssembly demo page

site/src/content/docs/
  user/
    getting-started.mdx
    outliner.mdx
    journals.mdx
    backlinks.mdx
    search.mdx
  developer/
    architecture.mdx
    build.mdx
    contributing.mdx
    module-structure.mdx
```

Starlight is configured in `site/astro.config.mjs` with a sidebar listing the existing pages. A new docs page requires two changes:

1. Create `site/src/content/docs/user/<slug>.mdx` with frontmatter:
   ```mdx
   ---
   title: Page Linking
   description: How [[wikilinks]] work in SteleKit
   ---

   ## Overview
   ...
   ```

2. Add the slug to the `sidebar` array in `astro.config.mjs`:
   ```js
   { label: 'Page Linking', slug: 'user/page-linking' },
   ```

The `content.config.ts` uses Starlight's `docsLoader()` and `docsSchema()`, so the only required frontmatter fields are `title` and (optional) `description`. No layout import needed — Starlight wraps everything automatically.

**Format:** All existing docs use `.mdx` (not `.md` or `.astro`). Use `.mdx` for new pages.

**URL structure:** A file at `site/src/content/docs/user/page-linking.mdx` is served at `/user/page-linking/`. The landing page links to `/user/getting-started/` — same slug convention.

---

## 4. KSP vs reflection vs PSI for annotation scanning

**Verdict: Keep reflection for the runtime test (`DemoGraphCoverageTest`). Use PSI for the new Detekt lint rule. Do not introduce KSP.**

| Mechanism | When it runs | Finds missing annotations? | Effort |
|---|---|---|---|
| **JVM reflection** (existing) | `jvmTest` at test time | Yes — scans `Screen.declaredClasses` | Already implemented |
| **PSI / Detekt rule** (recommended) | `detekt` during `ciCheck`, pre-compile | Yes — earlier feedback | 30–50 lines, same pattern as existing rules |
| **KSP** | Kotlin compilation round, before `jvmTest` | Yes — generates code or reports | Requires new Gradle plugin, KMP target configs, processor module |

**Why not KSP:** KSP requires a separate `processor` module, registration in each KMP target's compilation (jvm, android, wasmJs, iosX64, iosArm64, iosSimulatorArm64), and a `build.gradle.kts` block per target. For a rule that reports a warning, this is 10× the complexity of a Detekt PSI rule. KSP is the right tool when you need to *generate* code from annotations (e.g., building a lookup table at compile time); for *enforcement* (report if annotation absent), Detekt PSI is the project's established pattern.

**Why keep reflection for `DemoGraphCoverageTest`:** The reflection test does something PSI cannot: it instantiates the `DiataxisDoc` object and checks that the referenced `.md` file actually exists in the bundled demo graph at test time. That is a runtime check that requires the classpath, not a structural check on source. Keep both layers — Detekt rule catches the annotation absence early (author time), reflection test catches the docs file being absent or empty (CI time).

**Tradeoff summary:**
- Detekt PSI rule: catches missing `@HelpPage` at lint time, before compilation, with a clear error message pointing to the offending class. Zero new dependencies.
- Reflection test (existing): catches `@HelpPage` pointing to a missing or empty demo-graph page. Runs in `jvmTest`.
- Together they form a complete two-layer net: structural correctness at lint time, content correctness at test time.
