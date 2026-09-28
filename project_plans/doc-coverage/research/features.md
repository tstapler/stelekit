# Doc Coverage System — Research Findings

Research for a doc-coverage enforcement system where every `Screen` subclass must carry `@HelpPage`,
CI rejects unannotated screens, a Gradle task generates `DemoFileSystem.kt` from markdown resources,
and docs follow the Diataxis quadrant model.

---

## Android/KMP Annotation Enforcement Patterns

### Key Findings

**Detekt is the right tool — not Kotlin's `@RequiresOptIn` or Android lint.**

`@ExperimentalComposeUiApi` is enforced by the Kotlin compiler's opt-in mechanism (`@RequiresOptIn`),
not by a lint/detekt rule. That model enforces that callers opt in to use an API — it does not enforce
that implementors *add* an annotation. For requiring annotation presence on class hierarchies, detekt
custom rules are the appropriate mechanism.

**The minimal working rule:**

```kotlin
class ScreenMustHaveHelpPage(config: Config) : Rule(
    config,
    "Every Screen subclass must be annotated with @HelpPage.",
) {
    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (klass.isAbstract() || klass.isSealed()) return
        val superTypes = klass.superTypeListEntries.map {
            it.typeAsUserType?.referencedName
        }
        if ("Screen" !in superTypes) return
        val hasHelpPage = klass.annotationEntries.any {
            it.shortName?.asString() == "HelpPage"
        }
        if (!hasHelpPage) {
            report(
                CodeSmell(
                    issue,
                    Entity.from(klass),
                    "${klass.name} extends Screen but is missing @HelpPage annotation."
                )
            )
        }
    }
}
```

**PSI APIs used (no type resolution required):**
- `klass.superTypeListEntries` → list of `KtSuperTypeListEntry`; use `.typeAsUserType?.referencedName` for the short class name
- `klass.annotationEntries` → list of `KtAnnotationEntry`; use `.shortName?.asString()` for the annotation short name
- `klass.isAbstract()` / `klass.isSealed()` → skip the `Screen` base class itself

**Caveat:** Short-name matching works when `Screen` is always the *direct* supertype. If `Screen` subclasses can themselves be subclassed (multi-level hierarchy), the rule needs to walk the chain or use `bindingContext` (requires `RequiresAnalysisApi`). For a sealed `Screen` hierarchy this is never an issue.

**Rule registration:**

```kotlin
// lint-rules/src/main/kotlin/.../SteleKitRuleSetProvider.kt
class SteleKitRuleSetProvider : RuleSetProvider {
    override val ruleSetId = RuleSetId("stelekit-rules")
    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(::ScreenMustHaveHelpPage)
    )
}
```

SPI file at `src/main/resources/META-INF/services/io.gitlab.arturbosch.detekt.api.RuleSetProvider`
containing the provider's fully-qualified class name.

