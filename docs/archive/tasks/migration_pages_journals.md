# Migration Plan: Pages & Journals

## 1. Discovery & Requirements
Pages and Journals are the primary containers for user content. Journals are time-based pages, while standard pages are topic-based.

### Existing Artifacts
- `src/main/frontend/components/page.cljs`: Page rendering logic.
- `src/main/frontend/components/journal.cljs`: Journal specific logic (infinite scroll of days).
- `src/main/frontend/handler/page.cljs`: Event handlers for page operations.

### Functional Requirements
- **Journals**: Display blocks for "Today", "Yesterday", etc. Infinite scroll back in time.
- **Pages**: Display blocks for a specific topic.
- **Backlinks**: Show "Linked References" and "Unlinked References" at the bottom.
- **Properties**: Page-level properties (tags, alias, etc.).
- **Navigation**: Breadcrumbs, Favorites, Recent pages.

### Non-Functional Requirements
- **Load Time**: Open a page in < 200ms.
- **Scrolling**: Smooth scrolling (60fps) even with hundreds of blocks per day in Journals.

## 2. Architecture & Design (KMP)

### Domain Model
- **Entity**: `Page(id: UUID, name: String, isJournal: Boolean, journalDate: LocalDate?)`
- **Relation**: `Page` 1--* `Block`.

### Logic Layer (Common)
- **PageRepository**: Fetch page by name or date.
- **JournalService**: Calculate date ranges, handle "create journal for today if missing".
- **ReferenceService**: Efficiently query backlinks (incoming links).

### UI Layer (Compose Multiplatform)
- **Component**: `JournalView` (Infinite list of `PageSection`).
- **Component**: `PageView` (Header + BlockList + References).
- **Component**: `BacklinksSection`.

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Logic: Date Timezone Issues [SEVERITY: High]
- **Description**: Journals rely on "local date". If the user changes timezones, "Today" might shift, or files might be saved with the wrong filename date.
- **Mitigation**: Use `kotlinx-datetime`. Explicitly handle timezone conversions. Store dates in ISO-8601 but display in local time. Ensure file names (e.g., `2023-10-27.md`) are consistent.

### 🐛 Performance: Backlink Calculation [SEVERITY: Medium]
- **Description**: Calculating "Unlinked References" (text search for page title) can be slow on large graphs.
- **Mitigation**: Pre-calculate/Index unlinked references in SQLite. Do not compute on the fly during render.

### 🐛 UI: Infinite Scroll Jitter [SEVERITY: Low]
- **Description**: Loading previous days in Journal view might cause the scroll position to jump.
- **Mitigation**: Use Compose's `LazyListState` carefully. Maintain scroll offset relative to the currently visible item, not the top of the list.

## 4. Implementation Roadmap

### Phase 1: Logic Porting
- [ ] Implement `Page` and `Journal` entities.
- [ ] Port "Date to Filename" logic.
- [ ] Implement Backlink query logic (SQL).

### Phase 2: UI Implementation
- [ ] Create `PageView` scaffold.
- [ ] Create `JournalView` with infinite scrolling.
- [ ] Implement "Linked References" component.

### Phase 3: Integration
- [ ] Hook up `JournalView` to `BlockRepository`.
- [ ] Verify navigation between pages.

## 5. Migration Checklist
- [ ] **Logic**: Journal date handling matches legacy.
- [ ] **Logic**: Backlinks are correctly identified.
- [ ] **UI**: Infinite scroll works smoothly.
- [ ] **Tests**: Timezone tests for Journals.
- [ ] **Parity**: Page properties rendering.

