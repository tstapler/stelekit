# Graph View — Knowledge Graph Visualization

**Feature ID**: GV-001
**Priority**: High (Logseq signature feature; expected by users)
**Status**: Planned
**Last Updated**: 2026-04-16

---

## Epic Overview

### User Value

Users of knowledge-management tools depend on visual feedback to discover emergent
connections between their notes. Without a graph view, SteleKit is a text editor with
search — with it, users can:

* Immediately see which pages are hubs in their knowledge graph
* Discover orphaned pages that have grown stale
* Navigate to related pages with a single click
* Understand namespace structure spatially

This is Logseq's most visually distinctive feature and the most commonly cited
differentiator against plain markdown editors.

### Success Metrics

| Metric | Target |
|--------|--------|
| Time to first interactive frame (1 000-page graph) | < 2 s |
| Sustained frame rate after initial layout | ≥ 50 fps at 60 Hz |
| Node click → page navigation latency | < 100 ms |
| Test coverage (domain + ViewModel) | ≥ 80 % |
| Zero regressions in existing navigation tests | Pass |

### Scope

**In scope:**
* Global graph view (`Screen.GraphView`) — all pages as nodes, wiki-link references
  as directed edges
* Local graph panel in right sidebar — current page's immediate neighbourhood (1-hop)
* Node click navigates to the page (`StelekitViewModel.navigateTo`)
* Graph filters: show/hide journal pages, show/hide orphan pages, hop-count slider
  (1–5 hops from focus node, global view only)
* Visual namespace clustering (nodes with the same namespace prefix share a colour)
* Sidebar navigation entry and command-palette command

**Out of scope (future):**
* Block-level graph (nodes = blocks)
* Custom node shapes / icons
* Edge-click to inspect references
* Graph export (PNG/SVG)
* Animated graph transitions between page navigations

### Constraints

* Pure KMP/Compose Multiplatform — no WebView, no platform-specific native library
* Must not introduce new external Gradle dependencies (physics loop is hand-written)
* `GraphViewRepository` must be unit-testable with `InMemoryRepositories`
* Local graph panel must not noticeably delay right-sidebar open time (lazy coroutine
  load with placeholder)

---

## Architecture Decisions (ADRs)

| File | Decision |
|------|----------|
| `project_plans/stelekit/decisions/ADR-002-graph-view-rendering-engine.md` | Compose Canvas with pure-KMP Verlet physics — no D3.js WebView hybrid |
| `project_plans/stelekit/decisions/ADR-003-graph-view-data-model.md` | Page-level graph; edges from wiki-link scan; lazy background edge load |
| `project_plans/stelekit/decisions/ADR-004-graph-view-screen-navigation.md` | `Screen.GraphView` sealed-class entry; local graph is a sidebar panel, not a Screen |

---

## Story Breakdown

### Story 1 — Graph Data Layer [1 week]

Introduce `GraphViewRepository`, `GraphNode`, `GraphEdge` domain types, a
`GraphViewDataSource` that computes page-level edges from existing `BlockRepository`
and `PageRepository` queries, and a `GraphViewModel` that drives `StateFlow`-based
state consumed by the Canvas composable.

**User value**: An offline, testable data model for the graph — no UI yet, but all
the wiring needed to make Stories 2 and 3 land cleanly.

**Acceptance criteria:**

1. `GraphViewRepository.getNodes()` returns all pages as `GraphNode` instances.
2. `GraphViewRepository.getEdges()` returns page→page edges derived from
   `BlockRepository.getLinkedReferences`.
3. `GraphViewModel` exposes `StateFlow<GraphViewState>` with loading, error, and
   populated states.
4. `InMemoryGraphViewRepository` passes all unit tests.
5. Filter predicates (`showJournals`, `showOrphans`) are applied inside the ViewModel
   before emitting to the Canvas.

---

#### Task 1.1 — Domain Models: GraphNode, GraphEdge, GraphViewState [2h]

