# Migration Plan: Graph Visualization

## 1. Discovery & Requirements
The Graph View visualizes the connections between pages and blocks.

### Existing Artifacts
- `src/main/frontend/common/graph_view.cljs`: Main logic.
- `src/main/frontend/extensions/graph.cljs`: Extension points.

### Functional Requirements
- **Nodes & Edges**: Pages are nodes, links are edges.
- **Interactivity**: Zoom, Pan, Drag nodes, Click to navigate.
- **Filtering**: Filter by tags, orphans, etc.
- **Layout**: Force-directed layout.

### Non-Functional Requirements
- **Performance**: Render 10,000+ nodes at 60fps.
- **Startup**: Graph loads in < 1s.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **GraphModel**: `Node`, `Edge`.
- **LayoutEngine**: Force-directed algorithm (e.g., Barnes-Hut simulation) implemented in pure Kotlin.
    - *Optimization*: Use primitive arrays or `FloatArray` for physics simulation to avoid object overhead.

### UI Layer (Compose Multiplatform)
- **Rendering**:
    - Option A: **Compose Canvas** (Good for small graphs).
    - Option B: **Skia (Skiko)** direct access (Better performance).
    - Option C: **OpenGL/Metal** via generic bindings (Best performance).
    - *Decision*: Start with Skia/Canvas. If slow, optimize.

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Performance: Layout Stutter [SEVERITY: High]
- **Description**: Physics simulation is CPU intensive. Running it on the UI thread will freeze the app.
- **Mitigation**: Run the simulation loop in a background Coroutine. Send position updates to the UI thread at 60fps (or lower if needed).

### 🐛 UX: Touch Handling [SEVERITY: Medium]
- **Description**: Pinch-to-zoom and pan gestures can be tricky to get right across Desktop (Mouse/Trackpad) and Mobile (Touch).
- **Mitigation**: Use Compose's `detectTransformGestures`. Test on actual devices early.

## 4. Implementation Roadmap

### Phase 1: Data Preparation
- [ ] Implement `GraphService` to extract Nodes/Edges from DB.
- [ ] Implement filtering logic.

### Phase 2: Physics Engine
- [ ] Port force-directed layout algorithm to Kotlin.
- [ ] Optimize for performance (benchmark with 10k nodes).

### Phase 3: Rendering
- [ ] Implement `GraphCanvas` using Compose.
- [ ] Implement Zoom/Pan logic.
- [ ] Implement Node interaction (Click/Drag).

## 5. Migration Checklist
- [ ] **Logic**: Physics simulation runs efficiently in Kotlin.
- [ ] **UI**: Graph renders nodes and edges.
- [ ] **UI**: Zoom/Pan works smoothly.
- [ ] **Tests**: Benchmark layout algorithm.
- [ ] **Parity**: Visual style matches legacy graph.

