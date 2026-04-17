# Tasks

- [ ] Recent Pages — per-graph visit history in left sidebar showing last 10 visited pages, persists across sessions (docs/tasks/recent-pages.md)
- [ ] Copy / Cut / Paste Blocks — Ctrl+C/X/V block-tree clipboard with undo, external Markdown paste, and Ctrl+Shift+V block reference paste (docs/tasks/copy-cut-paste-blocks.md)
- [ ] All Pages View — sortable table of every page with backlink counts, name filter, journal/page toggle, and multi-select bulk delete (docs/tasks/all-pages-view.md)
- [ ] Implement Progressive Data Loading Plan (docs/tasks/progressive-loading.md)
- [ ] Multi-block selection with drag-and-drop reparenting and deletion, including mobile support (docs/tasks/multi-block-selection.md)
- [ ] Task Management UI — TODO/DOING/DONE cycling, priorities, SCHEDULED/DEADLINE chips, agenda section (docs/tasks/task-management-ui.md)
- [ ] Graph View — force-directed knowledge graph visualization with global view, local panel, namespace clustering, and filter controls (docs/tasks/graph-view.md)
- [ ] Templates — mark any block as a reusable template via `template::` property, insert via `/template` slash command with fuzzy picker, dynamic variables `<% today %>` / `<% current page %>` / NLD dates (docs/tasks/templates.md)
- [ ] LaTeX / Math Rendering — inline `$...$` and block `$$...$$` typeset math via `jlatexmath` (JVM/Android), KaTeX (JS), and `expect/actual` MathRenderer composable; LRU cache, error fallback, editor monospace styling (docs/tasks/latex-math-rendering.md)