**Objective**: Define the immutable value-object types that represent graph data.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphModels.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt`

**Prerequisites**:
- Understanding of `Page` and `Block` data classes in `Models.kt`

**Implementation approach**:
1. Create `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/` package directory.
2. Define `GraphNode(uuid, name, namespace, isJournal, x, y, vx, vy, degree, isPinned)`.
   Use `Float` for physics coordinates; `Int` for degree.
3. Define `GraphEdge(fromUuid, toUuid, weight: Int)`.
4. Define `GraphViewState` sealed class: `Loading`, `Empty`, `Populated(nodes, edges,
   focusPageUuid?)`, `Error(message)`.
5. Define `GraphFilter(showJournals: Boolean = true, showOrphans: Boolean = true,
   hopCount: Int = 0, focusPageUuid: String? = null)` where `hopCount = 0` means
   "show all".

**Validation strategy**:
- Unit tests: `GraphNode` copy with updated coordinates does not mutate original.
- Unit tests: `GraphFilter` default equals contract.
- Success criteria: All data classes compile; `GraphViewState.Populated` carries
  non-empty lists in test.

**INVEST check**:
- Independent: Pure data definitions; no I/O.
- Valuable: Required by every subsequent task.
- Estimable: 2 h — pure data modelling.
- Small: Single file, single concern.
- Testable: Equality and copy semantics verified automatically.

---

#### Task 1.2 — GraphViewRepository Interface + InMemory Implementation [2h]

**Objective**: Define the data-access contract and a testable in-memory implementation.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphViewRepository.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryRepositories.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphModels.kt` (Task 1.1)

**Prerequisites**:
- Task 1.1 complete.
- Understanding of `ReferenceRepository` and `PageRepository` interfaces.

**Implementation approach**:
1. Define `interface GraphViewRepository`:
   ```kotlin
   fun getNodes(): Flow<Result<List<GraphNode>>>
   fun getEdges(): Flow<Result<List<GraphEdge>>>
   ```
2. Implement `InMemoryGraphViewRepository(nodes: List<GraphNode>, edges: List<GraphEdge>)`
   that returns the pre-seeded lists as immediate `success` flows.
3. Register `InMemoryGraphViewRepository` in `InMemoryRepositories.kt` so test
   helpers can construct a full `RepositorySet` with graph-view data.

**Validation strategy**:
- Unit test: `InMemoryGraphViewRepository` emits nodes and edges synchronously.
- Unit test: Empty repository emits empty lists (not error).
- Success criteria: Test `InMemoryGraphViewRepositoryTest` passes in `jvmTest`.

**INVEST check**:
- Independent: No UI dependency.
- Estimable: 2 h.
- Small: Interface + one concrete class.
- Testable: Fully exercised by unit tests.

---

#### Task 1.3 — SqlDelightGraphViewRepository [3h]

