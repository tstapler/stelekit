# Documentation Coverage Enforcement — Architecture

## 1. MissingHelpPageAnnotation Detekt Rule

### PSI Node Visitor Strategy

`Screen` is a `sealed class` with nested `data object` and `data class` members. The correct
visitor override is `visitClassOrObject`, which fires on **both** `KtClass` (for `data class`) and
`KtObjectDeclaration` (for `data object`). Overriding only `visitClass` misses all `data object`
declarations — which represent most navigation destinations.

The detection algorithm:

1. For every `KtClassOrObject` node, walk up the PSI parent chain.
2. A node is a **Screen member** if its immediate containing class or object (via
   `containingClassOrObject`) has the simple name `"Screen"` **and** `Screen` is itself a `sealed`
   class (`KtClass.isSealed() == true`). The single parent check is sufficient because the
   requirements scope only direct children; deeply-nested types inside a Screen subclass (e.g.
   an inner state class) should not be flagged.
3. If the node is a Screen member and carries **neither** `@HelpPage` **nor** `@HelpExempt`, fire
   a `CodeSmell`.

Checking for annotation absence: iterate `annotationEntries` and collect `shortName?.asString()`
values. If neither `"HelpPage"` nor `"HelpExempt"` appears in that set, the rule fires.

### Skeleton Rule

```kotlin
// buildSrc/src/main/kotlin/dev/stapler/detekt/MissingHelpPageAnnotationRule.kt
package dev.stapler.detekt

import io.gitlab.arturbosch.detekt.api.*
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

class MissingHelpPageAnnotationRule(config: Config = Config.empty) : Rule(config) {

    override val issue = Issue(
        id = "MissingHelpPageAnnotation",
        severity = Severity.Defect,
        description = "Every Screen subclass must carry @HelpPage. " +
            "Add @HelpExempt for internal/diagnostic screens.",
        debt = Debt.TEN_MINS,
    )

    override fun visitClassOrObject(classOrObject: KtClassOrObject) {
        super.visitClassOrObject(classOrObject)

        // Walk one level up to find the containing class.
        val parent = classOrObject.containingClassOrObject ?: return

        // Only care about direct children of `Screen`.
        if (parent.name != "Screen") return

        // Verify the parent is a sealed class (guards against coincidental name collisions).
        if (parent !is KtClass || !parent.isSealed()) return

        val annotations = classOrObject.annotationEntries
            .mapNotNull { it.shortName?.asString() }
            .toSet()

        if ("HelpPage" !in annotations && "HelpExempt" !in annotations) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(classOrObject),
                    "Screen.${classOrObject.name} is missing @HelpPage. " +
                        "Annotate with @HelpPage(docs = YourDocs::class) or " +
                        "@HelpExempt if this is an internal/diagnostic screen.",
                )
            )
        }
    }
}
```

Register in `SteleKitRuleSetProvider` alongside existing rules.

### detekt.yml entry

```yaml
stelekit:
  MissingHelpPageAnnotation:
    active: true
    excludes: ['**/buildSrc/**', '**/*Test.kt', '**/test/**']
```

No per-file file-path filtering is needed beyond the standard test exclusion because the rule's
parent-name check already limits it to nodes inside `Screen`.

---

## 2. @HelpExempt Annotation Design

### Annotation declaration

Declare `@HelpExempt` next to `@HelpPage` in `commonMain/docs/`:

```kotlin
// kmp/src/commonMain/kotlin/dev/stapler/stelekit/docs/HelpExempt.kt
package dev.stapler.stelekit.docs

/**
 * Exempts a Screen subclass from the @HelpPage requirement.
 *
 * Use for internal/diagnostic screens that are never user-initiated entry points
 * (e.g. debug menu, conflict resolution sub-steps, annotation editor opened only
 * from an image block). The reason parameter is required to make exemptions
 * intentional and reviewable.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)   // Not needed at runtime; BINARY would also work.
annotation class HelpExempt(val reason: String)
```

`@Retention(SOURCE)` is correct here: the exemption is a build-time signal only. The detekt rule
reads PSI text — it never needs the annotation at runtime. This avoids the annotation appearing
in reflection-based tests such as `DemoGraphCoverageTest`.

### Class vs. config-set trade-off

Use the annotation on the class (not an `exemptScreens` list in `detekt.yml`) because:
- A config list decouples the exemption from the code; someone can remove the Screen class without
  cleaning up the config, leaving a stale entry.
