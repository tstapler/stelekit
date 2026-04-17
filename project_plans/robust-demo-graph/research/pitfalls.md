# Pitfalls: Robust Demo Graph

**Phase**: 2 — Research (Pitfalls)  
**Date**: 2026-04-13

This document identifies risks and failure modes for the Robust Demo Graph feature before implementation. Each risk is assessed for severity, likelihood, and impact, with proposed mitigations.

---

## KSP in Kotlin Multiplatform: Per-Target Configuration

**Problem**: Kotlin Symbol Processing (KSP) in KMP requires explicit per-target configuration. Unlike the single-target JVM case, KMP projects must declare `kspJvm`, `kspAndroid`, `kspIos*` separately or KSP may fail to run on some targets.

**Evidence**:
- The project uses **Kotlin 2.3.10** and **Compose Multiplatform 1.7.3**, both modern enough to support KSP.
- `build.gradle.kts` currently has **no KSP plugin or dependencies** (`ksp` not mentioned).
- The project targets JVM, Android, iOS, and JS — all require separate configuration.
- KSP version **must match Kotlin version exactly** (2.3.10 would require ksp-2.3.10).

**Severity**: **High**  
**Likelihood**: **Very High** if using KSP  
**Impact**: Build failures, incomplete annotation processing, features not enforced on some platforms.

**Mitigation**:
1. **Before adding KSP**, decide: is annotation processing per-target essential?
   - If the annotation is processed at compile time to verify files exist, it works on JVM/Android/JS but may skip iOS (which uses Kotlin/Native and has limited KSP support).
2. **Fallback to interface + test-time verification** (see "Annotation Enforcement" below) — simpler, cross-platform, and doesn't require KSP.
3. If KSP is mandatory:
   - Declare `kspCommonMainMetadata` (if using commonMain annotations) or separate `ksp*` configurations per target.
   - Pin KSP version to match Kotlin version (e.g., `"com.google.devtools.ksp:symbol-processing:2.3.10"`).
   - Test on all targets (JVM, Android, iOS) in CI to catch per-target failures early.
4. Document the decision in ADR-XXXX and link from requirements.

---

## Classpath Resource Loading Across Platforms

**Problem**: The demo graph must be bundled in `kmp/src/commonMain/resources/demo-graph/` and loaded on JVM, Android, and iOS. Each platform has different resource access mechanisms, and `PlatformFileSystem` currently does not expose a resource-loading API.

