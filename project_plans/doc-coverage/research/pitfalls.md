# Documentation Coverage Enforcement — Pitfalls & Risk Analysis

## 1. @HelpExempt Abuse

**The risk**: Developers under deadline pressure annotate questionable screens with `@HelpExempt` to silence the detekt rule without doing the documentation work. Over time the exemption list grows to include screens that genuinely should be documented.

**Why the current design is exposed**: The requirements define `@HelpExempt` with no required parameter, so `@HelpExempt` and `@HelpExempt(reason = "debug only")` are equally trivial to add. There is no CI signal that tracks exemption growth.

**Mitigations**:

- **Mandatory `reason: String`**: Change the annotation to `annotation class HelpExempt(val reason: String)`. This forces a brief written justification at the annotation site, which reviewers can see in the diff. Zero-friction exemptions ("just suppress it") become slightly higher friction ("write a sentence").
- **CI exemption count gate**: Add an assertion to `DemoGraphCoverageTest` (or a dedicated `HelpExemptAuditTest`) that counts `@HelpExempt` usages via reflection and fails if the count exceeds a known baseline. The baseline is checked in as a constant and updated deliberately via PR. This does not prevent exemptions but makes growth visible and reviewer-approved.
- **Code review guidance in CLAUDE.md**: Document the canonical list of approved-exempt screens (current: debug menu, conflict sub-steps, AnnotationEditor) so reviewers have a reference and can push back on additions without re-litigating the policy.
- **Exemption vs. deferral**: Consider splitting into `@HelpExempt(reason)` (permanently exempt) and `@HelpDeferred(issue = "STEL-NNN")` (temporarily exempt, tied to a ticket). The detekt rule can flag `@HelpDeferred` at a lower severity so they show up in CI output without blocking the build.

---

## 2. Stub Compliance — Minimum Content Threshold

**The risk**: A developer creates `demo-graph/pages/Git Sync.md` containing `-` (a single empty bullet) and `site/src/pages/docs/git-sync.astro` containing `---\ntitle: Git Sync\n---`. Both files are non-empty. Both tests pass. The feature ships with placeholder documentation that provides no user value.

**How the current check fails**: `DemoGraphCoverageTest.pageNonEmpty()` calls `f.readText().isNotBlank()`. A single space character passes. The website coverage test requires only that the file exists and is non-empty. Neither check models meaningful content.

**Practical minimum thresholds that would catch stubs**:

- **Demo-graph page**: At least one bullet with more than N characters (e.g., 80 chars) of non-whitespace, non-property-syntax text. Property lines (`key:: value`) are infrastructure, not content. A rough heuristic: at least 2 non-property bullet lines, each ≥ 40 characters.
- **Website doc page**: At minimum the five structural sections listed in R7 (what it is, how to access it, key interactions, shortcuts, related features). Enforcing this structurally is hard without parsing Astro/MDX frontmatter; a pragmatic floor is a line count (e.g., ≥ 15 non-blank lines) combined with the presence of at least one heading (`#` or `##`).

**Recommended approach**: Keep the coverage test focused on existence and basic non-emptiness (that is its job), and rely on human PR review for content quality. Document the stub anti-pattern explicitly in CONTRIBUTING guidelines so reviewers know what to look for. Automated content quality checks tend to be gamed as easily as they are written.

---

## 3. Detekt Rule False Positives — Scope Definition

**The current Screen inventory** (from `AppState.kt`) includes several subclasses that should be `@HelpExempt` by design:

| Screen | Reason for exemption |
|---|---|
| `VaultUnlock` | Shown automatically when a paranoid-mode graph is opened; not user-initiated from the nav |
| `AnnotationEditor` | Entered via image tap, not from the navigation sidebar; advanced feature |
| `LibraryStats` | Internal diagnostics / developer tooling |
| `Notifications` | System surface, not a feature users navigate to deliberately |
| `Logs` | Developer/debug log viewer |
| `Performance` | Developer profiling screen |
| `Import` | Transient wizard state, not a standing screen |
| `GlobalUnlinkedReferences` | Power-user feature accessible from sidebar; borderline — probably should be documented |

**The false-positive failure mode**: If `MissingHelpPageAnnotation` fires on `VaultUnlock` or `Logs` and the developer has to add `@HelpExempt`, that is expected behavior. The failure mode is if the rule fires on a screen that has `@HelpPage` but the annotation is not found by the rule's PSI visitor — for example, if the rule only scans `declaredClasses` one level deep and misses screens added inside a nested sealed class. The current `DemoGraphCoverageTest.findAnnotatedClasses()` already has this issue: it calls `screenClass.declaredClasses.toList()` which only scans one level of nesting; if `Screen` ever contains a nested sealed class, the rule would silently miss it.