- The annotation is co-located with the class, so code review of the Screen addition also reveals
  the exemption rationale.
- The `reason` parameter creates an inline audit trail (`@HelpExempt("opened only from image block,
  not from sidebar nav")`).

If a future need arises to exempt entire files from the rule, the standard detekt `excludes`
path-glob in `detekt.yml` is the correct mechanism — not a custom list.

---

## 3. generateDemoFileSystem Gradle Task

### Design

The task reads `*.md` files from two source directories, encodes their content as Kotlin triple-
quoted string literals, and writes a single `DemoFileSystem.kt` file. Up-to-date checking uses
Gradle's `@InputFiles` / `@OutputFile` incremental API so the task is a no-op when no `.md`
files have changed since the last build.

The "today's journal" problem is resolved at **generation time**: the task substitutes the current
build date (via `LocalDate.now()` in the Gradle JVM, which is always the JVM target — fine for a
build-time value) into a `{DATE}` and `{YEAR}`, `{MONTH}`, `{DAY}` placeholder set in a template
file `demo-graph/welcome-journal.md`. This journal is written as a static file embedded with the
correct date strings; `DemoFileSystem.kt` at runtime contains no date logic at all, eliminating
the current `today` property and `journalFileName` construction.

```kotlin
// buildSrc/src/main/kotlin/dev/stapler/GenerateDemoFileSystemTask.kt
package dev.stapler

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

abstract class GenerateDemoFileSystemTask : DefaultTask() {

    @get:InputDirectory
    abstract val demoGraphDir: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val graphDir = demoGraphDir.asFile.get()
        val today = LocalDate.now()
        val dateStr = today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val fileNameDate = today.format(DateTimeFormatter.ofPattern("yyyy_MM_dd"))

        val entries = mutableListOf<Pair<String, String>>()

        // Collect pages
        val pagesDir = graphDir.resolve("pages")
        pagesDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.name }
            .forEach { file ->
                entries.add("pages/${file.name}" to file.readText())
            }

        // Collect journals (static, pre-dated)
        val journalsDir = graphDir.resolve("journals")
        journalsDir.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .sortedBy { it.name }
            .forEach { file ->
                entries.add("journals/${file.name}" to file.readText())
            }

        // Inject today's journal from template
        val templateFile = graphDir.resolve("welcome-journal.md")
        if (templateFile.exists()) {
            val content = templateFile.readText()
                .replace("{DATE}", dateStr)
                .replace("{YEAR}", today.year.toString())
                .replace("{MONTH}", today.monthValue.toString().padStart(2, '0'))
                .replace("{DAY}", today.dayOfMonth.toString().padStart(2, '0'))
            // Replace any existing journal for today (or add if absent)
            val todayKey = "journals/${fileNameDate}.md"
            val existingIndex = entries.indexOfFirst { it.first == todayKey }
            if (existingIndex >= 0) entries[existingIndex] = todayKey to content
            else entries.add(todayKey to content)
        }

        val mapEntries = entries.joinToString(",\n") { (key, value) ->
            val escaped = value.replace("$", "\${'$'}")
            "        \"$key\" to \"\"\"\n$escaped\"\"\".trimIndent()"
        }

        val code = """
            // GENERATED — do not edit. Run :kmp:generateDemoFileSystem to regenerate.
            // Source: kmp/src/commonMain/resources/demo-graph/
            package dev.stapler.stelekit.platform

            class DemoFileSystem : FileSystem {
                private val overrides = mutableMapOf<String, String>()

                val demoFiles: Map<String, String> = mapOf(
            $mapEntries
                )

                // FileSystem implementation methods follow (copied from manual version) ...
            }
        """.trimIndent()

        outputFile.asFile.get().apply {
            parentFile.mkdirs()
            writeText(code)
        }
    }
}
```

### Registration in kmp/build.gradle.kts

```kotlin
val generateDemoFileSystem = tasks.register<GenerateDemoFileSystemTask>("generateDemoFileSystem") {
    demoGraphDir.set(layout.projectDirectory.dir("src/commonMain/resources/demo-graph"))
    outputFile.set(
        layout.projectDirectory.file(
            "src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt"
        )
    )
}

tasks.named("compileKotlinWasmJs") {
    dependsOn(generateDemoFileSystem)
}
```

### .gitignore

Add to `kmp/.gitignore` (or the repo root `.gitignore` with the correct path):

