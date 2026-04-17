# Feature Parity Plan: ClojureScript to KMP Migration

## Epic Overview

### User Value
As a Logseq user, I want the new Kotlin Multiplatform (KMP) version to have all the features and tools available in the original ClojureScript version, so that I can transition to the new platform without losing any functionality or productivity.

### Success Metrics
- 100% parity for Core Editor features (Outliner, Properties, Journals, etc.)
- 100% parity for UI/UX components (Command Palette, Sidebars, i18n)
- 100% parity for Search capabilities (Global, Datalog, Vector)
- 100% parity for Media/Docs support (PDF, LaTeX, Whiteboards)
- 100% parity for Platform integrations (File System, Git, Plugins)
- 100% parity for Sync/RTC functionality

### Scope

**Included:**
- Core Editor: Outliner pipeline, Block properties, Namespaces, Journals, Templates, Macros.
- UI/UX: Command palette (cmdk), Sidebar (left/right), Settings, Onboarding, i18n.
- Search: Global search, Datalog query builder, Vector search (inference worker).
- Media/Docs: PDF viewer, LaTeX, Code highlighting, Zotero, Whiteboards (tldraw).
- Platform: Multi-platform File System (Node, OPFS, Mobile), Git, E2EE, Plugins.
- Sync/RTC: Real-time collaboration, DB worker.

**Excluded:**
- New features not present in the ClojureScript version (unless required for parity).
- Platform-specific features that are being deprecated.

---

## Phase 1: Core Editor & Outliner Parity

### Story 1: Advanced Outliner Logic
**Task 1.1: Implement Outliner Pipeline (2h)**
- Port the outliner pipeline logic from `deps/outliner/src/logseq/outliner/pipeline.cljs`.
- Implement block transformation and validation rules in KMP.
- **Success Criteria**: Blocks are correctly processed through the pipeline before being saved/rendered.

**Task 1.2: Tree Structure & Hierarchy Management (2h)**
- Implement tree traversal and manipulation logic (move, indent, outdent).
- Ensure parity with `deps/outliner/src/logseq/outliner/tree.cljs`.
- **Success Criteria**: Complex tree operations work identically to the CLJS version.

### Story 2: Properties & Metadata
**Task 2.1: Block Properties Parser & Manager (2h)**
- Implement full support for block properties (parsing from markdown, storing in SQLDelight).
- Support for special properties (alias, tags, etc.).
- **Success Criteria**: Properties are correctly extracted and indexed.

**Task 2.2: Namespaces & Journals (2h)**
- Implement namespace logic (parent/child pages).
- Implement Journal page generation and management.
- **Success Criteria**: Journals and namespaces work as expected.

---

## Phase 2: UI/UX & Navigation

### Story 3: Command Palette & Sidebars
**Task 3.1: Command Palette (cmdk) Implementation (3h)**
- Port the command palette logic and UI.
- Support for dynamic command registration.
- **Success Criteria**: Command palette is accessible and functional.

**Task 3.2: Dual Sidebar System (2h)**
- Implement left (navigation) and right (context/blocks) sidebars.
- Support for pinning and collapsing.
- **Success Criteria**: Sidebars behave identically to the original app.

---

## Phase 3: Search & Querying

### Story 4: Global & Advanced Search
**Task 4.1: Datalog Query Builder (4h)**
- Implement a KMP-compatible Datalog query engine or bridge to existing logic.
- Support for the visual query builder.
- **Success Criteria**: Users can run complex Datalog queries.

**Task 4.2: Vector Search & Inference Worker (4h)**
- Port the inference worker for text-embeddings.
- Implement vector search in KMP (using a library like Milvus or custom implementation).
- **Success Criteria**: Semantic search works across the graph.

---

## Phase 4: Media, Docs & Extensions