**Objective**: Derive page-level graph data from existing SQLDelight queries.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/SqlDelightGraphViewRepository.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightPageRepository.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/SteleDatabase.sq`

**Prerequisites**:
- Tasks 1.1 and 1.2 complete.
- Familiarity with `SteleDatabase.sq` query syntax.

**Implementation approach**:
1. Implement `getNodes()`: query all pages via `PageRepository.getAllPages()`; map to
   `GraphNode` (set `x = 0f, y = 0f` — physics engine will randomise on first tick).
2. Implement `getEdges()`: for each page, call
   `BlockRepository.getLinkedReferences(pageName)` and group by `pageUuid` to
   produce `GraphEdge(fromUuid = page.uuid, toUuid = referrerPage.uuid, weight = count)`.
   Use `flowOn(PlatformDispatcher.IO)`.
3. Deduplicate edges (A→B and B→A are separate directed edges; same pair with same
   direction is merged into one edge with summed weight).
4. Wire into `RepositoryFactoryImpl.createRepositorySet` so production builds get the
   real implementation.

**Validation strategy**:
- Integration test (jvmTest): seed 3 pages and 2 wiki-link references; assert
  `getEdges()` returns 2 `GraphEdge` instances with correct `fromUuid`/`toUuid`.
- Unit test: duplicate reference on same page is aggregated into weight > 1.
- Success criteria: Integration test passes; no N+1 query warnings in logs.

**INVEST check**:
- Independent: Pure data layer; no UI.
- Estimable: 3 h (includes integration test setup).
- Small: One file; reuses existing queries.
- Testable: Integration test with real SQLDelight in-memory DB.

---

#### Task 1.4 — GraphViewModel [3h]

**Objective**: ViewModel that exposes `StateFlow<GraphViewState>` with filter support.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphViewModel.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphViewRepository.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphModels.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

**Prerequisites**:
- Tasks 1.1–1.3 complete.

**Implementation approach**:
1. `class GraphViewModel(repo: GraphViewRepository, scope: CoroutineScope)`.
2. Expose `val state: StateFlow<GraphViewState>` (starts as `Loading`).
3. Expose `val filter: StateFlow<GraphFilter>`.
4. `fun updateFilter(filter: GraphFilter)` — triggers re-computation.
5. Apply filter in `combine(repo.getNodes(), repo.getEdges(), filter)`:
   - Remove journal nodes if `!showJournals`.
   - Remove orphan nodes (degree == 0) if `!showOrphans`.
   - If `hopCount > 0` and `focusPageUuid != null`, BFS-trim to N-hop neighbourhood.
6. Expose `fun onNodeClick(nodeUuid: String)` — calls out via a callback lambda
   (passed at construction) to `StelekitViewModel.navigateToPageByUuid`.

**Validation strategy**:
- Unit test: filter `showJournals = false` removes journal nodes from emitted state.
- Unit test: filter `hopCount = 1` with a focus node returns only directly connected nodes.
- Unit test: `onNodeClick` invokes the navigation callback with correct UUID.
- Success criteria: All unit tests green; `GraphViewState.Populated` emitted within
  100 ms of construction with `InMemoryGraphViewRepository`.

**INVEST check**:
- Independent: No Compose dependency (pure ViewModel).
- Estimable: 3 h.
- Small: One class, one concern.
- Testable: Full unit test suite.

---

### Story 2 — Global Graph Canvas [1 week]

Implement the full-screen `Screen.GraphView` composable with a Compose Canvas,
Verlet-integration physics, zoom/pan, and node-click navigation. Wire into sidebar
and command palette.

**User value**: Users can open the graph view, see all pages, zoom and pan, and click
a node to navigate.

**Acceptance criteria:**

1. `Screen.GraphView` appears in sidebar navigation.
2. Canvas renders all page nodes as circles; edges as lines.
3. Physics simulation converges to a stable layout within 5 s for a 500-node graph.
4. Pinch-zoom and two-finger pan work on desktop (trackpad) and Android (touch).
5. Tapping/clicking a node navigates to that page.
6. Filter toolbar (journals, orphans toggles) updates the visible node/edge set
   without restarting physics.
7. Frame rate ≥ 50 fps on JVM desktop with 1 000 nodes (measured via `HistogramWriter`).

---

#### Task 2.1 — GraphPhysicsEngine: Verlet Integration [4h]

**Objective**: Self-contained physics simulation that mutates `GraphNode` positions
over time using attractive edge forces + repulsive node forces.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphPhysicsEngine.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphModels.kt`

**Prerequisites**:
- Task 1.1 complete.
- Basic familiarity with force-directed graph algorithms.

**Implementation approach**:
1. Define `interface GraphPhysicsEngine`:
   ```kotlin
   fun seed(nodes: List<GraphNode>, edges: List<GraphEdge>)
   fun tick(): List<GraphNode>  // returns updated node positions
   val isStable: Boolean
   ```
2. Implement `VerletGraphPhysicsEngine`:
   - Repulsive force between all node pairs: Coulomb approximation
     `F = k / d²` (clamp min distance to 1f).
   - Attractive (spring) force along edges: Hooke's law `F = -k * (d - restLength)`.
   - Velocity damping: `vx *= 0.85f` each tick.
   - `isStable`: maximum velocity across all nodes < 0.5f.
   - Random initial positions seeded from `Random(seed = nodeCount)` for
     deterministic layouts in tests.
3. Optimisation: skip node pairs whose squared distance > `(width + height)² * 0.25`
   (viewport cull) once positions are known.

