# Tasks

- [x] SteleKit Site — Astro + Starlight GitHub Pages site with CI/CD pipeline, landing page, user docs, developer docs, and demo page (PR #3; archived)
- [x] Browser Wasm Demo — `wasmJs` + `CanvasBasedWindow` rendering real Compose UI in-browser via Skia canvas (PR #3; archived)
- [x] Recent Pages — per-graph visit history in left sidebar showing last 10 visited pages, persists across sessions (StelekitViewModel.kt; archived)
- [x] All Pages View — sortable table of every page with backlink counts, name filter, journal/page toggle, and multi-select bulk delete (AllPagesScreen.kt; archived)
- [x] Progressive Data Loading — repository-level pagination via getPages(limit, offset) (SqlDelightPageRepository.kt; archived)
- [ ] Copy / Cut / Paste Blocks — Ctrl+C/X/V block-tree clipboard with undo, external Markdown paste, and Ctrl+Shift+V block reference paste (docs/tasks/copy-cut-paste-blocks.md)
- [ ] Multi-block selection with drag-and-drop reparenting and deletion, including mobile support (docs/tasks/multi-block-selection.md)
- [ ] Task Management UI — TODO/DOING/DONE cycling, priorities, SCHEDULED/DEADLINE chips, agenda section (docs/tasks/task-management-ui.md)
- [ ] Graph View — force-directed knowledge graph visualization with global view, local panel, namespace clustering, and filter controls (docs/tasks/graph-view.md)
- [ ] Templates — mark any block as a reusable template via `template::` property, insert via `/template` slash command with fuzzy picker, dynamic variables `<% today %>` / `<% current page %>` / NLD dates (docs/tasks/templates.md)
- [ ] LaTeX / Math Rendering — inline `$...$` and block `$$...$$` typeset math via `jlatexmath` (JVM/Android), KaTeX (JS), and `expect/actual` MathRenderer composable; LRU cache, error fallback, editor monospace styling (docs/tasks/latex-math-rendering.md)
- [ ] Android SAF Performance — fix Phase 3 / BlockStateManager UUID race condition, eliminate DocumentFile metadata overhead, lazy Phase 3 indexing, and SAF shadow copy for 1000+ page graphs (docs/tasks/android-saf-performance.md)
