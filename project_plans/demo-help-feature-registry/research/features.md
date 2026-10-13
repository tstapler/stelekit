# Research: FEATURES dimension -- demo-help-feature-registry

Commit 5d00781169 (branch feat/git-sync-branch-fix). Labels: VERIFIED = command run / file opened this session; INFERRED = reasoned, not executed. Paths are under `kmp/src/commonMain/kotlin/dev/stapler/stelekit/` (abbrev `K/`) unless noted.

## Part A: Repo inventory

### A1. Candidate feature sources (counts recomputed)

| Source | Location | Count | Notes |
|---|---|---|---|
| `Screen` sealed class | `K/ui/AppState.kt:36-90` | 15 subclasses (5 `@HelpPage`, 10 `@HelpExempt`) VERIFIED (grep over lines 36-90) | `Journals, Flashcards, AllPages, LibraryStats, Notifications, Logs, Performance, GlobalUnlinkedReferences, Import, VaultUnlock, PageView, Gallery, AssetBrowser, AssetDetail, AnnotationEditor` |
| `SettingsCategory` enum | `K/ui/components/settings/SettingsDialog.kt:453-467` | 13 entries VERIFIED (grep count of `NAME(` lines = 13). Requirements.md says 14; that is off by one | GENERAL, EDITOR, PLUGINS, ADVANCED, VOICE, TAG_SUGGESTIONS, LLM_PROVIDERS, AUDIOBOOK_NOTES, GOOGLE_ACCOUNT, VAULT, SECTIONS, DEVICE_SUBSCRIPTIONS, DEVELOPER |
| `EditorCommand(` | `K/editor/commands/EssentialCommands.kt` | 24 ids VERIFIED (`id = "..."` grep): text.{bold,italic,code,strikethrough,highlight,link,heading}, block.{new,delete,move-up,move-down,indent,outdent,toggle-todo}, navigation.{search,goto-page,back,forward}, media.image, system.{save,undo,redo,settings,help} | Already has structured ids with category prefix: closest thing to a ready-made registry row. Other constructors: `editor/commands/CommandTypes.kt`, `model/EditorCommand.kt`. I did not find a separate "palette list" in `StelekitViewModel` (grep for paletteCommands/buildCommands: none); palette source needs a follow-up. |
| `FormatAction` enum | `K/ui/screens/JournalsViewModel.kt:26-42` | 12 (BOLD, ITALIC, STRIKETHROUGH, HIGHLIGHT, CODE, LINK, QUOTE, NUMBERED_LIST, HEADING, CODE_BLOCK, TABLE_INSERT + one more) INFERRED count from the visible lines; recount before use | Bound to keys via `ShortcutTable` |
| `ShortcutTable` | `K/ui/components/ShortcutTable.kt:24` | 9 key bindings VERIFIED (lines 40-49); CODE_BLOCK and TABLE_INSERT have none | Single source for shortcuts; `ShortcutTableConsistencyTest` pins it |
| `SyncState` sealed | `K/git/model/SyncState.kt` | 14 states VERIFIED (grep `^    data`) | Idle, Fetching, MergeAvailable, Merging, Pushing, Committing, CredentialVaultLocked, ConflictPending, JournalMergeReady, Error, CredentialExpired, LocalChangesPending, RateLimited, Success. These are states, not features: a "Git sync" feature would own all 14 |
| Sidebar nav items | `K/ui/components/Sidebar.kt:302-320` | 13 `NavigationItem(` call sites VERIFIED | Journals, New Section Journal (conditional), All Pages, Flashcards, Unlinked References, Notifications, Gallery, Assets, Library Stats (+ more below line 320, not read). `Library Stats` and `Notifications` are in the nav even though their `@HelpExempt` reasons say "not reachable from user nav" / "users do not navigate to it" -- check whether the item is gated; the exemption text and nav are in tension (INFERRED: gating not checked) |
| Block types | `K/model/ParsedModels.kt:17-33` `BlockType` | 12 variants VERIFIED: Bullet, Paragraph, Heading, CodeFence, Blockquote, OrderedListItem, ThematicBreak, Table, RawHtml, ImageAnnotation, Query, Unknown | Parser is `parser/{MarkdownParser,MarkdownPreprocessor,PropertiesParser,TimestampParser}.kt`. Inline syntax (bold, `[[link]]`, `^^highlight^^`, tags) is not an enum; derive from `FormatAction` |
| README/landing claims | `README.md`, `site/src/pages/index.astro:321-381` | Landing has 5 feature cards: Outliner, Journals, Bidirectional links, Search, Local-first VERIFIED. Landing comparison table lines 392-445 is platform/licence, not features. README has no feature list (prose + "Project Status" limitations, lines 144-160) | Marketed set today = {outliner, journals, backlinks, search, local-first}. Flashcards, git sync, queries, AI, vault, voice are unmarketed |
| `I18n` | `K/ui/i18n/I18n.kt` | not counted | String keys (`menu.help`, TopBar.kt:362); not a feature source |