**Validation strategy**:
- Unit test: after 200 ticks with 2 connected nodes, distance < initial distance
  (nodes attracted).
- Unit test: after 200 ticks with 2 unconnected nodes starting close, distance
  > initial distance (nodes repelled).
- Unit test: `isStable` becomes `true` within 500 ticks for a 10-node graph.
- Success criteria: All three unit tests pass; no `NaN` in positions after 1 000 ticks.

**INVEST check**:
- Independent: No UI, no Compose dependency.
- Estimable: 4 h.
- Small: Single algorithm, single file.
- Testable: Deterministic with fixed seed.

---

#### Task 2.2 — GraphCanvas Composable (nodes, edges, zoom/pan) [4h]

**Objective**: `@Composable fun GraphCanvas(state, onNodeClick, modifier)` that draws
nodes and edges and handles pointer input.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/GraphCanvas.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphModels.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Color.kt`

**Prerequisites**:
- Tasks 1.1 and 2.1 complete.
- Familiarity with `androidx.compose.ui.graphics.Canvas` and `drawCircle`/`drawLine`.

**Implementation approach**:
1. Accept `state: GraphViewState`, `onNodeClick: (String) -> Unit`, `modifier`.
2. Maintain local `var zoom: Float` and `var pan: Offset` via `remember`.
3. `Modifier.pointerInput`:
   - Two-finger pinch: detect scale gesture, update `zoom`.
   - Drag: translate `pan`.
   - Tap: hit-test nodes (circle with radius `NODE_RADIUS / zoom`); emit `onNodeClick`.
4. `Canvas(modifier)`:
   - Apply `scale(zoom)` and `translate(pan.x, pan.y)` transforms.
   - Draw edges as `drawLine(color, startOffset, endOffset, strokeWidth)`.
   - Draw nodes as `drawCircle(color, NODE_RADIUS, center)`.
   - Draw node labels with `drawIntoCanvas { it.nativeCanvas.drawText(...) }` for
     nodes with zoom > 0.4f (LOD: hide labels when zoomed out).
5. Namespace colouring: map `namespace` to a deterministic hue via `namespace.hashCode() % 360`.

**Validation strategy**:
- Screenshot test (Roborazzi, jvmTest): `GraphCanvas` with 5 nodes and 3 edges
  produces stable screenshot.
- Manual smoke test: drag and pinch work on desktop and Android.
- Success criteria: Screenshot test baseline committed; no `NaN` offsets in draw calls.

**INVEST check**:
- Independent: Receives `GraphViewState`; no ViewModel coupling.
- Estimable: 4 h.
- Small: Pure rendering composable.
- Testable: Roborazzi screenshot.

---

#### Task 2.3 — Physics Animation Loop (withFrameMillis coroutine) [2h]

**Objective**: Drive `GraphPhysicsEngine.tick()` from the Compose animation loop and
publish updated `GraphNode` positions to the Canvas.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphViewModel.kt` (extend Task 1.4)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphPhysicsEngine.kt`

**Prerequisites**:
- Tasks 1.4 and 2.1 complete.

**Implementation approach**:
1. Add `val physicsNodes: StateFlow<List<GraphNode>>` to `GraphViewModel`.
2. Add `fun startPhysics(engine: GraphPhysicsEngine)` — launches a coroutine that
   calls `withFrameMillis { engine.tick() }` in a loop until `engine.isStable`.
3. On each tick, emit updated positions through `physicsNodes`.
4. On `updateFilter(filter)`, call `engine.seed(filteredNodes, filteredEdges)` and
   restart the physics loop.
5. Cancel the physics coroutine in `ViewModel.onCleared()`.

**Validation strategy**:
- Unit test: after `startPhysics`, `physicsNodes` emits at least 10 distinct position
  snapshots before `isStable` (using `TestGraphPhysicsEngine` with 50 tick delay).
- Unit test: `updateFilter` cancels the previous physics coroutine (verify via mock
  engine call count reset).
- Success criteria: No coroutine leaks in tests (checked by `TestScope` job cancellation).

**INVEST check**:
- Independent: Modifies `GraphViewModel`; no Compose import.
- Estimable: 2 h.
- Testable: `TestGraphPhysicsEngine` doubles the real engine.

---

#### Task 2.4 — GraphViewScreen Composable + Navigation Wiring [3h]

**Objective**: Full-screen `Screen.GraphView` composable with filter toolbar, wired
into `AppState`, `Sidebar`, and command palette.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/GraphViewScreen.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

**Prerequisites**:
- Tasks 1.4, 2.2, and 2.3 complete.

**Implementation approach**:
1. Add `data object GraphView : Screen()` to `sealed class Screen`.
2. Add `"graph-view" -> Screen.GraphView` to `StelekitViewModel.navigateTo(destination: String)`.
3. Implement `@Composable fun GraphViewScreen(viewModel: StelekitViewModel, graphViewModel: GraphViewModel)`:
   - Top filter toolbar: `Switch` for journals, orphans; `Slider` for hop count.
   - `GraphCanvas` consuming `graphViewModel.physicsNodes.collectAsState()`.
   - `onNodeClick` calls `viewModel.navigateToPageByUuid(uuid)`.
4. Add `Screen.GraphView -> GraphViewScreen(...)` branch in `App.kt` routing.
5. Add sidebar icon (use `Icons.Outlined.AccountTree` or similar) that calls
   `viewModel.navigateTo(Screen.GraphView)`.
6. Register a command-palette entry: `"Open Graph View"`, shortcut `Ctrl+G`.

**Validation strategy**:
- UI test (jvmTest): navigate to `Screen.GraphView` via command; assert screen is
  rendered without crash.
- Unit test: `navigateTo("graph-view")` produces `Screen.GraphView` in navigation history.
- Success criteria: CI builds green; sidebar icon visible in smoke test.

**INVEST check**:
- Independent: Thin wiring layer — all logic in `GraphViewModel`.
- Estimable: 3 h.
- Testable: Navigation unit test + composable smoke test.

---

### Story 3 — Local Graph Panel + Polish [1 week]

Add the per-page local graph minimap in the right sidebar, add namespace visual
clustering, and address performance for large graphs.

**User value**: Without leaving the page, users see which pages they reference and
which pages reference them — instantly surfacing the knowledge context.

**Acceptance criteria:**

1. Right sidebar (when expanded) shows a `LocalGraphPanel` with the current page as
   a highlighted centre node and its 1-hop neighbours.
2. Clicking a neighbour node navigates to that page.
3. Namespace nodes share a colour; a legend shows namespace → colour mapping.
4. For graphs > 2 000 nodes, a Barnes–Hut spatial approximation is used and the
   frame rate remains ≥ 40 fps.
5. `LocalGraphPanel` does not block sidebar open animation (async load with spinner).

---

#### Task 3.1 — LocalGraphPanel Composable [3h]

**Objective**: Compact, non-interactive-zoom graph showing only the focus page and
its 1-hop neighbours.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/LocalGraphPanel.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/GraphCanvas.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphViewModel.kt`