```
kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/DemoFileSystem.kt
```

The path is deterministic; the pattern above works with a repo-root `.gitignore`.

### Generated file shape

```kotlin
// GENERATED — do not edit. Run :kmp:generateDemoFileSystem to regenerate.
package dev.stapler.stelekit.platform

class DemoFileSystem : FileSystem {
    private val overrides = mutableMapOf<String, String>()

    val demoFiles: Map<String, String> = mapOf(
        "journals/2026_06_19.md" to """
            - date:: 2026-06-19
            ...
        """.trimIndent(),
        "pages/All Pages.md" to """
            ...
        """.trimIndent(),
        // ... one entry per .md file
    )

    // FileSystem interface methods unchanged from manual version
}
```

Dollar signs in markdown content must be escaped as `${'$'}` to prevent Kotlin string template
interpolation inside triple-quoted strings.

---

## 4. WebsiteDocsCoverageTest Repository Root Resolution

### The problem

`jvmTest` tasks run with a working directory of `kmp/` (or wherever Gradle sets the CWD), not the
repository root. `site/` is a sibling of `kmp/`, so a simple `File("site/")` relative path will
not resolve. The test classpath does not include `site/`.

### Solution: Walk up from the resource URL

The test classpath **does** include `demo-graph/` (loaded via `classLoader.getResource`). Use the
same anchor that `DemoGraphCoverageTest` already uses and walk up:

```kotlin
private val repoRoot: File by lazy {
    // demo-graph/pages is on the classpath as a resource directory.
    // Its URL resolves to something like:
    //   .../kmp/build/processedResources/jvm/main/demo-graph/pages
    // Walk upward past processedResources to the kmp/ directory, then one more level to repo root.
    val demoGraphUrl = javaClass.classLoader.getResource("demo-graph/pages")
        ?: fail("demo-graph/pages not found on classpath")
    var dir = File(demoGraphUrl.toURI())
    // Walk up until we find a directory that contains both "kmp" and "site" as children.
    while (dir != dir.parentFile) {
        if (dir.resolve("kmp").isDirectory && dir.resolve("site").isDirectory) return@lazy dir
        dir = dir.parentFile
    }
    fail("Could not locate repository root from classpath anchor $demoGraphUrl")
}

private val siteDocsDir: File by lazy {
    repoRoot.resolve("site/src/pages/docs")
}
```

This approach is robust to Gradle changing the `processedResources` output path because it only
relies on the structural property that `kmp/` and `site/` are siblings of the repo root.

**Alternative (simpler, but brittle)**: Use `System.getProperty("user.dir")` which Gradle sets to
the subproject directory (`kmp/`), then resolve `../site/`. This works but breaks if someone runs
the test from a different working directory or in a composite build with a relocated project root.
The classpath-anchor walk-up approach is safer.

### Test skeleton

```kotlin
class WebsiteDocsCoverageTest {

    private val repoRoot: File by lazy { /* walk-up logic above */ }
    private val siteDocsDir: File by lazy { repoRoot.resolve("site/src/pages/docs") }

    private fun findAnnotatedScreens(): List<Pair<String, HelpPage>> {
        val screenClass = Screen::class.java
        return screenClass.declaredClasses
            .mapNotNull { it.getAnnotation(HelpPage::class.java)?.let { ann -> it.simpleName to ann } }
    }

    private fun slugFor(title: String): String =
        title.lowercase().replace(' ', '-').replace(Regex("[^a-z0-9-]"), "")

    @Test
    fun `every HelpPage-annotated Screen has a docs page on the website`() {
        assertTrue(siteDocsDir.isDirectory, "site/src/pages/docs/ not found at $siteDocsDir")

        val missing = mutableListOf<String>()
        for ((className, annotation) in findAnnotatedScreens()) {
            val docs = annotation.docs.java.getDeclaredConstructor().newInstance() as DiataxisDoc
            val title = (docs as? HowToDoc)?.howTo?.title ?: continue
            val slug = slugFor(title)
            val astro = siteDocsDir.resolve("$slug.astro")
            val mdx   = siteDocsDir.resolve("$slug.mdx")
            when {
                !astro.exists() && !mdx.exists() ->
                    missing += "[$className] Missing site/src/pages/docs/$slug.astro (or .mdx)"
                astro.exists() && astro.readText().isBlank() ->
                    missing += "[$className] site/src/pages/docs/$slug.astro is empty"
                mdx.exists()  && mdx.readText().isBlank() ->
                    missing += "[$className] site/src/pages/docs/$slug.mdx is empty"
            }
        }
        assertTrue(missing.isEmpty(), "Website docs missing:\n${missing.joinToString("\n")}")
    }
}
```

