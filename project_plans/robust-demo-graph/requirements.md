# Requirements: Robust Demo Graph

**Status**: Draft | **Phase**: 1 — Ideation complete  
**Created**: 2026-04-13

## Problem Statement

SteleKit has no canonical example graph that demonstrates its features in a real-world,
human-readable form. This means:

1. New users have no guided "Hello World" to explore after first launch.
2. The test suite relies on programmatically-generated fixture data (inline Kotlin strings
   and temp directories), which is harder to read, edit, and maintain than real markdown.
3. There is no in-app help reference — users must consult external documentation.

Logseq solves this with a bundled demo graph that doubles as a help document. SteleKit
needs an equivalent artifact that is simultaneously a first-class test fixture, a visual
regression baseline, and the in-app Help Graph.

## Success Criteria

- A user opening SteleKit for the first time can add the "Help Graph" and immediately
  understand core features by reading it.
- The graph parses without errors under `GraphLoader` on every CI run.
- Screenshot (Roborazzi) baselines exist for at least the index page, a journal page,
  a properties page, and a page with wiki-links, covering the core rendered surfaces.
- Any new feature added to SteleKit has a corresponding page or section in the demo graph
  within the same PR.
- Existing inline fixture strings in tests can be migrated to load from the demo graph
  files where it is natural to do so.

## Scope

### Must Have (MoSCoW)

- **Block editing / outliner** — nested blocks, indentation levels, reordering examples
- **Page linking / graph** — `[[wiki links]]` between pages, backlink demonstration pages
- **Properties & metadata** — page-level frontmatter, block-level properties
- **Journal / daily notes** — at least 3–5 sample journal entries showing daily-note patterns
- **Images** — at least one embedded image (using a bundled asset or a safe placeholder)
- **Tables** — at least one Markdown table example
- **Index / Welcome page** — a "Contents" or "Start Here" page that links to all feature pages
- **Shipped in the repo** — checked in under `kmp/src/commonMain/resources/demo-graph/`
  (or equivalent classpath location), so it is always available without network access
- **Integration test** — `DemoGraphIntegrationTest` that loads the graph via `GraphLoader`
  and asserts all pages parse, all wiki-links resolve, and no blocks are empty
- **Screenshot tests** — Roborazzi baselines for key pages (index, journal, properties, links)

### Feature-Documentation Enforcement (Must Have)

Every SteleKit feature must declare its help documentation **in the code itself** — not in
an external registry file. The design is inspired by Python's docstrings / pydoc: documentation
is intrinsic to the definition, so it cannot be omitted without a compile or test error.

**Design: `@HelpPage` points to a documentation class, not a string**

The annotation does not reference a page name directly. Instead it references a **documentation
class** that must implement required Diataxis interfaces. The compiler enforces that all required
documentation types are provided; the test suite verifies the generated pages exist in the graph.

```kotlin
// 1. The annotation — references a KClass, not a raw string
@HelpPage(docs = BlockEditorDocs::class)
object BlockEditorFeature : Feature

// 2. The documentation class — must implement all required Diataxis interfaces
class BlockEditorDocs : HowToDoc, ReferenceDoc {

    override val howTo: HowToContent = HowToContent(
        title = "How to edit blocks",
        steps = listOf("Click a block to focus it", "Type to edit", "Press Enter for a new block")
    )

    override val reference: ReferenceContent = ReferenceContent(
        title = "Block Editor",
        summary = "The block editor handles all inline editing operations.",
        properties = listOf(/* ... */)
    )
}
```

**Diataxis interfaces required (minimum viable set):**

| Interface | Diataxis type | Required for |
|---|---|---|
| `HowToDoc` | How-to guide | Any feature with a user workflow |
| `ReferenceDoc` | Reference | Any feature with configurable properties or syntax |
| `TutorialDoc` | Tutorial | Complex features that need a learning path (optional) |
| `ExplanationDoc` | Explanation | Non-obvious concepts (optional) |