**Real-world precedent:** [`mrmans0n/compose-rules`](https://github.com/mrmans0n/compose-rules) enforces
Compose API conventions via detekt `visitClass` / `visitNamedFunction` rules that check `annotationEntries`
for `@Composable` — the same pattern, in production.

### Recommended Approach for SteleKit

1. Create `lint-rules/` as a `kotlin("jvm")` submodule (no Android deps, even for KMP).
2. Implement `ScreenMustHaveHelpPage` using the pattern above.
3. Wire into `kmp/build.gradle.kts`:
   ```kotlin
   dependencies { detektPlugins(project(":lint-rules")) }
   tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
       dependsOn(":lint-rules:assemble")
   }
   ```
4. Enable in `detekt.yml` (rules are **disabled by default**):
   ```yaml
   stelekit-rules:
     ScreenMustHaveHelpPage:
       active: true
   ```
5. The existing `ciCheck` task already runs detekt; the custom rule fires automatically once enabled.

**Test the rule with `detekt-test`:**

```kotlin
class ScreenMustHaveHelpPageSpec {
    val subject = ScreenMustHaveHelpPage(Config.empty)

    @Test fun `reports Screen subclass without annotation`() {
        assertThat(subject.lint("class SearchScreen : Screen()")).hasSize(1)
    }

    @Test fun `passes annotated Screen subclass`() {
        assertThat(subject.lint("""
            @HelpPage("search") class SearchScreen : Screen()
        """.trimIndent())).isEmpty()
    }
}
```

---

## Gradle Code Generation from Markdown

### Key Findings

**Canonical task structure using `@InputDirectory` / `@OutputFile`:**

```kotlin
// Can be inline in kmp/build.gradle.kts — no buildSrc needed for a single module
val generateDemoFs by tasks.registering {
    val inputDir = layout.projectDirectory.dir("src/commonMain/resources/docs")
    val outputDir = layout.buildDirectory.dir("generated/commonMain/kotlin")
    inputs.dir(inputDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outputDir)

    doLast {
        val root = inputDir.asFile
        val entries = root.walkTopDown()
            .filter { it.isFile && it.extension == "md" }
            .map { file ->
                val key = "/" + file.relativeTo(root).path.replace('\\', '/')
                val content = file.readText().replace("\$", "\${'$'}")
                "        \"$key\" to \"\"\"$content\"\"\""
            }
            .joinToString(",\n")

        val outputFile = outputDir.get().asFile.resolve("DemoFileSystem.kt")
        outputFile.parentFile.mkdirs()
        outputFile.writeText("""
            // GENERATED — do not edit
            package dev.stapler.stelekit

            object DemoFileSystem {
                val files: Map<String, String> = mapOf(
            $entries
                )
            }
        """.trimIndent())
    }
}

kotlin.sourceSets["commonMain"].kotlin.srcDir(
    generateDemoFs.map { it.outputs.files.singleFile }
)
```

**Wiring key:** Passing a **provider** (`generateDemoFs.map { ... }`) rather than a plain path to
`srcDir()` creates an implicit task dependency — Gradle automatically runs `generateDemoFs` before any
compilation task consuming that source set. No explicit `dependsOn` needed.

**Triple-quoted string escaping:** Inside `"""..."""`, only `$` requires escaping:

```kotlin
content.replace("\$", "\${'$'}")
```

This emits `${'$'}` in the generated source, which Kotlin evaluates to `$` at runtime. Backticks, colons,
and standard Markdown characters need no escaping.

**Alternative (avoids all escaping):** Base64-encode content at generation time, decode at runtime.
Adds a `Base64.decode()` call but eliminates all escaping edge cases for Markdown files with heavy
`$variable` or interpolation-like syntax.

**Up-to-date checking:** With `inputs.dir(inputDir).withPathSensitivity(PathSensitivity.RELATIVE)`,
Gradle tracks all files by relative path + content hash. The task is skipped on subsequent builds if
no markdown files changed.

**Generated file location:** `build/generated/commonMain/kotlin/` — conventional path, excluded from
VCS by the default `.gitignore` for Gradle projects, cleaned by `./gradlew clean`.

### Recommended Approach for SteleKit

Use an inline task in `kmp/build.gradle.kts` (not `buildSrc`). `buildSrc` is warranted when:
- The task is shared across multiple subprojects
- It has its own test suite
- It has non-trivial external dependencies

A single-file string-embedding task with no external deps stays inline. The generated output path
`build/generated/commonMain/kotlin/DemoFileSystem.kt` follows Gradle convention and integrates cleanly
with the existing KMP source set structure.

---

## Astro Starlight Conventions

### Key Findings

**Required frontmatter:** Only `title` is required. Everything else is optional.

**Most useful optional fields for SteleKit:**

| Field | Type | Use |
|---|---|---|
| `description` | `string` | SEO meta and search snippet |
| `sidebar.label` | `string` | Override page title in nav |
| `sidebar.order` | `number` | Sort order within autogenerated group (lower = higher) |
| `sidebar.hidden` | `boolean` | Exclude from nav (useful for draft/stub pages) |
| `draft` | `boolean` | Dev-only page; excluded from production build |
| `pagefind` | `boolean` | Set `false` to exclude from built-in search index |
| `tableOfContents` | `false \| {min, max}` | Per-page TOC control |
| `template` | `'doc' \| 'splash'` | `splash` = full-width landing page |

**Extending schema for SteleKit's Diataxis + Screen tracking:**

```typescript
// src/content.config.ts
import { docsSchema } from '@astrojs/starlight/schema';
import { z } from 'astro:schema';

export const collections = {
  docs: defineCollection({
    schema: docsSchema({
      extend: z.object({
        diataxisType: z.enum(['howto', 'reference', 'tutorial', 'explanation']).optional(),
        featureScreen: z.string().optional(), // links back to @HelpPage(slug)
      }),
    }),
  }),
};
```

This causes Starlight's content collection validation to error at build time if `diataxisType` or
`featureScreen` values are malformed — free enforcement at `astro build`.

**Sidebar navigation:**

Autogenerate from directory structure — no manual config needed for new screens:

```javascript
// astro.config.mjs
starlight({
  sidebar: [
    {
      label: 'Features',
      items: [{ autogenerate: { directory: 'features' } }],
    },
  ],
})
```

New `@HelpPage`-annotated screens get a docs entry by adding files to `src/content/docs/features/`.
`sidebar.order` frontmatter controls sort order within the group.

**`.md` vs `.mdx` vs `.astro`:**

- `.md` — default for all HowTo and Reference pages; no component imports
- `.mdx` — use only when a page needs Starlight's built-in components (`<Steps>`, `<Tabs>`, `<Aside>`,
  `<Card>`); no meaningful build cost difference
