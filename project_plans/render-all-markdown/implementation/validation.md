# Validation Plan: Render All Markdown Blocks

## Coverage Map

| Requirement | Test Type | Test Location | Priority |
|---|---|---|---|
| `MarkdownParser.convertBlock()` maps all 9 `BlockNode` subtypes to correct discriminator | Unit | `commonTest/parser/BlockTypeDiscriminatorTest` | P0 |
| Discriminator string round-trips through `split(":", limit=2)` | Unit | `commonTest/parser/BlockTypeDiscriminatorTest` | P0 |
| `ParsedBlock.blockType` default is `BlockType.Bullet` | Unit | `commonTest/model/ParsedBlockDefaultsTest` | P0 |
| `Block.blockType` string field defaults to `"bullet"` | Unit | `commonTest/model/BlockModelTest` | P0 |
| `Block.init` rejects unknown discriminator strings | Unit | `commonTest/model/BlockModelTest` | P1 |
| `BlockTypeMapper.toDiscriminatorString()` covers all `BlockType` variants | Unit | `commonTest/model/BlockTypeMapperTest` | P0 |
| Full pipeline: markdown → `ParsedBlock.blockType` populated correctly | Integration | `jvmTest/db/GraphLoaderBlockTypeTest` | P0 |
| SQLDelight round-trip: insert `blockType`, read back, value preserved | Integration | `jvmTest/db/BlockTypeRepositoryRoundTripTest` | P0 |
| Migration `2.sqm`: existing rows default to `"bullet"` | Integration | `jvmTest/db/BlockTypeMigrationTest` | P0 |
| `BlockItem` dispatches to `HeadingBlock` for heading blockType | Compose/UI | `jvmTest/ui/components/BlockItemDispatchTest` | P0 |
| `HeadingBlock` renders H1-H6 with distinct typography | Screenshot | `jvmTest/ui/screenshots/HeadingBlockScreenshotTest` | P1 |
| `CodeFenceBlock` renders with monospace, language label, scroll | Screenshot | `jvmTest/ui/screenshots/CodeFenceBlockScreenshotTest` | P1 |
| `BlockquoteBlock` renders with left accent bar | Screenshot | `jvmTest/ui/screenshots/BlockquoteBlockScreenshotTest` | P1 |
| `OrderedListItemBlock` renders number prefix | Screenshot | `jvmTest/ui/screenshots/OrderedListBlockScreenshotTest` | P1 |
| `ThematicBreak` renders as full-width divider | Screenshot | `jvmTest/ui/screenshots/ThematicBreakScreenshotTest` | P2 |
| `TableBlock` renders header row, body rows, column alignment | Screenshot | `jvmTest/ui/screenshots/TableBlockScreenshotTest` | P1 |
| `TableBlock` parses raw pipe-table string correctly | Unit | `commonTest/ui/components/ParseTableContentTest` | P0 |
| `TableBlock` scrolls horizontally when content exceeds screen width | Compose/UI | `jvmTest/ui/components/TableBlockScrollTest` | P2 |
| `ImageBlock` renders `AsyncImage` with placeholder and error states | Unit | `jvmTest/ui/components/ImageBlockTest` | P1 |
| `SteleKitAssetFetcher` resolves `../assets/` path against graph root | Unit | `jvmTest/ui/components/SteleKitAssetFetcherTest` | P1 |
| Subscript renders with `BaselineShift(-0.3f)` and 0.75em size | Unit | `commonTest/ui/components/MarkdownEngineSubscriptTest` | P1 |
| Superscript renders with `BaselineShift.Superscript` and 0.75em size | Unit | `commonTest/ui/components/MarkdownEngineSubscriptTest` | P1 |
| LaTeX `$...$` delimiters stripped; content rendered monospace italic | Unit | `commonTest/ui/components/MarkdownEngineLatexTest` | P1 |
| Existing bullet block rendering unchanged after dispatch wiring | Regression | `jvmTest/ui/components/BlockItemDispatchTest` | P0 |
| Inline rendering (bold, italic, wiki links) works within all block types | Regression | `jvmTest/ui/components/InlineRenderingRegressionTest` | P0 |
| Edit mode (`BasicTextField`) unaffected by view-mode changes | Regression | `jvmTest/ui/components/BlockItemEditModeRegressionTest` | P0 |
| All pre-existing tests pass without modification after Story 1 | Regression | All source sets | P0 |

---