`HowToDoc` and `ReferenceDoc` are **required** for all user-facing features. `TutorialDoc`
and `ExplanationDoc` are optional. Omitting a required interface is a compile error.

**Content-to-file generation:**
- At build time (Gradle task or KSP), all `@HelpPage`-annotated classes are collected.
- The `docs` class is instantiated; its interface implementations are rendered to Logseq-format
  `.md` files in the demo graph (or validated against existing checked-in files).
- A `DemoGraphCoverageTest` verifies that every declared `@HelpPage` class has corresponding
  `.md` pages present and non-empty.

**Enforcement gates:**
- Compile-time: missing required interface (`HowToDoc`, `ReferenceDoc`) on the docs class
  → compile error.
- Build/test-time: `@HelpPage` docs class present but no matching `.md` in demo graph → CI
  failure with:
  ```
  FAIL: BlockEditorDocs declares HowToDoc but demo-graph/pages/How to edit blocks.md is missing.
  ```
- PRs adding `@HelpPage` without the corresponding markdown file fail CI.

### Out of Scope

- Plugin or extension system demos (SteleKit does not yet have a plugin API)
- Real-time sync or collaboration content
- Custom themes or CSS examples
- Non-English content (English only for initial release)
- A separate, expanded Help Graph distinct from the demo graph — same files serve both roles

## Constraints

- **Tech stack**: Kotlin Multiplatform; demo graph must be loadable on all targets
  (JVM/Desktop, Android, iOS) via the existing `GraphLoader` + `PlatformFileSystem` APIs.
- **Format**: Logseq-compatible Markdown directory layout (`pages/` + `journals/`
  subdirectories, `.md` files with Logseq-style bullet outliner syntax).
- **Bundling**: Classpath resource (commonMain resources), not downloaded at runtime.
- **Timeline**: No hard deadline. Implement incrementally — start with the index + 4–5
  feature pages, expand as new features ship.
- **Dependencies**: Requires `GraphLoader` to support loading from classpath/bundled paths
  (may need a small platform shim if not already supported for resources).

## Context

### Existing Work

- `GraphLoader` already loads graphs from arbitrary filesystem paths; test fixtures today
  are written to `System.getProperty("user.home")` temp directories.
- `GraphLoaderTest` and `GraphLoaderIntegrationTest` show the fixture-graph pattern.
- Roborazzi screenshot infrastructure is live in `jvmTest/screenshots/`.
- No existing demo or help graph content exists in the repo.
- Logseq's demo graph (MIT-licensed) is available as a reference for content ideas, but
  content should be original and SteleKit-specific.

### Stakeholders

- **End users**: Need a frictionless "try it now" experience after first launch.
- **Developers / contributors**: Need a human-readable, editable fixture that covers
  parser edge cases without maintaining inline Kotlin strings.
- **Tyler (project owner)**: Wants the demo graph to serve as living documentation and
  a quality gate — if a page in the graph breaks, a test should catch it.

## Research Dimensions Needed

- [ ] Stack — how to bundle classpath resources in KMP commonMain and read them on each
      target (JVM, Android, iOS); whether `FileSystem` abstraction is sufficient
- [ ] Features — survey Logseq demo graph content, Obsidian Help Vault, and other
      "example vault" projects for page structure and feature coverage patterns
- [ ] Architecture — best location for demo graph files, how integration tests load them,
      how screenshot tests reference specific pages, classpath vs. asset-folder trade-offs
- [ ] Stack — KSP availability and configuration in KMP commonMain; whether reflection-based
      class scanning is viable cross-platform or if KSP code generation is required
- [ ] Pitfalls — encoding issues in bundled resources, image asset bundling on each platform,
      Roborazzi flakiness with dynamic content, KSP incremental compilation edge cases,
      annotation vs. interface trade-offs (KSP requires annotation; interface allows runtime check),
      avoiding false-positive CI failures when help pages are stubs vs. missing entirely