### A2. Existing content, mapped to features

Demo pages (18, `ls | wc -l` VERIFIED): About SteleKit, All Pages, Backlinks, Block Editing, Block Editor Reference, Daily Notes, Flashcards, Images, Keyboard Shortcuts, Markdown Formatting, Page Linking, Properties, Search, Start Here, Tables, Tags & Highlight, Tasks, Unlinked References.
Site user docs (9 VERIFIED): all-pages, backlinks, block-editing, daily-notes, flashcards, getting-started, outliner, search, unlinked-references (.mdx).
Kotlin docs (`docs/ScreenDocs.kt`, 6 classes VERIFIED; titles at lines 5, 27, 50, 76, 97, 135, 157/165): JournalsDocs("Daily Notes"), FlashcardsDocs("Flashcards"), AllPagesDocs("All Pages"), PageViewDocs ("Block Editing" + "Block Editor Reference"), SearchDocs("Search"), GlobalUnlinkedReferencesDocs("Unlinked References"). Registered in `AppDocsInitializer`: 5 (all but GlobalUnlinkedReferencesDocs) VERIFIED.

### A3. Coverage matrix (feature x surface)

Y = present, - = absent. "Kotlin doc" = a `*Docs` class; note none is rendered in any UI (requirements.md appendix, GAP).

