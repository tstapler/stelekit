# Migration Plan: Publishing & Export

## 1. Discovery & Requirements
Publishing allows users to export their graph as a static website or raw files.

### Existing Artifacts
- `src/main/frontend/publishing.cljs`: Static site generation logic.
- `src/main/frontend/components/export.cljs`: UI for export options.

### Functional Requirements
- **Static HTML**: Generate a read-only version of the graph (HTML/JS/CSS).
- **Raw Export**: Export graph as Markdown/JSON/EDN.
- **OPML**: Export page/block structure as OPML.
- **Assets**: Ensure images and assets are copied to the export folder.

### Non-Functional Requirements
- **Performance**: Exporting a large graph (10k pages) should not crash the app (OOM).
- **Correctness**: The exported HTML should look identical to the app.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **ExportService**: Orchestrates the export process.
- **HtmlGenerator**:
    - *Strategy*: We cannot easily run the Compose UI to generate HTML.
    - *Solution*: Bundle a "Web Viewer" (React/JS app) and generate a JSON data dump that the viewer loads. This is how the current publishing works.
- **FormatConverter**: Logic to convert Block/Page models to OPML/JSON.

### UI Layer (Compose Multiplatform)
- **Component**: `ExportDialog`.
- **Component**: `ProgressIndicator` (Exporting can take time).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Performance: Memory Exhaustion [SEVERITY: High]
- **Description**: Loading the entire graph into memory to serialize it to JSON can cause OOM.
- **Mitigation**: Stream the data. Read from DB -> Write to JSON Stream. Do not hold the whole graph in RAM.

### 🐛 Logic: Broken Links in Export [SEVERITY: Medium]
- **Description**: Absolute paths or internal UUID links might break in the static export.
- **Mitigation**: Implement a "Link Rewriter" that converts internal links to relative HTML links (e.g., `[[Page]]` -> `./pages/Page.html`).

## 4. Implementation Roadmap

### Phase 1: Raw Exports
- [ ] Implement JSON/Markdown export (Streaming).
- [ ] Implement OPML export.

### Phase 2: Static Site
- [ ] Create a "Lightweight" Web Viewer (or reuse existing one).
- [ ] Implement the "Data Dump" generator compatible with the viewer.

### Phase 3: UI
- [ ] Create Export Dialog with progress tracking.

## 5. Migration Checklist
- [ ] **Logic**: Exported JSON is valid.
- [ ] **Logic**: Static site loads in browser.
- [ ] **Performance**: Large graph export succeeds.
- [ ] **Parity**: OPML export structure matches legacy.