## Story 1: Model & Data Layer Tests

### Unit Tests

**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parser/BlockTypeDiscriminatorTest.kt`

```
BlockTypeDiscriminatorTest
  convertBlock_bulletBlockNode_returnsBulletDiscriminator
  convertBlock_paragraphBlockNode_returnsParagraphDiscriminator
  convertBlock_headingBlockNode_level1_returnsHeading1Discriminator
  convertBlock_headingBlockNode_level2_returnsHeading2Discriminator
  convertBlock_headingBlockNode_level3_returnsHeading3Discriminator
  convertBlock_headingBlockNode_level4_returnsHeading4Discriminator
  convertBlock_headingBlockNode_level5_returnsHeading5Discriminator
  convertBlock_headingBlockNode_level6_returnsHeading6Discriminator
  convertBlock_codeFenceBlockNode_withLanguage_returnsCodeFenceWithLanguage
  convertBlock_codeFenceBlockNode_nullLanguage_returnsCodeFenceWithEmptySuffix
  convertBlock_codeFenceBlockNode_emptyLanguage_returnsCodeFenceWithEmptySuffix
  convertBlock_blockquoteBlockNode_returnsBlockquoteDiscriminator
  convertBlock_orderedListItemBlockNode_number1_returnsOrderedList1
  convertBlock_orderedListItemBlockNode_number99_returnsOrderedList99
  convertBlock_thematicBreakBlockNode_returnsThematicBreakDiscriminator
  convertBlock_tableBlockNode_returnsTableDiscriminator
  convertBlock_rawHtmlBlockNode_returnsRawHtmlDiscriminator
```

Each test constructs the appropriate `BlockNode` subtype with minimal content, calls `MarkdownParser().convertBlock(node)`, and asserts `parsedBlock.blockType` equals the expected `BlockType` variant.

Key assertions for the split round-trip (tested as a pure string operation, no parser involvement):

```
DiscriminatorRoundTripTest
  heading2_discriminator_splitReturnsPrefix_heading_andSuffix_2
  codeFenceKotlin_discriminator_splitReturnsPrefix_code_fence_andSuffix_kotlin
  codeFenceEmpty_discriminator_splitReturnsEmptySuffix
  orderedList5_discriminator_splitReturnsPrefix_ordered_list_andSuffix_5
  bullet_discriminator_splitReturnsEmptySuffix_or_singleElement
  thematicBreak_discriminator_split_returnsSingleElement
```

Verify: `"heading:2".split(":", limit = 2)` gives `["heading", "2"]`; `"code_fence:".split(":", limit = 2)` gives `["code_fence", ""]`; `"bullet".split(":", limit = 2)` gives `["bullet"]`.

**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/model/ParsedBlockDefaultsTest.kt`

```
ParsedBlockDefaultsTest
  parsedBlock_constructedWithoutBlockType_defaultsToBlockTypeBullet
  parsedBlock_blockTypeField_isIncludedInEquality
  parsedBlock_copyWithDifferentBlockType_producesDistinctInstance
```

These verify that the new `blockType` field does not break existing callers that omit it (default = `BlockType.Bullet`).

**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/model/BlockModelTest.kt`

New tests appended to existing `BlockModelTest` (or created fresh if none exists):

```
BlockModelTest
  block_withBulletBlockType_isValid
  block_withHeadingBlockType_isValid
  block_withCodeFenceBlockType_isValid
  block_withAllKnownDiscriminatorPrefixes_isValid
  block_withUnknownBlockType_throwsIllegalArgumentException
  block_defaultBlockType_isBullet
  block_blockTypeField_survivesDataClassCopy
```

The validation test uses `assertFailsWith<IllegalArgumentException>` with an unknown discriminator string such as `"unknown_type"`.

**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/model/BlockTypeMapperTest.kt`

```
BlockTypeMapperTest
  blockTypeBullet_toDiscriminatorString_returnsBullet
  blockTypeParagraph_toDiscriminatorString_returnsParagraph
  blockTypeHeading1_toDiscriminatorString_returnsHeading1
  blockTypeHeading6_toDiscriminatorString_returnsHeading6
  blockTypeCodeFenceKotlin_toDiscriminatorString_returnsCodeFenceKotlin
  blockTypeCodeFenceEmpty_toDiscriminatorString_returnsCodeFenceColon
  blockTypeBlockquote_toDiscriminatorString_returnsBlockquote
  blockTypeOrderedListItem1_toDiscriminatorString_returnsOrderedListItem1
  blockTypeThematicBreak_toDiscriminatorString_returnsThematicBreak
  blockTypeTable_toDiscriminatorString_returnsTable
  blockTypeRawHtml_toDiscriminatorString_returnsRawHtml
  toDiscriminatorString_andFromDiscriminatorString_areInverse (property)
```

