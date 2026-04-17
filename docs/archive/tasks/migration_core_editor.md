# Migration Plan: Core Editor & Blocks

## 1. Discovery & Requirements
The "Core Editor" is the heart of Logseq. It handles the outliner interface, block-based editing, markdown rendering, and user interactions (keyboard shortcuts, drag-and-drop).

### Existing Artifacts
- `src/main/frontend/components/block.cljs`: Individual block rendering and behavior.
- `src/main/frontend/components/editor.cljs`: Global editor state, input handling.
- `src/main/frontend/modules/outliner`: Logic for indentation, movement, and hierarchy.

### Functional Requirements
- **Block Editing**: Create, edit, delete blocks. Support Markdown/Org-mode syntax.
- **Outliner Operations**: Indent (Tab), Unindent (Shift+Tab), Move Up/Down (Alt+Arrow), Zoom into block.
- **Selection**: Multi-block selection, cursor persistence.
- **Rich Text**: Bold, Italic, Links, Latex, Code blocks.
- **References**: Block references ((`uuid`)) and Page references (`[[link]]`).

### Non-Functional Requirements
- **Latency**: Typing latency < 16ms (60fps).
- **Scalability**: Handle pages with 1000+ blocks without lag.
- **Reliability**: Zero data loss on crash (auto-save).

## 2. Architecture & Design (KMP)

### Domain Model
- **Entity**: `Block(id: UUID, content: String, parentId: UUID?, children: List<UUID>, properties: Map<String, Any>)`
- **Value Object**: `BlockContent` (Parsed AST of the markdown).
- **Aggregate**: `Page` (Root of the block tree).

### Logic Layer (Common)
- **EditorSession**: Manages the current state of the editor (focused block, cursor position, selection).
- **BlockRepository**: CRUD operations for blocks.
- **OutlinerService**: Handles structural changes (re-parenting logic).
- **Parser**: Markdown/Org-mode parser (using a KMP-compatible library like `commonmark-java` ported or `multiplatform-markdown`).

### UI Layer (Compose Multiplatform)
- **Component**: `BlockList` (LazyColumn).
- **Component**: `EditableBlock` (Custom TextField).
- **Pattern**: Unidirectional Data Flow (MVI).
    - **Intent**: `UserTypes`, `UserIndents`.
    - **State**: `EditorUiState(blocks: List<BlockUiModel>, focus: FocusState)`.

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Concurrency: Race Condition in Auto-Save [SEVERITY: High]
- **Description**: Rapid typing might trigger multiple save events. If processed out of order, data loss occurs.
- **Mitigation**: Use a `SerialExecutor` or Kotlin `Channel` (Actor pattern) to serialize save operations. Implement "Last Write Wins" with version vectors.

### 🐛 UX: IME (Input Method Editor) Issues [SEVERITY: Medium]
- **Description**: CJK (Chinese/Japanese/Korean) input often conflicts with custom text editors, causing duplicated characters or committed text before completion.
- **Mitigation**: Thorough testing with Compose's IME support. Avoid manual key-event handling for text insertion; rely on the platform's text input connection.

### 🐛 Performance: Large Page Rendering [SEVERITY: Medium]
- **Description**: Rendering 500+ blocks in a `LazyColumn` can still be jerky if item measurement is expensive.
- **Mitigation**: Use `key` in LazyColumn to prevent unnecessary recompositions. Cache text layouts.

## 4. Implementation Roadmap

### Phase 1: Logic Porting
- [ ] Define `Block` and `Page` data classes in KMP.
- [ ] Port `Outliner` logic (indent/unindent/move) to Kotlin.
- [ ] Implement `MarkdownParser` in KMP.
- [ ] Unit Test: Verify outliner logic against CLJS behavior.

### Phase 2: UI Implementation (Compose)
- [ ] Create `BlockView` component.
- [ ] Implement `Editor` container with `LazyColumn`.
- [ ] Implement Keyboard handling (Enter for new block, Tab for indent).
- [ ] Implement "Block Reference" rendering.

### Phase 3: Verification & Cleanup
- [ ] Connect KMP Editor to SQLite DB.
- [ ] Verify feature parity (Manual & Automated tests).
- [ ] **DELETE** `src/main/frontend/components/block.cljs`.
- [ ] **DELETE** `src/main/frontend/components/editor.cljs`.
- [ ] **DELETE** `src/main/frontend/modules/outliner`.

## 5. Migration Checklist
- [x] **Logic**: Outliner algorithms (move/indent) ported to Kotlin.
- [x] **Logic**: Markdown parsing ported to Kotlin.
- [x] **UI**: Basic text editing works in Compose.
- [x] **UI**: Block hierarchy rendering works.
- [x] **Tests**: Unit tests for Outliner logic pass.
- [x] **Parity**: Keyboard shortcuts match legacy app.
- [x] **Parity**: Drag and drop works.
- [x] **Cleanup**: Legacy CLJS code removed.