**Narrowing the rule scope**:

- The rule should apply only to `data class` and `data object` members that are **direct** `Screen` subclasses (not `VaultState`, `AppState`, or other sealed interfaces that happen to live in the same file). Use `KtClass.getSuperTypeListEntries()` to check the supertype is exactly `Screen`, not merely inside the `Screen` file.
- Explicitly document the exemption criteria in the rule's KDoc: "exempt if the screen is shown programmatically (not from a nav affordance), is a developer diagnostic surface, or is a transient step in a wizard flow."
- Add test cases for the exempted screens in `MissingHelpPageAnnotationRuleTest` (following the pattern in `MissingDirectRepositoryWriteRuleTest`) so the correct behavior is regression-tested.

---

## 4. Generated DemoFileSystem.kt — Idempotency and Escaping Pitfalls

**Idempotency requirement**: Gradle's up-to-date checks compare task inputs (source `.md` files) and outputs (generated `.kt` file). If the generator is not deterministic — for example, it sorts files by directory iteration order (which is OS-dependent), or it embeds a build timestamp — the output changes on every build, invalidating the `compileKotlinWasmJs` compile cache and causing full recompilation even when no markdown changed.

**Rules to make the task idempotent**:

- Sort input files by their relative path (lexicographic) before generating. Never rely on `File.listFiles()` iteration order.
- Do not embed build timestamps, Gradle version, or generator version in the output file header beyond a `// DO NOT EDIT` comment.
- Use a stable file header (e.g., `// Generated by generateDemoFileSystem — do not edit`) and verify the task's `outputs.file(...)` declaration is set so Gradle tracks it.

**Kotlin raw string escaping pitfalls**:

The demo-graph markdown files contain characters that are dangerous inside Kotlin raw strings (`"""..."""`):

- `"""` inside content terminates the raw string literal. Any markdown file that contains three consecutive double-quotes (rare but possible in code fences) will produce a compile error. **Mitigation**: escape by splitting: emit `""" + "\"" + """` or use a string concatenation approach. The safest approach is to emit the content as `trimIndent()` raw strings but test every file for embedded `"""` sequences before generating.
- `$` inside raw strings is a template reference. A markdown file with `$variable` or `${expression}` (common in shell command examples) will cause `StringTemplate` compile errors. **Mitigation**: always wrap embedded content in `${'$'}` escapes, or use `"""${"\$"}variable"""`, or — most robustly — emit the content as a `buildString { }` block with `append()` calls and no raw string wrapping.
- Backslash sequences inside raw strings are NOT escape sequences (raw strings are literal), so `\n` in markdown is safe. But this means that content containing `\` is safe, which removes one class of problems.

**Recommended generation approach**: For each file, emit a map entry using a single-quoted string via `buildString { }` or `trimIndent()` with a `${'$'}` guard for dollar signs, and include a test in `DemoGraphCoverageTest` that reads the generated `.kt` file as text and asserts it compiles (or run the file through a lightweight syntax check).

---

## 5. Website Docs Test Path Resolution

**The risk**: `WebsiteDocsCoverageTest` navigates from the classpath to the `site/src/pages/docs/` directory using a relative path like `../../site/`. When `./gradlew jvmTest` is run from the repo root this works because the working directory is the repo root. But when IntelliJ runs the test, the working directory may be `kmp/` or a Gradle daemon working directory, breaking the relative path silently.

**The correct portable approach**: Use the classpath to anchor to a known resource, then traverse upward to the repo root:

```kotlin
private val repoRoot: File by lazy {
    // The test classpath always contains demo-graph/pages, which lives under kmp/src/commonMain/resources.
    // Walk up from the resource URL to find the repo root (the directory containing kmp/).
    val resource = javaClass.classLoader.getResource("demo-graph/pages")
        ?: error("demo-graph/pages not found — test classpath is misconfigured")
    var dir = File(resource.toURI())
    // Ascend until we find the directory containing both kmp/ and site/
    while (dir != dir.parentFile && !File(dir, "site").isDirectory) {
        dir = dir.parentFile
    }
    require(File(dir, "site").isDirectory) { "Could not locate repo root from classpath: $resource" }
    dir
}