The inverse round-trip property test iterates all `BlockType` variants (headings 1-6, code fence with several languages, ordered list items 1-99), converts to string, converts back, and asserts equality.

### Integration Tests

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/BlockTypeRepositoryRoundTripTest.kt`

Strategy: use `RepositoryFactoryImpl` with an in-memory SQLite driver (same pattern as `DatabaseIntegrationTest`). For the SQLDelight backend test, use the JVM `DriverFactory` with `jdbc:sqlite::memory:`.

```
BlockTypeRepositoryRoundTripTest
  insertBlock_withHeadingBlockType_readBackPreservesBlockType
  insertBlock_withCodeFenceKotlinBlockType_readBackPreservesBlockType
  insertBlock_withTableBlockType_readBackPreservesBlockType
  insertBlock_withBulletBlockType_readBackPreservesBlockType
  insertThenUpdateContent_blockTypeUnchanged
```

Each test:
1. Constructs a `Block` with a specific `blockType`
2. Calls `blockRepository.saveBlock(block)` (SQLDelight backend)
3. Calls `blockRepository.getBlocksForPage(pageUuid).first()`
4. Asserts `retrieved.blockType == expected`

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/BlockTypeMigrationTest.kt`

```
BlockTypeMigrationTest
  existingDatabase_afterMigration2_allRowsHaveBulletBlockType
  migrationAddsColumn_withNotNullConstraint
  migrationAddsColumn_withDefaultBullet
```

Strategy: create an in-memory SQLite database with schema version 1 (without `block_type`), insert several rows, run the migration SQL from `2.sqm`, then assert all existing rows have `block_type = 'bullet'` and the column is `NOT NULL`.

Since SQLDelight migrations run automatically in the production driver, this test can either (a) run raw JDBC on an in-memory SQLite database to simulate the before/after states, or (b) create a temporary file-backed SQLite database at schema version 1, close it, reopen with the new schema version, and verify the DEFAULT was applied.

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/GraphLoaderBlockTypeTest.kt`

```
GraphLoaderBlockTypeTest
  loadPage_withHeadingMarkdown_parsedBlocksHaveHeadingBlockType
  loadPage_withCodeFenceMarkdown_parsedBlocksHaveCodeFenceBlockType
  loadPage_withTableMarkdown_parsedBlocksHaveTableBlockType
  loadPage_withThematicBreakMarkdown_parsedBlocksHaveThematicBreakBlockType
  loadPage_withBlockquoteMarkdown_parsedBlocksHaveBlockquoteBlockType
  loadPage_withOrderedListMarkdown_parsedBlocksHaveOrderedListBlockType
  loadPage_withMixedMarkdown_eachBlockHasCorrectBlockType
  loadPage_withBulletOnlyMarkdown_existingBlockTypeDefaultsPreserved
```

Strategy: write markdown fixture files to a temp directory using the same pattern as `GraphLoaderTest.createFixtureGraph()`, call `graphLoader.loadGraph(path)`, then query `blockRepository.getBlocksForPage(pageUuid).first()` and assert `blockType` on each `Block`.

Fixture markdown for the "mixed" test:

```markdown
# Page Title

A paragraph of text.

- A bullet item
- Another bullet

1. First ordered item
2. Second ordered item

> A blockquote

| Col A | Col B |
|-------|-------|
| val1  | val2  |

---

```kotlin
fun example() = "hello"
```
```

---

## Story 2: Block Renderer Dispatch Tests

### Unit Tests

**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/components/BlockItemDispatchTest.kt`

Note: pure dispatch logic can be tested in `commonTest` only if the dispatch function is extracted as a pure function. If `BlockItem` is `@Composable`, these tests belong in `jvmTest` with Compose test infrastructure.