**Prerequisites**:
- Story 2 tasks complete.
- Understanding of `ReferencesPanel.kt` right-sidebar pattern.

**Implementation approach**:
1. `@Composable fun LocalGraphPanel(page: Page, graphViewModel: GraphViewModel, onNodeClick)`.
2. Derive the 1-hop subgraph by calling `graphViewModel.getLocalGraph(focusUuid)` —
   a new `suspend fun` that filters `GraphViewState.Populated` to 1-hop neighbours.
3. Render via `GraphCanvas` with a fixed zoom (no user zoom/pan) and a smaller
   `NODE_RADIUS`.
4. Use `LaunchedEffect(page.uuid)` to trigger async load; show `CircularProgressIndicator`
   while loading.
5. Embed in `ReferencesPanel.kt` or directly in the right-sidebar column (after the
   existing references section).

**Validation strategy**:
- Screenshot test: `LocalGraphPanel` with a 5-node graph centred on one page produces
  expected layout.
- Manual test: panel updates when navigating between pages.
- Success criteria: Roborazzi screenshot baseline committed.

**INVEST check**:
- Independent: Thin wrapper around existing `GraphCanvas`.
- Estimable: 3 h.
- Testable: Screenshot test.

---

#### Task 3.2 — Namespace Colour Clustering [2h]

