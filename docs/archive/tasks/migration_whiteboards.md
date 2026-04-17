# Migration Plan: Whiteboards

## 1. Discovery & Requirements
Whiteboards provide a spatial canvas for organizing blocks, shapes, and connectors.

### Existing Artifacts
- `db/model.cljs`: Data model for canvas.
- *Note*: Current implementation relies heavily on web technologies (DOM/SVG) or a library like Tldraw.

### Functional Requirements
- **Canvas**: Infinite panning and zooming.
- **Elements**: Shapes (Rect, Circle), Arrows, Text, Logseq Blocks.
- **Interaction**: Drag, Resize, Connect, Group.

### Non-Functional Requirements
- **Performance**: Smooth 60fps panning with hundreds of elements.
- **Fidelity**: Must look identical to the web version.

## 2. Architecture & Design (KMP)

### Strategy: Hybrid Approach
Re-implementing a full whiteboard engine in Compose is a massive undertaking (estimated 6+ months).
- **Phase 1 (Migration)**: Use a **WebView** to render the existing CLJS/JS Whiteboard engine. Bridge data to KMP.
- **Phase 2 (Native)**: Evaluate **Compose Canvas** or a KMP port of Tldraw only if performance requires it.

### Logic Layer (Common)
- **WhiteboardRepository**: Store canvas data (JSON/EDN).
- **Bridge**: Communication between KMP and WebView (e.g., "Insert Block").

### UI Layer (Compose Multiplatform)
- **Component**: `WhiteboardView` (Wraps WebView).
- **Component**: `Toolbar` (Native UI for selecting tools, overlaying the WebView).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 UX: Gesture Conflicts [SEVERITY: High]
- **Description**: WebView handles gestures (pan/zoom) differently than native.
- **Mitigation**: Disable native scroll interception on the WebView container. Let the JS engine handle physics.

### 🐛 Data: Sync Latency [SEVERITY: Medium]
- **Description**: If the user edits a block on the canvas (WebView), the KMP database must update immediately.
- **Mitigation**: Optimistic UI updates in WebView. Background sync to KMP DB.

## 4. Implementation Roadmap

### Phase 1: WebView Integration
- [ ] Extract Whiteboard JS code into a standalone bundle.
- [ ] Create `WhiteboardView` Composable.
- [ ] Load local HTML/JS into WebView.

### Phase 2: Data Bridge
- [ ] Implement `loadCanvas(id)`: KMP -> JS.
- [ ] Implement `saveCanvas(data)`: JS -> KMP.

### Phase 3: Native Toolbar (Optional)
- [ ] Move tool selection (Pen, Rect, Arrow) to a native Compose Toolbar for better UX.

## 5. Migration Checklist
- [ ] **UI**: Whiteboard loads and renders.
- [ ] **Logic**: Can create and move shapes.
- [ ] **Logic**: Changes persist to disk.
- [ ] **Parity**: Feature set matches legacy (via WebView).