| Feature | Demo page | Kotlin doc | Site doc | README/landing |
|---|---|---|---|---|
| Journals / daily notes (`Screen.Journals`) | Daily Notes | JournalsDocs | daily-notes | landing "Journals", README prose |
| Flashcards (`Screen.Flashcards`) | Flashcards | FlashcardsDocs | flashcards | - |
| All Pages (`Screen.AllPages`) | All Pages | AllPagesDocs | all-pages | - |
| Unlinked references (`Screen.GlobalUnlinkedReferences`) | Unlinked References | GlobalUnlinkedReferencesDocs (unregistered) | unlinked-references | - |
| Page view / block editing (`Screen.PageView`) | Block Editing, Block Editor Reference | PageViewDocs | block-editing, outliner | landing "Outliner" |
| Search (no Screen; SearchDocs orphaned) | Search | SearchDocs (no Screen uses it) | search | landing "Search" |
| Backlinks / linking | Backlinks, Page Linking | - | backlinks | landing "Bidirectional links" |
| Keyboard shortcuts (`ShortcutTable`) | Keyboard Shortcuts | - | - | README: only the capture shortcut (line 153) |
| Markdown formatting (FormatAction, BlockType Heading/Blockquote/Ordered/Code) | Markdown Formatting | - | partial in block-editing (INFERRED) | - |
| Tables (BlockType.Table, TABLE_INSERT) | Tables | - | - | - |
| Tasks (block.toggle-todo) | Tasks | - | - | - |
| Properties | Properties | - | - | - |
| Tags and highlight | Tags & Highlight | - | - | - |
| Images / image blocks (media.image) | Images | - | - | - |
| Getting started | Start Here, About SteleKit | - | getting-started | README intro |
| Query blocks `{{query}}` (BlockType.Query, #398) | - (grep for literal `{{query` in demo-graph, site/src, README: 0 hits) | - | - | - |
| Image annotation (`AnnotationEditor`, `Gallery`) | `annot` mentioned in 1 demo file; no page | - (exempt) | - | - |
| Asset browser/detail (`AssetBrowser`, `AssetDetail`) | - | - (exempt) | - | README mentions assets once |
| Git sync + branch repair, conflicts, journal merge (`SyncState`) | - (0 hits) | - | - | - (README says "no sync service", which is about hosted sync) |
| Cross-graph page copy / share target (#397) | - | - | - | - |
| Wiki subdirectory detection (#382, #384) | - | - | - | - |
| Vault / paranoid mode (`VaultUnlock`, VAULT category) | - | - (exempt) | - | - |
| Voice capture, AI providers, tag suggestions, audiobook notes (SettingsCategory) | - | - | - | - |
| Graph diagnostics / log export (#376) | - | - | - | - |
| Notifications, LibraryStats, Logs, Performance, Import | - | - exempt | - | - |

Counts of zero come from `grep -rli` over `kmp/src/commonMain/resources/demo-graph`, `site/src/content`, `site/src/pages`, README.md (VERIFIED, terms: git sync, cross-graph/copy page, vault, voice, diagnostic, wiki subdir/subdirectory all = 0 in all three).

### A4. Gaps

1. Unmarketed/undocumented user-visible features: query blocks, git sync (the whole branch's work), cross-graph copy/share, wiki-subdir, vault, voice capture, AI providers, tag suggestions, audiobook notes, diagnostics export, annotations, assets (VERIFIED zero coverage above).
2. Recent releases with no demo/help/site coverage (CHANGELOG.md, VERIFIED): 0.93.0 cross-graph copy + share target; 0.92.0 resilient clone/fetch/push; 0.91.0/0.90.0 wiki subdir; 0.89.0 logs + diagnostics; 0.88.3 drag-drop image on web. Unreleased on this branch: branch-repair sheet + first-sync review (`git log` f982adf5e0). `{{query}}` live blocks (4420be47c6, post-0.93.0).
3. Structural: `@HelpPage` covers only Screens, and `Screen` is not the feature list (git sync, settings, queries, commands are not Screens). So the existing detekt rule `MissingHelpPageAnnotationRule` cannot see them.
4. `GlobalUnlinkedReferencesDocs` unregistered; `SearchDocs` has no Screen; `HelpExempt.kt:11` cites a CLAUDE.md "approved list" that does not exist; `HelpExempt` has SOURCE retention so tests cannot see it and `DemoGraphCoverageTest` hard-codes `KNOWN_EXEMPT_SCREENS` (a 9-name copy of a 10-name set: `AssetDetail` is missing from it, VERIFIED by reading the test) so its "unannotated (gap)" summary would list `AssetDetail`.
5. 11 of 18 demo pages (Backlinks, Page Linking, Properties, Tags & Highlight, Tasks, Tables, Images, Markdown Formatting, Keyboard Shortcuts, Start Here, About) have no Kotlin doc; 4 of those have no site page either. No test links a demo page to a feature other than via `@HelpPage` titles.
6. Site docs are a hand-copy (flashcards.mdx 68 lines vs Flashcards.md): no drift check.

## Part B: How other projects keep docs/demos/feature lists in sync

Fetched and read this session (VERIFIED): kotlinx-knit README, Home Assistant manifest docs. The rest are INFERRED from prior knowledge; URLs given for the follow-up verification.

1. **kotlinx-knit** (https://github.com/Kotlin/kotlinx-knit) VERIFIED. Markdown is the source; `knit` generates Kotlin examples + tests committed to VCS; `knitCheck` "checks that all the files are up-to-date and fail the build if not", wired into `check`. Lesson: generate-and-commit plus a check task that only diffs (same shape as this repo's SQLDelight and `readme-sync` jobs). Cheap, deterministic, no network.
2. **Home Assistant manifest + hassfest** (https://developers.home-assistant.io/docs/creating_integration_manifest/) VERIFIED for the manifest (required `manifest.json` per integration with domain, name, documentation URL, codeowners); hassfest validator is INFERRED. Lesson: one small declarative manifest per feature with required fields (docs URL, owner), validated by a script in CI; a feature without one cannot exist. Directly maps to a `FeatureSpec` row with demo/help/site pointers.
3. **rustdoc doctests** (https://doc.rust-lang.org/rustdoc/write-documentation/documentation-tests.html) INFERRED. Docs examples compile and run as tests, so stale examples fail. Lesson: make the demo graph executable -- load every demo page through the real loader (this plan's success metric) so a parser change that breaks a demo page fails CI.
4. **Storybook** (https://storybook.js.org/docs/writing-tests) INFERRED. Stories are the registry; test-runner renders each; coverage is "does a story exist for component X". Lesson: gallery coverage is component-level, so keep it a sibling of the feature registry (see Q3), not the source for it.
5. **VS Code contribution points** (https://code.visualstudio.com/api/references/contribution-points) INFERRED. Features declared statically in `package.json` (`contributes.commands`, `keybindings`, `configuration`); the Marketplace and Settings UI render docs from the manifest. Lesson: static declaration lets docs/UX derive from the declaration, and each command has a stable id plus a title -- like this repo's `EditorCommand.id`.
6. **Kubernetes feature gates** (https://kubernetes.io/docs/reference/command-line-tools-reference/feature-gates/) INFERRED. Each gate is declared in code with stage/version; the docs page lists them and a verify script compares the two. Lesson: derive-from-code plus a documented-list diff catches both "added gate, no doc" and "removed gate, doc remains" (two-way check, not only code -> docs).
7. **Docs-as-tests / link checkers (e.g. Vale, lychee, Docusaurus `onBrokenLinks`)** (https://docusaurus.io/docs/api/docusaurus-config#onBrokenLinks) INFERRED. Build-level broken-link failure is the standard, low-noise gate for site content. Lesson: put the site link check in the site build, separate from the feature coverage gate, so noise in one does not mask the other.
8. **Detekt baseline ratchet (this repo)** `.github/workflows/ci.yml:157-166` VERIFIED per requirements.md. Lesson: baseline file may only shrink -- reuse as the rollout mechanism for the coverage gate rather than inventing a new one.

## Answers

**What is a feature as a data type?** A stable-id record, not an enum value. Proposed minimum (INFERRED design): `Feature(id: String /* "git-sync" */, title, kind: {SCREEN, COMMAND, SETTING, SYNTAX, SYSTEM}, audience: {MARKETED, DEMO, INTERNAL}, sources: List<SourceRef>, demoPage: String?, helpDoc: KClass<*>?, sitePage: String?, exempt: reason?)`. Ids are stable strings, as `EditorCommand.id` already does; `SyncState`'s 14 states and `SettingsCategory`'s 13 entries are inputs that roll up to a smaller number of features (e.g. 14 states -> 1 feature), so the registry has to hold a many-to-one mapping from source-enum members to feature ids. Features outnumber Screens (15) and the matrix above has ~22 rows, of which only 6 have `@HelpPage`.

**Which existing registries can be the source of truth?** None is complete alone. Usable as *discovery* inputs (derive): `Screen` (reflection, already done by `DemoGraphCoverageTest`), `SettingsCategory`, `EditorCommand.id`, `BlockType`, `SyncState`, `ShortcutTable`/`FormatAction`. Features such as cross-graph copy, wiki-subdir, diagnostics, and queries-as-UI exist in none of these as a single named entity, so a declared layer is unavoidable (derive-vs-declare resolves to: derive the *obligation list* from the enums, declare the *feature rows* and check every enum member maps to one -- the hassfest/feature-gate pattern). `Screen`+`@HelpPage` is the only enforced slice today and can stay as one adapter. Constraint: `@HelpExempt` has SOURCE retention (invisible to tests), so either switch it to RUNTIME or move exemptions into the registry data.

**Is ComponentGallery the same or a sibling?** Sibling. VERIFIED: `kmp/src/jvmTest/.../ui/gallery/ComponentGallery.kt:36` is `object ComponentGallery` holding `GalleryStory(id, content)` (comment: "Storybook-style registry of components rendered in isolation"); it lives in jvmTest, uses Compose, and is untracked (`git status`: `?? kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/gallery/`). ~129 `GalleryStory(` occurrences across 6 files (includes the data-class declaration line; recount before quoting). It is keyed by component and pixel output, not by feature, and is jvmTest-only, so it cannot be the registry (the registry must be commonMain/KMP-portable). Useful link: a Feature row could optionally point at gallery story ids so the registry can report features with no visual regression coverage. Gallery files are in jvmTest only; whether it gets committed is an owner decision (requirements Open Question 5).