**Objective**: Assign deterministic colours to namespaces and render a legend.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/NamespaceColorMapper.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/GraphCanvas.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Color.kt`

**Prerequisites**:
- Task 2.2 complete.

**Implementation approach**:
1. Implement `object NamespaceColorMapper`:
   ```kotlin
   fun colorFor(namespace: String?): Color
   fun legendEntries(nodes: List<GraphNode>): List<Pair<String, Color>>
   ```
   Use `HSL(hue = abs(namespace.hashCode()) % 360f, saturation = 0.6f, lightness = 0.55f)`
   for determinism; null namespace → `MaterialTheme.colorScheme.primary`.
2. Update `GraphCanvas` to call `NamespaceColorMapper.colorFor(node.namespace)` for
   node fill colour.
3. Add a collapsible `NamespaceLegend` composable in `GraphViewScreen` showing
   coloured chips.

**Validation strategy**:
- Unit test: same namespace always produces same colour.
- Unit test: null namespace produces the fallback colour.
- Screenshot test: legend chip row renders correctly.
- Success criteria: Unit tests green; no visible colour collision for 5 distinct namespaces.

**INVEST check**:
- Independent: `NamespaceColorMapper` has no I/O.
- Estimable: 2 h.
- Testable: Pure function — deterministic output.

---

#### Task 3.3 — Barnes–Hut Optimisation for Large Graphs [4h]

**Objective**: Reduce O(n²) repulsion to O(n log n) for graphs > 2 000 nodes using
a quadtree spatial approximation.

**Context boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/BarnesHutPhysicsEngine.kt` (new)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphPhysicsEngine.kt`
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/graph/GraphViewModel.kt`

**Prerequisites**:
- Task 2.1 complete.

**Implementation approach**:
1. Implement `BarnesHutPhysicsEngine : GraphPhysicsEngine` using a quad-tree.
2. Each tick: build quad-tree from current node positions; for each node, traverse
   the tree using the Barnes–Hut criterion (θ = 0.9) to approximate repulsion.
3. Attractive and damping forces are identical to `VerletGraphPhysicsEngine`.
4. Update `GraphViewModel.startPhysics` to select engine based on node count:
   `if (nodes.size > 2_000) BarnesHutPhysicsEngine() else VerletGraphPhysicsEngine()`.

**Validation strategy**:
- Performance test: 3 000-node graph; measure ticks/second; assert > 40 ticks/s on JVM.
- Unit test: final layout of 10-node graph is within 10 % of `VerletGraphPhysicsEngine`
  layout (same seed) — verifies Barnes–Hut doesn't break correctness.
- Success criteria: Performance benchmark passes; unit test passes.

**INVEST check**:
- Independent: Implements same interface as `VerletGraphPhysicsEngine`.
- Estimable: 4 h.
- Testable: Benchmark + correctness test.

---

## Known Issues (Planning-Phase Bug Register)

### BUG-GV-001: Graph edge N+1 query [SEVERITY: High]

**Description**: `SqlDelightGraphViewRepository.getEdges()` as naively implemented
calls `BlockRepository.getLinkedReferences(pageName)` once per page. For a 2 000-page
graph this is 2 000 sequential queries, taking 4–8 s.

**Mitigation**:
- Add a single SQL query `SELECT b.page_uuid AS from_page, p.uuid AS to_page, COUNT(*) AS weight FROM blocks b JOIN pages p ON b.content LIKE '%[[' || p.name || ']]%' GROUP BY 1, 2` to `SteleDatabase.sq` to compute all edges in one pass.
- This query uses FTS5 or LIKE; FTS5 is preferred (existing `blocks_fts` virtual table).

**Files likely affected**:
- `kmp/src/commonMain/sqldelight/.../SteleDatabase.sq` — new query
- `kmp/src/commonMain/kotlin/.../graph/SqlDelightGraphViewRepository.kt` — use new query