private val siteDocsDir: File get() = File(repoRoot, "site/src/pages/docs")
```

This approach is robust to any working directory because it roots navigation in the classpath, which Gradle always populates correctly. It will also work in IntelliJ with no configuration change.

An alternative is to pass the repo root as a system property via the `jvmTest` task configuration in `build.gradle.kts`:
```kotlin
tasks.named<Test>("jvmTest") {
    systemProperty("repo.root", rootProject.projectDir.absolutePath)
}
```
and read it in the test with `System.getProperty("repo.root")`. This is slightly simpler but requires the `build.gradle.kts` change to also be present; the classpath-walking approach is self-contained.

---

## 6. Detekt vs. jvmTest — Two-Gate Divergence

**The scenario**: A developer annotates `Screen.GitSync` with `@HelpPage(docs = GitSyncDocs::class)` (satisfying `MissingHelpPageAnnotation` detekt rule, passing the lint CI gate) but does not create `demo-graph/pages/Git Sync.md` or `site/src/pages/docs/git-sync.astro` (failing `DemoGraphCoverageTest` and `WebsiteDocsCoverageTest`, which run in the test CI gate). The PR passes lint, then fails on test, requiring a second commit.

**Is the two-gate split acceptable?** Yes, with caveats:

- The split is intentional: detekt catches missing annotations (structural/code-level); jvmTest catches missing files (content-level). These are genuinely different concerns and different tools are better suited to each.
- The failure happens within the same PR, not after merge — the developer gets feedback before the branch is merged. The cost is a second CI round-trip, not a shipped bug.
- The bigger risk is that a developer annotates with `@HelpPage` and creates stub `.md` files (per risk 2) — in that case both gates pass. The two-gate split does not create a hole; stubs do.

**Improvements that reduce the round-trip cost without collapsing the gates**:

- Document in the PR template that adding a new `Screen` requires three artifacts: the annotation, the demo-graph page, and the website docs page. A checklist in the PR template is low-cost and effective.
- The `./gradlew checkDocCoverage` convenience task (R8) subsumes both gates and can be run locally before push, avoiding the CI round-trip entirely. Ensure CLAUDE.md mentions it.
- Consider adding a pre-commit hook (via the `update-config` skill) that runs `./gradlew checkDocCoverage` on changes to `Screen.kt` or `AppState.kt`. This catches the divergence before the PR is even created.

---

## 7. DemoFileSystem Journal Date — Build-Time vs. Runtime

**The problem**: Under the current requirements (R3), the Gradle `generateDemoFileSystem` task reads `demo-graph/welcome.md`, substitutes `{DATE}` with the build date, and embeds the result as a static string in `DemoFileSystem.kt`. When the WASM bundle is deployed to production, the journal entry is dated at build time. A user who opens the web demo 30 days after the last deployment sees a journal dated in the past, which is confusing (the Journals view highlights "today" and navigates to today's date on open).

**Why the current DemoFileSystem.kt does this correctly**: The existing (manually maintained) `DemoFileSystem.kt` uses `Clock.System.todayIn(TimeZone.currentSystemDefault())` at **runtime** to compute `journalFileName` and injects the correct date dynamically. This works perfectly — the journal is always dated today regardless of when the WASM bundle was built.

**The pitfall in R3**: If the Gradle task bakes the date into the emitted `.kt` source, it breaks the runtime-date logic. The correct solution is to preserve the runtime date injection: the Gradle task should embed the journal *template content* (the text of `welcome.md` with `{DATE}` still present as a literal placeholder string), and `DemoFileSystem.kt` should substitute the placeholder at runtime when constructing the `demoFiles` map.

**Concrete implementation**: The generated file should emit the journal content as:
```kotlin
private val journalTemplate = """...<welcome.md content with {DATE} intact>..."""

private val demoFiles = mapOf(
    "journals/$journalFileName" to journalTemplate.replace("{DATE}", today.toString()),
    // ... other files
)
```
This makes the Gradle task idempotent (same content in → same `.kt` out, no build-date embedding) and keeps the runtime behavior correct. The `{DATE}` placeholder approach is simple and avoids any string template conflict since `{DATE}` is not valid Kotlin string template syntax.

**Secondary issue — journal file list**: The generated `demoFiles` map should also include the five bundled journal files from `demo-graph/journals/` as static entries, so historical journal navigation works in the demo. These are static dates (not today's date), so build-time embedding is correct for them. Only the "today" journal entry requires runtime date injection.