---

## 5. Integration Sequence and Dependency Order

### Full dependency graph

```
demo-graph/*.md (source files)
        │
        ▼
generateDemoFileSystem (Gradle task, kmp/build.gradle.kts)
        │
        ▼
compileKotlinWasmJs ──────────────────────────────────────────┐
                                                              │
                                              wasmJs binary + DemoFileSystem.kt
```

```
Screen.kt + @HelpPage annotations
        │
        ├──▶ detekt (MissingHelpPageAnnotation rule)          — catches unannotated Screen
        │         runs as part of :kmp:detekt → ciCheck
        │
        ├──▶ DemoGraphCoverageTest (jvmTest)                  — catches missing demo-graph pages
        │         reads demo-graph/pages/ directly from classpath resources
        │         does NOT depend on generateDemoFileSystem (reads .md source, not generated .kt)
        │
        ├──▶ WebsiteDocsCoverageTest (jvmTest)                — catches missing site/docs pages
        │         reads site/src/pages/docs/ via repo-root walk-up from classpath anchor
        │         no compile dependency; pure filesystem check
        │
        └──▶ DemoFileSystemSyncTest (jvmTest, extension of DemoGraphCoverageTest)
                  verifies every demo-graph/pages/*.md appears as a key in generated DemoFileSystem.kt
                  DOES depend on generateDemoFileSystem having run
                  wire-up: tasks.named("jvmTest") { dependsOn(generateDemoFileSystem) }
```

### ciCheck dependency additions

```kotlin
// In root build.gradle.kts or kmp/build.gradle.kts:
tasks.register("checkDocCoverage") {
    group = "verification"
    description = "Assert every Screen has demo-graph page, website /docs page, and DemoFileSystem sync"
    dependsOn(":kmp:generateDemoFileSystem", ":kmp:jvmTest")
}

tasks.named("ciCheck") {
    dependsOn(":kmp:checkDocCoverage")
}

// Ensure jvmTest sees the generated file (for DemoFileSystemSyncTest)
tasks.named("jvmTest") {
    dependsOn(":kmp:generateDemoFileSystem")
}
```

`DemoGraphCoverageTest` and `WebsiteDocsCoverageTest` run inside `jvmTest` automatically (they are
ordinary `@Test` functions). They do not need an explicit `dependsOn` on `generateDemoFileSystem`
because they read source `.md` files and the `site/` directory, not the generated Kotlin file.
Only `DemoFileSystemSyncTest` needs the generated output.

### Enforcement point summary

| Failure scenario | Caught by | CI gate |
|---|---|---|
| New Screen subclass without @HelpPage | `MissingHelpPageAnnotation` detekt rule | `:kmp:detekt` |
| New Screen with @HelpPage but no demo-graph page | `DemoGraphCoverageTest` | `:kmp:jvmTest` |
| New Screen with @HelpPage but no site/docs page | `WebsiteDocsCoverageTest` | `:kmp:jvmTest` |
| demo-graph .md edited but DemoFileSystem.kt out of sync | `DemoFileSystemSyncTest` + Gradle incremental check | `generateDemoFileSystem` + `:kmp:jvmTest` |
| All of the above | `checkDocCoverage` | `ciCheck` |

### Key design decisions

**`DemoGraphCoverageTest` reads classpath resources, not the generated file.** This avoids a
circular dependency: the test validates that source `.md` files exist, which is independent of the
Gradle code-generation step. The code generation step is validated separately by
`DemoFileSystemSyncTest`, which reads the generated `.kt` file as text and checks for key strings.

**`@HelpExempt` uses `@Retention(SOURCE)`.** The annotation is only needed by the detekt PSI
visitor at compile time. It does not appear in `DemoGraphCoverageTest`'s reflection scan (which
filters by `@HelpPage`), so there is no risk of exempt screens being spuriously included in
coverage checks.

**The detekt rule uses `visitClassOrObject`, not `visitClass`.** `Screen` nesting uses `data object`
(which is a `KtObjectDeclaration`, not a `KtClass`). A `visitClass`-only approach would miss every
`data object Journals : Screen()` entry — i.e., most existing Screen destinations.