**Prevention**: Before merging Task 1.3, run the integration test with a 500-page
seed and assert load time < 500 ms.

**Related task**: Task 1.3

---

### BUG-GV-002: Physics NaN explosion with co-located nodes [SEVERITY: Medium]

**Description**: When two nodes have exactly the same `(x, y)` position (e.g., all
nodes initialised to `(0, 0)` before random seeding), the Coulomb repulsion formula
`F = k / d²` produces `Infinity` or `NaN`, corrupting all subsequent positions.

**Mitigation**:
- Seed initial positions with jitter: `x = Random.nextFloat() * width - width / 2`.
- Add a guard in the repulsion loop: `val safeDist = maxOf(dist, 1f)`.
- Add unit test: `tick()` with two co-located nodes does not produce `NaN`.

**Files likely affected**:
- `kmp/src/commonMain/kotlin/.../graph/VerletGraphPhysicsEngine.kt`
- `kmp/src/commonMain/kotlin/.../graph/BarnesHutPhysicsEngine.kt`

**Related task**: Task 2.1

---

### BUG-GV-003: GraphViewModel coroutine leak on rapid filter changes [SEVERITY: Low]

**Description**: If the user moves the hop-count slider rapidly, each `updateFilter`
call could launch a new physics coroutine before the previous one finishes, leading
to multiple `withFrameMillis` loops running concurrently.

**Mitigation**:
- Use `conflatedChannel` or store physics job as `var physicsJob: Job?` and cancel
  before launching a new one.
- Add unit test: rapid `updateFilter` calls produce exactly one physics coroutine job.

**Files likely affected**:
- `kmp/src/commonMain/kotlin/.../graph/GraphViewModel.kt`

**Related task**: Task 2.3

---

### BUG-GV-004: LocalGraphPanel blocks sidebar animation [SEVERITY: Low]

**Description**: If `LocalGraphPanel` starts loading synchronously on right-sidebar
open, the initial `collectAsState()` call may trigger a recomposition heavy enough
to drop the sidebar slide-in animation below 30 fps.

**Mitigation**:
- Show an empty `Box(modifier = Modifier.height(200.dp))` placeholder on first
  composition; begin coroutine load only after the first frame.
- Use `LaunchedEffect(page.uuid)` with an initial delay of one frame
  (`withFrameMillis {}`) before starting the data query.

**Files likely affected**:
- `kmp/src/commonMain/kotlin/.../ui/components/LocalGraphPanel.kt`

**Related task**: Task 3.1

---

## Dependency Visualization

```
Story 1: Data Layer
══════════════════

Task 1.1 (GraphModels)
    │
    ├──► Task 1.2 (InMemoryRepo)
    │
    └──► Task 1.3 (SqlDelightRepo) ──► Task 1.4 (GraphViewModel)
                                              │
                                         [Story 1 ✓]

Story 2: Global Canvas (depends on Story 1)
═══════════════════════════════════════════

Task 2.1 (Physics)──────────────────────────────────┐
                                                     │
Task 1.4 (GraphViewModel) ──► Task 2.3 (AnimLoop) ──┤
                                                     ▼
Task 1.1 (GraphModels) ──────► Task 2.2 (Canvas) ──► Task 2.4 (Screen + Nav)
                                                         │
                                                    [Story 2 ✓]

Story 3: Local Panel + Polish (depends on Story 2)
══════════════════════════════════════════════════

Task 2.2 ──► Task 3.1 (LocalGraphPanel)    [parallel]
Task 2.2 ──► Task 3.2 (NamespaceColors)   [parallel]
Task 2.1 ──► Task 3.3 (Barnes-Hut)        [parallel]

All three tasks merge at Story 3 integration checkpoint.
```

---

## Integration Checkpoints

**After Story 1**:
- `GraphViewModel` is fully testable with `InMemoryGraphViewRepository`.
- `SqlDelightGraphViewRepository` passes the 500-page load-time assertion (< 500 ms).
- All `jvmTest` suite passes.

