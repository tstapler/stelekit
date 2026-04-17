# Migration Plan: PDF Integration

## 1. Discovery & Requirements
PDF integration allows users to read papers, highlight text, and extract those highlights as blocks in Logseq.

### Existing Artifacts
- `src/main/frontend/extensions/pdf`: Logic for PDF.js wrapper.

### Functional Requirements
- **Rendering**: Display PDF files.
- **Highlighting**: Select text/area to highlight.
- **Extraction**: Copy highlighted text/image to a block.
- **Linking**: Clicking a block reference opens the PDF to the specific page/location.

### Non-Functional Requirements
- **Performance**: Smooth scrolling for large PDFs (100+ pages).
- **Accuracy**: Highlights must align perfectly with text.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **PdfService**: Manage open PDF documents.
- **AnnotationRepository**: Store highlights (coordinates, color, content) associated with a file.
- **CoordinateMapper**: Convert PDF coordinates to/from normalized values (0.0-1.0) to handle different screen densities.

### UI Layer (Compose Multiplatform)
- **Desktop/Web**: Wrap **PDF.js** inside a `WebView` or `CEF` (Chromium Embedded Framework) component. This is the most robust way to maintain feature parity (text selection is hard in native).
- **Mobile**: Use native PDF renderers (Android `PdfRenderer`, iOS `PDFKit`) OR use the same PDF.js WebView approach for consistency.
- *Decision*: **WebView + PDF.js** is recommended for the initial migration to ensure 100% parity with existing highlighting logic.

## 3. Proactive Bug Identification (Known Issues)

### 🐛 UX: Mobile Text Selection [SEVERITY: High]
- **Description**: Selecting text in a PDF on a small touch screen is notoriously difficult.
- **Mitigation**: Implement "Area Highlight" as a fallback. Optimize touch hit targets.

### 🐛 Data: Highlight Drift [SEVERITY: Medium]
- **Description**: If the PDF file is modified externally (e.g., annotated in another app), Logseq's stored coordinates might point to the wrong text.
- **Mitigation**: Store a file hash. Warn user if the hash changes.

## 4. Implementation Roadmap

### Phase 1: Viewer Integration
- [ ] Create a `PdfViewer` Composable that wraps a WebView.
- [ ] Load PDF.js into the WebView.

### Phase 2: Bridge Logic
- [ ] Implement communication: KMP -> WebView (Go to page).
- [ ] Implement communication: WebView -> KMP (Text selected).

### Phase 3: Annotation Sync
- [ ] Implement logic to save highlights to the graph (Markdown/EDN).
- [ ] Implement logic to restore highlights on load.

## 5. Migration Checklist
- [ ] **UI**: PDF renders correctly.
- [ ] **Logic**: Text selection triggers KMP event.
- [ ] **Logic**: Clicking a reference scrolls PDF to location.
- [ ] **Parity**: Highlight colors and styles match legacy.