```
BlockItemDispatchLogicTest (commonTest if dispatch is extracted, jvmTest otherwise)
  blockTypeHeading_dispatchSelectsHeadingBranch
  blockTypeParagraph_dispatchSelectsBulletViewerPath
  blockTypeBullet_dispatchSelectsBulletViewerPath
  blockTypeCodeFence_dispatchSelectsCodeFenceBranch
  blockTypeBlockquote_dispatchSelectsBlockquoteBranch
  blockTypeOrderedListItem_dispatchSelectsOrderedListBranch
  blockTypeThematicBreak_dispatchSelectsThematicBreakBranch
  blockTypeTable_dispatchSelectsTableBranch
  blockTypeRawHtml_dispatchSelectsRawHtmlBranch (or falls back gracefully)
  unknownBlockType_dispatchDoesNotCrash_fallsBackToViewer
```

For Compose-layer tests, use `composeTestRule.setContent { }` and `onNodeWithText` or semantic assertions to verify the correct composable renders. Do not assert on internal implementation (which function is called); assert on the rendered output — e.g. for heading, check that `MaterialTheme.typography.displaySmall` font size was applied via a `SemanticsNode` custom action or a test tag.

Use `Modifier.testTag("block-heading")`, `Modifier.testTag("block-code-fence")`, etc. on each composable branch, then assert `onNodeWithTag("block-heading").assertIsDisplayed()` in the dispatch tests.

### Screenshot Tests (Roborazzi)

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/BlockComposableScreenshotTest.kt`

Pattern follows `DesktopScreenshotTest`: `createComposeRule()`, `StelekitTheme { }`, `captureRoboImage("build/outputs/roborazzi/<name>.png")`.

```
HeadingBlockScreenshotTest
  heading_h1_light
  heading_h1_dark
  heading_h2_light
  heading_h3_light
  heading_h4_light
  heading_h5_light
  heading_h6_light
  heading_h1_withBoldInlineContent_light
  heading_allLevels_light (single screenshot showing H1-H6 stacked)
```

Each test sets content to `HeadingBlock(content = "## Section Title", level = N, onStartEditing = {})` inside `StelekitTheme`. The "all levels" composite screenshot is the primary regression baseline.

```
CodeFenceBlockScreenshotTest
  codeFence_withLanguageKotlin_light
  codeFence_withLanguageKotlin_dark
  codeFence_withNoLanguage_light
  codeFence_withLongLine_showsHorizontalScrollIndicator
  codeFence_withEmptyBody_light
```

```
BlockquoteBlockScreenshotTest
  blockquote_singleLine_light
  blockquote_singleLine_dark
  blockquote_withInlineBold_light
  blockquote_withWikiLink_light
```

```
OrderedListScreenshotTest
  orderedListItem_number1_light
  orderedListItem_number10_light (verify alignment with double-digit numbers)
  orderedListItem_withInlineContent_light
```

```
ThematicBreakScreenshotTest
  thematicBreak_light
  thematicBreak_dark
```

```
TableBlockScreenshotTest (Story 3 composable, tested here for convenience)
  table_twoColumns_defaultAlignment_light
  table_threeColumns_mixedAlignment_light
  table_headerOnly_noBodyRows_light
  table_manyRows_light
  table_light
  table_dark
```

Baseline images are committed to `kmp/src/jvmTest/resources/roborazzi/` and compared on CI with Roborazzi's default pixel-diff strategy. Update baselines with `./gradlew recordRoborazziJvm` after intentional visual changes.

---

## Story 3: Table Renderer Tests

### Unit Tests

**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/components/ParseTableContentTest.kt`

`parseTableContent` is a pure function with no `@Composable` annotation, so it belongs in `commonTest`.

```
ParseTableContentTest
  parseTableContent_twoColumnTable_parsesHeadersCorrectly
  parseTableContent_twoColumnTable_parsesBodyRowsCorrectly
  parseTableContent_leftAlignmentMarker_returnsAlignmentLeft
  parseTableContent_rightAlignmentMarker_returnsAlignmentRight
  parseTableContent_centerAlignmentMarker_returnsAlignmentCenter
  parseTableContent_noAlignmentMarker_returnsNullAlignment
  parseTableContent_headerOnlyTable_noBodyRows_returnsEmptyRowList
  parseTableContent_singleBodyRow_returnsOneRow
  parseTableContent_cellsWithLeadingAndTrailingPipes_stripsThemCorrectly
  parseTableContent_cellContentWithInlineMarkdown_preservesRawMarkdown
  parseTableContent_emptyCell_returnsEmptyString
  parseTableContent_tableWithExtraWhitespace_trimsCorrectly
  parseTableContent_unevenColumnCount_doesNotCrash
```

Input fixture for the mixed-alignment test:

```
| Left | Center | Right |
|:-----|:------:|------:|
| a    | b      | c     |
```

Expected: `TableData(headers=["Left","Center","Right"], alignments=[LEFT, CENTER, RIGHT], rows=[["a","b","c"]])`

### Boundary Tests

```
ParseTableContentBoundaryTest
  table_withZeroBodyRows_headerOnly
  table_withOneBodyRow
  table_with100BodyRows_doesNotCrash
  table_with1Column_parsesCorrectly
  table_withVeryLongCellContent_doesNotCrash
  table_withPipeCharacterInCellContent_handledGracefully
```

### Compose Tests

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/TableBlockScrollTest.kt`

```
TableBlockScrollTest
  tableBlock_withNarrowViewport_horizontalScrollEnabled
  tableBlock_parseTableContent_calledOncePerContentChange (remember caching)
```

The scroll test sets the compose content width to a constrained `Modifier.width(200.dp)` and uses `onNodeWithTag("table-scroll-container").assertScrollableInDirection(Horizontal)` (or an equivalent Compose semantics assertion).

---

## Story 4: Image + Inline Polish Tests

### Unit Tests

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/SteleKitAssetFetcherTest.kt`

`SteleKitAssetFetcher` requires JVM file I/O, so it belongs in `jvmTest`:

```
SteleKitAssetFetcherTest
  fetch_relativeAssetPath_resolvesAgainstGraphRoot
  fetch_dotDotAssetsPrefix_resolvedToAbsolutePath
  fetch_dotAssetsPrefix_resolvedToAbsolutePath
  fetch_absoluteHttpUrl_notIntercepted (returns null / defers to default)
  fetch_nullGraphRoot_doesNotCrash
  fetch_nonExistentFile_returnsErrorOrNull (not a crash)
  rememberImageLoader_sameGraphRoot_returnsSameInstance (memoisation)
  rememberImageLoader_differentGraphRoot_returnsDifferentInstance
```

**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngineSubscriptTest.kt`

`AnnotatedString` building is pure Compose-independent logic if the `parseMarkdownWithStyling` function returns `AnnotatedString`. Test in `commonTest` if the function is accessible there; otherwise move to `jvmTest`.

```
MarkdownEngineSubscriptTest
  subscriptNode_appliesNegativeBaselineShift
  subscriptNode_appliesReducedFontSize
  superscriptNode_appliesPositiveBaselineShift
  superscriptNode_appliesReducedFontSize
  subscript_andSuperscript_distinctBaselineShifts
```

**Location**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngineLatexTest.kt`

```
MarkdownEngineLatexTest
  latexInlineNode_dollarDelimitersStripped_contentPreserved
  latexInlineNode_doubleDollarDelimitersStripped_contentPreserved
  latexInlineNode_appliesMonospaceFontFamily
  latexInlineNode_appliesItalicFontStyle
  latexInlineNode_appliesCodeBackground
  latexInlineNode_emptyFormula_doesNotCrash
```

### Screenshot Tests

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/screenshots/InlinePolishScreenshotTest.kt`

```
InlinePolishScreenshotTest
  subscriptAndSuperscript_light (H2O, E=mc^2 examples)
  latexInlineMonospace_light
  imageBlock_withPlaceholder_light (Coil loading state)
  imageBlock_withErrorState_light (404 URL)
```

The image tests use Coil's test infrastructure (`FakeImageEngine` or a `MockEngine` for the Ktor network client) to return controlled states without network access.

---

## Regression Tests

**Location**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/BlockItemRegressionTest.kt`

```
BlockItemRegressionTest
  existingBulletBlock_afterDispatchWiring_rendersIdenticallyToBaseline
  paragraphBlock_afterDispatchWiring_rendersIdenticallyToBaseline
  bulletBlock_withBoldInlineContent_inlineRenderingUnchanged
  bulletBlock_withItalicInlineContent_inlineRenderingUnchanged
  bulletBlock_withWikiLink_inlineRenderingUnchanged
  bulletBlock_withTag_inlineRenderingUnchanged
  bulletBlock_withBlockRef_inlineRenderingUnchanged
  headingBlock_withBoldInline_boldStillRendered
  codeBlock_withBoldInline_boldNotRendered (code content is verbatim)
  blockquoteBlock_withWikiLink_wikiLinkStillClickable
  orderedListBlock_withItalic_italicStillRendered
  editMode_basicTextField_unaffectedByViewModeChanges
  editMode_isActivated_onDoubleClick (existing behavior preserved)
  editMode_lostFocus_returnsToViewMode (existing behavior preserved)
```