**Evidence**:
- **JVM**: `getResourceAsStream()` (via ClassLoader) is the standard. Path separators must be `/` (not `\`).
- **Android**: Assets live in `src/androidMain/assets/` (not classpath resources); accessed via `AssetManager.open()`. Alternatively, `raw/` resources in `res/` are compiled into R.java. Neither is transparently accessed via standard JVM classpath APIs.
- **iOS**: Bundle resources are accessed via `NSBundle.main.resourcePath` or `Bundle(for: ...)`, not classpath.
- Current `PlatformFileSystem` implementations (JVM, Android, iOS):
  - JVM: Uses `java.io.File` for filesystem access; no resource-loading method.
  - Android: Uses `Context` (requires init) and SAF for storage; no embedded-resource method.
  - iOS: Uses `NSFileManager` for Documents directory; no resource-loading method.
- **No composeResources configured**: The project does not use Compose Multiplatform's `composeResources` block (which provides a cross-platform resources API).

**Severity**: **High**  
**Likelihood**: **Very High** — bundling resources requires new code paths  
**Impact**: Demo graph cannot be loaded on Android or iOS; JVM works but requires special path handling.

**Mitigation**:
1. **Add composeResources to gradle**: Enable Compose Multiplatform's built-in resource bundling (available since 1.0.0).
   - Place demo graph files in `kmp/src/commonMain/composeResources/files/demo-graph/pages/` and `journals/`.
   - Gradle automatically embeds these and provides a cross-platform resources API.
2. **Extend PlatformFileSystem** with a new method:
   ```kotlin
   fun loadBundledResource(relativePath: String): String?
   ```
   Implement per-target:
   - JVM: `this::class.java.getResourceAsStream("/demo-graph/$path")?.readBytes()?.decodeToString()`
   - Android: `context.assets.open("demo-graph/$path").bufferedReader().readText()` (requires Context init).
   - iOS: `Bundle.main.path(forResource: "demo-graph/pages/index", ofType: "md")` then read file.
   - JS: `fetch("/demo-graph/$path").text()` (if serving static files).
3. **Add integration test** (`DemoGraphLoadTest`) that calls `loadBundledResource()` on all targets; fail the build if any target cannot load the index page.
4. **Document path normalization**: Demo graph files use `/` as path separators internally, but `PlatformFileSystem` may expect `\` on Windows. Normalize in the loader.

---

## Image / Asset Bundling: Binary Size and Platform Differences

**Problem**: The requirements include "at least one embedded image." Bundling images as binary assets increases app size on all platforms and requires different handling per platform (iOS bundle resources, Android drawable/asset, JVM classpath resources).

**Evidence**:
- Requirements specify "at least one embedded image (using a bundled asset or a safe placeholder)."
- No image assets currently exist in the repo (only a desktop icon).
- Compose Multiplatform's `composeResources` supports image bundling but has limitations:
  - Images must be in `composeResources/drawable/` for Compose to manage them.
  - Large images (>1MB) significantly bloat JVM and Android APK sizes.
  - Image encoding (PNG, WebP, SVG) matters; SVG support is limited in Compose.
- On iOS, images are bundled in the app bundle with different size constraints than Android APK.

**Severity**: **Medium**  
**Likelihood**: **Medium** — only if using large images  
**Impact**: Slow app startup, larger distribution size, user storage impact.

**Mitigation**:
1. **Use a small placeholder image** (< 50 KB) for the demo graph. A simple PNG or SVG diagram (e.g., a block structure diagram) is enough to demonstrate image rendering.
2. **Do not embed high-resolution photos or complex graphics**. If visual documentation is needed, link to external URLs or provide SVG-based diagrams (text-based, smaller).
3. **Measure binary impact before and after**:
   - Build `gradle build -x test` and check APK/app bundle size.
   - Set a threshold (e.g., "demo graph assets must not exceed 200 KB total").
4. **Use WebP or optimized PNG** for Android; Compose handles both efficiently.
5. **Test on slow networks**: Screenshot tests should verify that images load, but don't assume network access.

---

## Roborazzi Flakiness: Screenshot Tests with Dynamic Content

**Problem**: The requirements include "screenshot (Roborazzi) baselines for at least the index page, a journal page, a properties page, and a page with wiki-links." Roborazzi pixel-compares rendered screenshots, which can fail due to:
- Font rendering differences between CI machines and developer machines.
- Dynamic content (timestamps, `"today's journal"` links, dates) that changes daily.
- Demo graph pages that reference `today` or use `SCHEDULED` timestamps will cause baseline mismatches.

**Evidence**:
- Existing Roborazzi tests in `kmp/src/jvmTest/kotlin/.../screenshots/` use `captureRoboImage()` which saves exact pixel comparisons.
- `JournalsViewScreenshotTest.kt` creates hardcoded fixture pages with static dates (`2026-03-21`), avoiding timestamp issues.
- A real demo graph with `[[Today's Journal]]` or `SCHEDULED: 2026-04-13` will render differently on each CI run.
- Roborazzi's `recordMode` disables verification (allows any screenshot), but CI should fail if baselines are missing.

**Severity**: **Medium**  
**Likelihood**: **High** — journal pages inherently have dates  
**Impact**: Flaky CI (false failures), difficult troubleshooting, test maintenance burden.

**Mitigation**:
1. **Avoid hardcoded dates in the demo graph**. Instead:
   - Journal pages: Use relative links like `"See [[daily-template]]"` rather than `"Today: 2026-04-13"`.
   - Use placeholder text: `"Example scheduled task"` without embedding `SCHEDULED:` timestamps.
   - Or parameterize rendering: if a block contains `SCHEDULED:`, the UI can display `"(scheduled)"` without the date.
2. **Use static test data**: For screenshot baselines, create a separate *static* demo graph (not the real one) with:
   - Fixed dates (e.g., all journal entries on 2026-01-01).
   - No time-dependent content.
   - Placed in `kmp/src/jvmTest/resources/demo-graph-static/` for testing only.
3. **Separate fixture graphs**: The bundled demo graph (for users) and the screenshot-test fixture graph (for CI) should be decoupled.
4. **Font consistency**: Run screenshot tests in a Docker container with standard fonts. Document the CI environment (fonts, OS, screen DPI) in the test setup.
5. **CI config**: Set Roborazzi to *compare only*, not record. Record baselines locally with `./gradlew recordRoborazziDebug` and commit baselines to repo.

---

## Demo Graph Drift: Parser Versioning and Breaking Changes

**Problem**: The biggest long-term risk is the demo graph becoming stale as the parser evolves. If the markdown parser (currently `org.jetbrains:markdown:0.7.3`) introduces breaking changes, or if SteleKit's `.sq` schema changes (e.g., properties syntax, block structure), the demo graph pages may silently fail to parse or render incorrectly.

**Evidence**:
- `org.jetbrains:markdown:0.7.3` is a dependency, but changes to this library are not pinned. The project uses `implementation("org.jetbrains:markdown:0.7.3")` without version locking.
- SteleKit's own parser (`MarkdownParser`, `PropertiesParser`, `LogseqParser`) extends the Jetbrains markdown library and defines custom rules (e.g., `key:: value` properties, block nesting, wiki-links).
- No existing mechanism to detect when fixture files need updating (e.g., a test that verifies demo graph pages parse without errors).
- Properties syntax is custom and undocumented: if `PropertiesParser` changes to require `:PROPERTIES:` drawer instead of inline `key:: value`, demo graph pages with inline properties will break silently.

**Severity**: **High**  
**Likelihood**: **High** — parsers evolve, breaking changes happen  
**Impact**: Demo graph pages fail to load or render incorrectly; users see broken content; reputation damage.

**Mitigation**:
1. **Create `DemoGraphIntegrationTest`** (mentioned in requirements):
   ```kotlin
   @Test
   fun testDemoGraphParses() = runBlocking {
     val loader = GraphLoader(fileSystem, pageRepo, blockRepo)
     loader.loadGraph(demoGraphPath) { /* progress */ }
     val pages = pageRepo.getAllPages().first().getOrNull() ?: fail("No pages loaded")
     assertTrue(pages.isNotEmpty(), "Demo graph loaded zero pages")
     assertTrue(pages.all { it.isContentLoaded }, "Some pages failed to load")
   }
   ```
   - Run this test on every CI build. If it fails, the PR cannot merge.
   - This catches parser breaking changes immediately.

2. **Document parser schema** in `kmp/docs/parser-schema.md`:
   - Supported block types (bullet, heading, table, code fence, etc.).
   - Properties syntax: `key:: value`, `:PROPERTIES: ... :END:` drawers.
   - Inline formatting: `**bold**, __italic__, `code`, [[wiki-link]], #tag.
   - Reserved keywords: `SCHEDULED:`, `DEADLINE:`, `CREATED:`.
   - Any changes to this schema must be reflected in the doc and the test updated.

3. **Add breaking-change detection**:
   - When updating `MarkdownParser`, `PropertiesParser`, or dependencies, run a *dedicated test suite* against the demo graph:
     ```bash
     ./gradlew demoGraphCompatibilityTest
     ```
   - This test loads the demo graph and verifies:
     - Page count hasn't changed.
     - Block counts per page haven't changed unexpectedly.
     - No pages have zero blocks.
     - All wiki-links resolve.

4. **Versioning**: Pin the Jetbrains markdown library version in `gradle/libs.versions.toml`:
   ```toml
   jetbrains-markdown = "0.7.3"
   ```
   Only upgrade after testing the demo graph. Document the upgrade in the CHANGELOG.

5. **Enforce in PR reviews**: Require that any parser change include a demo graph compatibility test result in the PR description.

---

## Annotation Enforcement: False Positives and KSP Limitations

**Problem**: The requirements propose using `@HelpPage(page: String)` annotation + KSP or an interface to enforce that every feature has corresponding help documentation. Risks include:
- **Annotations on @Composable functions**: Kotlin annotations on `@Composable` functions have limitations (reflection can't find them reliably).
- **False positives**: A page marked as a "stub" (empty or incomplete) will still pass the enforcement check.
- **Scope creep**: What is a "feature"? Only classes? Also functions? Screens? Editors? Without clear boundaries, enforcement becomes inconsistent.
- **KSP limitations in Kotlin/Native**: iOS (Kotlin/Native target) has limited KSP support; the annotation verification may only work on JVM/Android.

**Evidence**:
- Requirements suggest `@HelpPage("Block Editing")` on classes but also mention `@Composable` functions; no guidance on where the annotation goes.
- The fallback interface approach (`interface Feature { val helpPage: HelpPageRef }`) is runtime-checkable but only works for classes that explicitly implement the interface.
- If KSP is used for verification, it will not enforce the rule on iOS (where KSP may not run).
- No existing `Feature` interface or annotation exists in the codebase.

**Severity**: **Medium**  
**Likelihood**: **Medium** — depends on implementation choice  
**Impact**: Inconsistent enforcement, false positives (stub pages appear valid), coverage gaps on iOS.

**Mitigation**:
1. **Choose interface over annotation** (recommended):
   - Define a `HelpDocumented` interface in `commonMain`:
     ```kotlin
     interface HelpDocumented {
       val helpPageName: String
     }
     ```
   - Require all feature classes to implement it: `class BlockEditor : HelpDocumented { override val helpPageName = "Block Editing" }`.
   - This is cross-platform, doesn't require KSP, and is compiler-enforced.
   - Test-time verification: `DemoGraphCoverageTest` checks that every `HelpDocumented.helpPageName` has a corresponding page file.

2. **Define "Feature" clearly in ADR**:
   - Features = top-level screens, editors, or major composable components (e.g., `BlockEditor`, `JournalsView`, `PropertiesPanel`).
   - Exclude: helper functions, utilities, internal components.
   - Document with examples.

3. **Prevent stub pages**:
   - `DemoGraphCoverageTest` should also verify that pages are **not empty**:
     ```kotlin
     pages.forEach { page ->
       assertTrue(page.blocks.isNotEmpty(), "Page '${page.name}' is empty; write content or remove the help page reference")
     }
     ```
   - Fail the build if a page has zero blocks (it's a stub).

4. **If using annotation anyway**:
   - Do NOT use `@Composable` — apply only to classes.
   - Use reflection at test time (JVM/Android only):
     ```kotlin
     val annotatedClasses = scanClasspath(HelpPage::class) // reflection-based scan
     ```
   - On iOS, fall back to the interface check.
   - Document that iOS has reduced enforcement and rely on manual review.

---

## Android Asset Access: Context Dependency and Initialization Order

**Problem**: Android's `PlatformFileSystem` requires a `Context` to access app assets. If the bundled demo graph is stored in Android assets (recommended for APK efficiency), loading it requires:
1. The `Context` to be initialized (typically in `Application.onCreate()`).
2. Proper handling of the case where demo graph loading happens before `Context.init()` is called.

**Evidence**:
- Android `PlatformFileSystem.kt` has `fun init(context: Context, ...)` but this is **optional** — the code does not enforce it.
- `getDefaultGraphPath()` returns a fallback if not initialized, but `loadBundledResource()` (proposed in "Classpath Resource Loading" mitigation above) would fail silently if `context` is null.
- Tests use `FakeFileSystem`, which returns empty strings for all file operations — Android tests may not catch `Context` initialization bugs.

**Severity**: **Medium**  
**Likelihood**: **Medium** — depends on when demo graph is loaded  
**Impact**: Demo graph fails to load on Android; feature not available; poor first-time user experience.

**Mitigation**:
1. **Enforce Context initialization early**:
   - In the main `Application` class:
     ```kotlin
     class StelekitApplication : Application() {
       override fun onCreate() {
         super.onCreate()
         PlatformFileSystem().init(this)
       }
     }
     ```
   - Document this requirement in `PlatformFileSystem.kt`.

2. **Add a guard in loadBundledResource()**:
   ```kotlin
   fun loadBundledResource(path: String): String? {
     if (context == null) {
       Log.e(TAG, "loadBundledResource called before init()")
       return null
     }
     return try {
       context.assets.open("demo-graph/$path").bufferedReader().readText()
     } catch (e: Exception) {
       null
     }
   }
   ```

3. **Test on Android**: Add an `androidUnitTest` that verifies:
   - `PlatformFileSystem.init(...)` is called.
   - A bundled resource (e.g., `demo-graph/pages/index.md`) can be loaded.
   - Fail if resource is missing.

4. **Store demo graph in assets, not raw resources**:
   - Android's `assets/` folder (not `res/raw/`) is better for directory structures and is accessed via `AssetManager.open()`.
   - Place files in `android/app/src/main/assets/demo-graph/pages/` (not in common resources).
   - Gradle will bundle them into the APK.

---

## CommonMain Source Set: Annotation Processing Inconsistency

**Problem**: KMP source sets have different compilation targets. If `@HelpPage` annotations live in `commonMain`, KSP must process them on each target, but:
- `commonMain` is compiled to bytecode *before* being consumed by platform-specific targets (JVM, Android, JS).
- KSP typically runs *after* compilation to bytecode, making it hard to intercept `commonMain` annotations.
- The alternative is to use `kspCommonMainMetadata`, which is less widely supported and may not work on all targets.

**Severity**: **Medium**  
**Likelihood**: **Medium** — specific to KSP approach  
**Impact**: Annotation processing fails on some targets; enforcement is skipped silently.

**Mitigation**:
1. **Avoid KSP for annotation processing**. Use the interface + test-time verification approach (see "Annotation Enforcement" mitigation above).
2. **If KSP is mandatory**:
   - Use `kspCommonMainMetadata` in `build.gradle.kts`:
     ```kotlin
     dependencies {
       kspCommonMainMetadata("com.example:ksp-processor:1.0")
     }
     ```
   - Document that this is experimental and may not work on all targets (especially iOS/Kotlin/Native).
   - Test on all targets before relying on the enforcement.

---

## Image Format and Compose Support

**Problem**: Compose Multiplatform has limited support for image formats. SVG is not natively supported in all targets; animated GIFs are not supported. If the demo graph includes images, the format must be compatible.

**Severity**: **Low**  
**Likelihood**: **Low** — only if using unsupported image formats  
**Impact**: Image fails to render; demo graph appears incomplete.

**Mitigation**:
1. **Use PNG or WebP only**. No SVG, GIF, or AVIF.
2. **Test images on all targets**: JVM, Android, iOS.
3. **Fallback**: If an image cannot be loaded, the UI should gracefully skip it and show a placeholder. Do not crash.

---

## Fixture Graph Maintenance: Editing Demo Graph in Production

**Problem**: The demo graph is both a user-facing artifact (bundled in the app) and a test fixture. If developers edit it to fix a page or add a feature, the changes:
- Are immediately visible to *all users* in the next release.
- Cannot be easily rolled back or conditionally deployed.
- May break existing screenshot baselines if visual content changes.

**Severity**: **Low**  
**Likelihood**: **Low** — only an issue for high-frequency updates  
**Impact**: Accidental breaking changes to demo graph shipped to users.

**Mitigation**:
1. **Treat the demo graph as a versioned artifact**:
   - Include a demo graph version or hash in the app metadata:
     ```kotlin
     object DemoGraphInfo {
       const val VERSION = "1.0.0"
       const val LAST_UPDATED = "2026-04-13"
     }
     ```
   - When users update the app, show a changelog (e.g., "Demo graph updated: Added properties examples").

2. **Code review**: Require at least one reviewer for any PR that modifies demo graph files. Document the purpose of changes in the PR description.

3. **Snapshot tests**: Screenshot baselines should be committed to git. If a baseline changes, the diff will be visible in the PR, prompting discussion.

---

## Top 5 Risks: Prioritized

| # | Risk | Severity | Likelihood | Impact | Mitigation Priority |
|---|------|----------|------------|--------|---------------------|
| **1** | Demo Graph Drift (parser breaking changes) | High | High | Silent failures; users see broken pages | **Critical** — add DemoGraphIntegrationTest and parser versioning |
| **2** | Classpath Resource Loading (no cross-platform API) | High | Very High | Demo graph cannot load on Android/iOS | **Critical** — add composeResources and PlatformFileSystem.loadBundledResource() |
| **3** | KSP in KMP Per-Target Configuration | High | Very High* | Build failures on some targets | **High** — if using KSP; otherwise use interface + tests (recommended) |
| **4** | Roborazzi Flakiness (dynamic content) | Medium | High | Flaky CI, false failures, test maintenance | **High** — use static test graphs; avoid hardcoded dates in demo graph |
| **5** | Annotation Enforcement False Positives | Medium | Medium | Stub pages pass checks; enforcement gaps on iOS | **Medium** — use interface over annotation; test for non-empty pages |

*Very High likelihood only if choosing KSP approach; interface approach avoids this entirely.

---

## Recommended Next Steps

1. **Create ADR-XXXX: Help Page Enforcement** — decide annotation vs. interface; document scope of "Feature."
2. **Add `PlatformFileSystem.loadBundledResource()`** method and test on all platforms.
3. **Implement `DemoGraphIntegrationTest`** and `DemoGraphCoverageTest` (referenced in requirements).
4. **Create a static test demo graph** in `kmp/src/jvmTest/resources/` for screenshot baselines; separate from the bundled production graph.
5. **Document parser schema** and establish a breaking-change review process for parser upgrades.
6. **Plan CI setup**: Font consistency for screenshot tests, resource bundling, integration test coverage on all targets.