### Story 5: Rich Content Support
**Task 5.1: PDF Viewer & Annotation (4h)**
- Integrate a cross-platform PDF viewer.
- Port annotation storage and linking logic.
- **Success Criteria**: PDFs can be viewed and annotated.

**Task 5.2: Whiteboards (tldraw fork) (4h)**
- Integrate the tldraw fork into the KMP UI.
- Ensure data persistence in the Logseq graph.
- **Success Criteria**: Whiteboards are fully functional.

---

## Phase 5: Platform & Infrastructure

### Story 6: File System & Git
**Task 6.1: Unified File System Abstraction (3h)**
- Expand `PlatformFileSystem` to support Node.js (Desktop), OPFS (Web), and Mobile APIs.
- **Success Criteria**: File operations work seamlessly across all targets.

**Task 6.2: Git Integration (3h)**
- Implement Git operations (commit, push, pull) using a KMP-compatible library (e.g., JGit for JVM/Android).
- **Success Criteria**: Users can sync their graphs via Git.

---

## Phase 6: Sync & Collaboration

### Story 7: RTC & Background Tasks
**Task 7.1: Real-Time Collaboration (RTC) (5h)**
- Port the RTC logic from `src/main/frontend/worker/rtc/`.
- Implement conflict resolution and state synchronization.
- **Success Criteria**: Multiple users can edit the same graph simultaneously.

---

## Known Issues & Bug Prevention

### 🐛 Concurrency Risk: Database Write Contention [SEVERITY: High]
**Description**: Concurrent writes to the SQLDelight database from the UI thread and background workers (RTC, Git) may lead to locking issues or data corruption.
**Mitigation**:
- Use a dedicated background dispatcher for all database operations.
- Implement a write-ahead log (WAL) mode for SQLite.
- Add retry logic with exponential backoff for locked database states.
**Prevention Strategy**: Centralize all DB access through a single Repository layer with strict concurrency controls.

### 🐛 Data Integrity: Markdown Parsing Edge Cases [SEVERITY: Medium]
**Description**: Complex markdown structures (nested code blocks, macros within properties) might be parsed differently in KMP compared to the CLJS version.
**Mitigation**:
- Create a comprehensive test suite using real-world markdown samples from the original app.
- Implement a "strict mode" for parsing that logs warnings on ambiguous structures.
**Prevention Strategy**: Use a shared grammar or highly validated parser implementation across both versions during the transition.

### 🐛 Performance: Large Graph Rendering [SEVERITY: High]
**Description**: Rendering thousands of blocks in Compose (Desktop/Mobile) may lead to UI jank if not properly virtualized.
**Mitigation**:
- Use `LazyColumn` and `LazyVerticalGrid` for all list-based views.
- Implement windowing/virtualization for the main outliner view.
**Prevention Strategy**: Profile rendering performance with large graphs (>10k blocks) early in the implementation phase.

### 🐛 Integration Risk: Plugin Sandbox Isolation [SEVERITY: High]
**Description**: Plugins in the CLJS version run in a specific environment; porting this to KMP requires a robust sandbox (especially for Desktop/Mobile).
**Mitigation**:
- Use a WebView-based sandbox for plugins on all platforms.
- Implement a strict API bridge between the KMP core and the plugin environment.
**Prevention Strategy**: Define a clear security boundary and capability-based access for plugins.

---

## Implementation Roadmap

1. **Phase 1 (Weeks 1-4)**: Core Editor & Outliner (Foundation)
2. **Phase 2 (Weeks 5-6)**: UI/UX & Navigation (Shell)
3. **Phase 3 (Weeks 7-9)**: Search & Querying (Intelligence)
4. **Phase 4 (Weeks 10-12)**: Media, Docs & Extensions (Rich Content)
5. **Phase 5 (Weeks 13-14)**: Platform & Infrastructure (System)
6. **Phase 6 (Weeks 15-16)**: Sync & Collaboration (Advanced)

*Generated: January 4, 2026*
*Framework: AIC + ATOMIC*