- `.astro` — custom layouts and landing pages only; not a content format

**Recommended for SteleKit:** `.md` for HowTo + Reference (the initial rollout). `.mdx` for Tutorial
pages that need `<Steps>`. No `.astro` pages needed for feature docs.

**Recommended project structure:**

```
docs/                          # Starlight site root
├── astro.config.mjs
├── src/
│   ├── content.config.ts
│   └── content/
│       └── docs/
│           ├── index.mdx      # Landing page (template: splash)
│           └── features/
│               ├── search/
│               │   ├── howto.md
│               │   └── reference.md
│               └── graph-view/
│                   ├── howto.md
│                   └── reference.md
└── public/
```

**Automated coverage check:** No Starlight plugin exists for this. The pattern used in practice is a
pre-build Node/shell script that reads a manifest (or the `@HelpPage` annotation values from a
generated JSON file) and asserts both `features/{slug}/howto.md` and `features/{slug}/reference.md`
exist. Run as a CI step before `astro build`.

### Recommended Approach for SteleKit

- Use `autogenerate: { directory: 'features' }` so new screens appear automatically when their docs
  folder is added.
- Extend `docsSchema()` with `diataxisType` and `featureScreen` fields — build-time validation is free.
- Keep docs files at `features/{slug}/howto.md` and `features/{slug}/reference.md` to match the
  annotation slug, making coverage checks a simple filesystem existence check.

---

## Annotation Coverage CI Patterns

### Key Findings

**Detekt vs. KSP — choose detekt:**

| Dimension | Detekt | KSP |
|---|---|---|
| Error timing | CI lint (post-compile) | Compile-time error |
| Setup cost | Low — standalone Kotlin JVM module | High — KSP processor + per-target wiring |
| KMP support | Works on source across all platforms | Per-platform KSP wiring required |
| False negatives | Possible if supertype aliased | None (full type resolution) |
| Blocks build? | Only if `failFast: true` in config | Always |

For a sealed `Screen` hierarchy where `Screen` is always the direct supertype, the PSI short-name
check is reliable. KSP adds significant setup overhead with no correctness benefit here.

**Shell fallback (zero dependencies, fragile):**

```bash
missing=$(grep -rn ': Screen' kmp/src --include='*.kt' -l | \
  xargs grep -L '@HelpPage')
if [ -n "$missing" ]; then
  echo "ERROR: Screen subclasses missing @HelpPage:"; echo "$missing"; exit 1
fi
```

Fragile: misses multi-line declarations, false-positives on comments. Use detekt.

**File existence check for doc coverage (pairs with detekt rule):**

After the detekt rule ensures every `Screen` subclass has `@HelpPage(slug = "...")`, a separate
test or CI script verifies the docs files exist:

```kotlin
// businessTest — runs without a device
@Test fun `every @HelpPage slug has both howto and reference docs`() {
    val slugs = HelpPageRegistry.all  // populated at build time or via reflection
    val docsRoot = File("../docs/src/content/docs/features")
    slugs.forEach { slug ->
        assertTrue(File(docsRoot, "$slug/howto.md").exists(),
            "Missing howto doc for @HelpPage(slug = \"$slug\")")
        assertTrue(File(docsRoot, "$slug/reference.md").exists(),
            "Missing reference doc for @HelpPage(slug = \"$slug\")")
    }
}
```

Alternatively, the Gradle `generateDemoFs` task can also emit a coverage assertion task that runs
as part of `check`.

**Android lint `@VisibleForTesting` model (reference):**

Android lint's enforcement of `@VisibleForTesting` works by checking the *call site* — it flags
calls to test-only members from non-test code. This is an access-restriction model, not an
annotation-presence model. The pattern is not directly applicable here, but the general lint rule
structure (visitor pattern on PSI trees) is the same as detekt.

**`@SuppressWarnings` / `@Suppress` escape hatch:**

Standard detekt suppression works: `@Suppress("ScreenMustHaveHelpPage")` on the class body. This
is useful for abstract base classes that can't carry `@HelpPage` (though `klass.isAbstract()` already
handles this). Document in the CLAUDE.md: prefer adding the doc page and annotation over suppressing.