**After Story 2**:
- Navigating to `Screen.GraphView` shows nodes and edges.
- Sidebar icon appears and is clickable.
- `Ctrl+G` command palette shortcut opens the graph view.
- Filter toggles (journals, orphans) visibly update the canvas.
- Roborazzi screenshot baselines committed.
- Frame rate ≥ 50 fps with 1 000 nodes (manual benchmark).

**After Story 3 (Final)**:
- Right sidebar shows `LocalGraphPanel` when a page is open.
- Namespace legend visible in global graph view.
- Barnes–Hut benchmark passes (40 ticks/s with 3 000 nodes).
- All existing navigation tests still green.
- Feature added to `TODO.md` completed items and `docs/tasks/TODO.md` checked off.

---

## Context Preparation Guide

### Task 1.1 (GraphModels)
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` (Page, Block)
- Understand: immutable data classes, `Validation` object, `copy()` semantics

### Task 1.2 (InMemoryRepo)
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryRepositories.kt`
- Understand: how `InMemoryBlockRepository` returns pre-seeded `Flow<Result<List<*>>>`

### Task 1.3 (SqlDelightRepo)
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`
- Load: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/SteleDatabase.sq`
- Understand: `flowOn(PlatformDispatcher.IO)` pattern; `executeAsList()` / `executeAsOne()`

### Task 1.4 (GraphViewModel)
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (lines 1–80)
- Understand: `combine()` on `StateFlow`; `MutableStateFlow.update {}`

### Task 2.1 (Physics)
- No domain file to load; start fresh
- Understand: Verlet integration — velocity Verlet is sufficient (no acceleration history needed)
- Key formula: repulsion `F_r = k_r / (d² + ε)`, spring `F_s = -k_s * (d - restLen)`

### Task 2.2 (Canvas)
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Color.kt`
- Understand: `DrawScope.drawCircle`, `DrawScope.drawLine`, `Modifier.pointerInput` with `detectTransformGestures`

### Task 2.3 (AnimLoop)
- Load: Task 1.4 result (`GraphViewModel`)
- Understand: `withFrameMillis {}` Compose API; `Job.cancelAndJoin()`

### Task 2.4 (Screen + Nav)
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` (sealed Screen)
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`
- Understand: how existing screens are routed in `App.kt` `when` block

### Task 3.1 (LocalGraphPanel)
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ReferencesPanel.kt`
- Understand: right-sidebar layout; `LaunchedEffect(page.uuid)` load pattern

### Task 3.2 (Namespace Colours)
- Load: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Color.kt`
- Understand: HSL → Compose Color conversion

### Task 3.3 (Barnes–Hut)
- Load: Task 2.1 result (`VerletGraphPhysicsEngine`)
- Understand: Barnes–Hut criterion `s/d < θ`; quadtree node merge

---

## Success Criteria

- [ ] All atomic tasks completed and their unit/integration/screenshot tests pass
- [ ] `Ctrl+G` opens graph view; sidebar icon present
- [ ] Node click navigates to the correct page
- [ ] Filter toggles update canvas correctly
- [ ] Local graph panel loads asynchronously in right sidebar
- [ ] Namespace legend rendered in global graph view
- [ ] Frame rate ≥ 50 fps (1 000 nodes, JVM desktop)
- [ ] Barnes–Hut benchmark ≥ 40 ticks/s (3 000 nodes)
- [ ] No `NaN` positions in physics simulation (co-location guard tested)
- [ ] `jvmTest` suite passes with no regressions
- [ ] Roborazzi screenshot baselines committed
- [ ] Test coverage ≥ 80 % for `graph/` package
- [ ] `TODO.md` entry `[GV-001]` added and checked off after completion
- [ ] Code reviewed and approved

---

## Related Documents

- `docs/architecture/feature-gap-analysis.md` — confirms Graph View as missing
- `project_plans/stelekit/decisions/ADR-002-graph-view-rendering-engine.md`
- `project_plans/stelekit/decisions/ADR-003-graph-view-data-model.md`
- `project_plans/stelekit/decisions/ADR-004-graph-view-screen-navigation.md`