For edit-mode regression tests, use `composeTestRule.onNodeWithTag("block-editor").assertIsDisplayed()` after simulating a double-click, then assert the `BasicTextField` is present and the view-mode composable is absent.

The comparison strategy for "renders identically" is: capture a Roborazzi baseline screenshot before the dispatch wiring change (recorded on the `main` branch or a pre-story-2 commit), commit it, then run the regression test after story 2 to confirm pixel-level equivalence for bullet and paragraph blocks.

---

## Test Execution Order

The following order minimizes false failures from unimplemented dependencies:

1. **Story 1 unit tests** (no UI, no Compose) — `./gradlew jvmTest --tests "*.BlockTypeDiscriminatorTest"` etc.
2. **Story 1 model validation tests** — `./gradlew jvmTest --tests "*.BlockModelTest"`
3. **Story 1 mapper tests** — `./gradlew jvmTest --tests "*.BlockTypeMapperTest"`
4. **Story 1 integration tests** — `./gradlew jvmTest --tests "*.BlockTypeRepositoryRoundTripTest"` and `*.BlockTypeMigrationTest`
5. **Story 1 pipeline integration** — `./gradlew jvmTest --tests "*.GraphLoaderBlockTypeTest"` — verifies end-to-end before any UI work
6. **Full regression gate** — `./gradlew jvmTest` — all pre-existing tests must still pass at this checkpoint
7. **Story 2 dispatch tests** — `./gradlew jvmTest --tests "*.BlockItemDispatchTest"`
8. **Story 2 screenshot baselines** — `./gradlew recordRoborazziJvm` — commit PNG baselines
9. **Story 3 parse unit tests** — `./gradlew jvmTest --tests "*.ParseTableContentTest"`
10. **Story 3 Compose tests and screenshots** — `./gradlew jvmTest --tests "*.TableBlockScrollTest"` + record baselines
11. **Story 4 fetcher unit tests** — `./gradlew jvmTest --tests "*.SteleKitAssetFetcherTest"`
12. **Story 4 inline unit tests** — `./gradlew jvmTest --tests "*.MarkdownEngineLatexTest"` etc.
13. **Regression sweep** — `./gradlew jvmTest` — final full pass
14. **All-platform sweep** — `./gradlew allTests` — catch any `commonTest` / `androidUnitTest` breakage

---

## Definition of Done

- [ ] All unit tests pass in `./gradlew jvmTest`
- [ ] All pre-existing `commonTest` and `jvmTest` tests pass without modification
- [ ] Screenshot baselines committed under `kmp/src/jvmTest/resources/roborazzi/` for: `HeadingBlock` (H1-H6 composite), `CodeFenceBlock` (with/without language), `BlockquoteBlock`, `OrderedListItemBlock`, `ThematicBreak`, `TableBlock` (alignment variants), `InlinePolish` (subscript/superscript/latex)
- [ ] No screenshot regression against committed baselines (`./gradlew verifyRoborazziJvm` passes)
- [ ] Migration tested: pre-migration rows confirmed to receive `"bullet"` default via `BlockTypeMigrationTest`
- [ ] `Block.init` rejects unknown `blockType` strings (verified by `BlockModelTest.block_withUnknownBlockType_throwsIllegalArgumentException`)
- [ ] Edit mode (`BasicTextField`) regression confirmed: existing edit-mode tests pass and new `BlockItemRegressionTest` edit-mode cases pass
- [ ] Inline rendering regression confirmed: bold, italic, wiki links, tags, block refs all render correctly inside heading, blockquote, and ordered list composables
- [ ] `parseTableContent()` is wrapped in `remember(content)` inside `TableBlock` (performance requirement, verified by `TableBlockScrollTest.parseTableContent_calledOncePerContentChange`)
- [ ] `SteleKitAssetFetcher` handles null `graphRoot` without crashing (verified by `SteleKitAssetFetcherTest.fetch_nullGraphRoot_doesNotCrash`)
- [ ] Manual smoke test on JVM desktop: open a page with H1-H6 headings, a code fence, a table, a blockquote, a thematic break, and an ordered list — all render visually correctly in view mode
- [ ] `./gradlew allTests` passes (JVM + Android unit tests; iOS skipped on non-macOS CI)