### Recommended Approach for SteleKit

The full enforcement chain:

1. **Detekt rule** (`ScreenMustHaveHelpPage`) — fires in CI if a Screen subclass lacks `@HelpPage`.
   Blocks `ciCheck`.
2. **businessTest file-existence check** — fires in CI if an annotated screen's slug has no
   `howto.md` + `reference.md` in the docs tree. Blocks `jvmTest`.
3. **`@HelpPage` annotation** carries a `slug: String` that is the canonical identifier linking
   the Kotlin source to the docs directory structure.

This gives two independent CI gates: structural (detekt) and content (test).

---

## Diataxis Minimal Content Templates

### Key Findings

**The four quadrants — what each one IS:**

| Quadrant | Serves | Mode | Question it answers |
|---|---|---|---|
| Tutorial | Learner | Acquisition + Action | "Help me learn by doing" |
| HowTo | Practitioner | Application + Action | "How do I accomplish X?" |
| Reference | Practitioner | Application + Cognition | "What is the exact syntax/behavior?" |
| Explanation | Curious | Acquisition + Cognition | "Why does it work this way?" |

**HowTo ≠ Tutorial:** A HowTo assumes the user already has the skill and wants to accomplish a
specific task. A Tutorial teaches a beginner through a learning experience. Conflating them is
"at the root of many documentation difficulties" (diataxis.fr).

**Minimum viable HowTo — required elements:**

1. Title in imperative "How to X" form
2. Numbered action sequence (even 2–3 steps is valid if that's genuinely all the task takes)
3. Nothing else is required — no prerequisites section, no screenshots, no explanation

What to *exclude*: teaching, context, "what is X", options not relevant to the stated task.
Padding with explanation dilutes a HowTo and belongs in the Explanation quadrant.

**Minimum viable Reference — required elements:**

1. Neutral, factual description of each option/operator/field
2. Structure mirrors the machinery (one section per concept, not per use case)
3. Brief examples illustrating usage (not instructions)
4. No how-to steps mixed in

Does not need to be exhaustive — must be accurate and authoritative for what it covers.
Tables and bullet lists are preferred over prose for scannability ("one consults reference, one doesn't read it").

**Concrete templates for SteleKit's Search feature:**

### `features/search/howto.md`

```markdown
---
title: How to search your notes
description: Find blocks and pages by keyword or query syntax.
sidebar:
  order: 1
diataxisType: howto
featureScreen: SearchScreen
---

1. Press `Cmd+K` (macOS) or `Ctrl+K` (Linux/Windows) to open search.
2. Type a keyword. Results update as you type.
3. Press `Enter` or click a result to navigate to it.

To filter results to a specific page, prefix your query with `page:PageName`.

See the [Search reference](/features/search/reference) for full query syntax.
```

This is a complete HowTo. 4 steps + 1 tip + cross-link to reference.

### `features/search/reference.md`

```markdown
---
title: Search reference
description: Query operators and behavior for SteleKit search.
sidebar:
  order: 2
diataxisType: reference
featureScreen: SearchScreen
---

Search queries match against block content and page titles.

| Operator | Effect | Example |
|---|---|---|
| (none) | Full-text keyword match | `meeting notes` |
| `page:` | Restrict results to a named page | `page:Journal todo` |
| `tag:` | Filter by tag | `tag:project` |
| `"..."` | Exact phrase match | `"action item"` |

Results are ranked by recency. The index updates on every save.
```

This is a complete Reference. Title + 1 prose sentence + operator table + 1 behavioral fact.

**HowTo + Reference is the minimum viable doc set per feature.** Diataxis recommends shipping
HowTo guides first ("the most-read sections") before Tutorials or Explanation. For a feature-by-feature
doc rollout, HowTo + Reference pairs are sufficient for a v1 doc gate.

**Tracking doc debt:**

Diataxis has no official tooling — it is a framework, not software. The practical coverage model:

- `@HelpPage(slug = "search")` on the Screen links the annotation to the docs directory slug
- A test asserts `features/{slug}/howto.md` and `features/{slug}/reference.md` both exist
- Optional: extend the annotation to `@HelpPage(slug, hasExplanation = false, hasTutorial = false)`
  for tracking partial quadrant coverage without blocking CI on missing advanced content

**Diataxis rollout order for SteleKit:**

1. Ship HowTo + Reference for every `@HelpPage`-annotated screen (blocks CI)
2. Add Explanation pages where the feature has non-obvious design rationale
3. Add Tutorial only for features with a significant learning curve (e.g., block queries, graph
   view filters)
