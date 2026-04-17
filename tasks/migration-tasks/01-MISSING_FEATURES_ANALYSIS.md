# Missing Features Analysis: ClojureScript → KMP Migration

## Overview
Based on systematic analysis of the ClojureScript frontend (`src/main/frontend`) vs KMP implementation (`kmp/src`), this document identifies all missing features that need to be implemented.

## Critical Missing Features (High Priority)

### 1. **Complete Editor System**
**Current State**: KMP has basic PageView placeholder
**Missing**: 
- Full rich text editor with markdown support
- Block-level editing operations (create, edit, delete, reorder)
- Undo/redo functionality
- Auto-save and synchronization
- Reference creation and management (`[[page]]` and `((block))`)
- Property editing and management
- Block commands and slash commands (`/command`)

**Files to Reference**:
- `src/main/frontend/components/editor.cljs`
- `src/main/frontend/handler/editor.cljs`
- `src/main/frontend/modules/outliner/`
- `src/main/frontend/commands.cljs`

### 2. **Real-Time Collaboration (RTC)**
**Current State**: KMP has basic RTCManager placeholder
**Missing**:
- WebSocket-based real-time sync
- Operational transformation (OT) for concurrent editing
- Conflict resolution
- Remote cursor and selection tracking
- Branch graph management
- Asset synchronization

**Files to Reference**:
- `src/main/frontend/worker/rtc/` (entire directory)
- `src/main/frontend/components/rtc/indicator.cljs`

### 3. **Advanced Search & Query System**
**Current State**: KMP has basic VectorSearch and DatalogQuery
**Missing**:
- Full-text search with indexing
- Advanced query builder UI
- Template search
- File search
- Search result highlighting and navigation
- Query visualization and filters

**Files to Reference**:
- `src/main/frontend/search.cljs`
- `src/main/frontend/components/query/`
- `src/main/frontend/components/cmdk/`

### 4. **Plugin System**
**Current State**: KMP has basic PluginSystem interface
**Missing**:
- Complete JS plugin bridge
- Plugin lifecycle management
- Plugin UI registration
- Command registration
- Settings management
- Plugin storage and configuration

**Files to Reference**:
- `src/main/logseq/api.cljs`
- `src/main/frontend/handler/plugin.cljs`
- `src/main/frontend/components/plugins.cljs`

### 5. **Export/Import System**
**Current State**: Not implemented in KMP
**Missing**:
- HTML export with themes
- OPML export
- Markdown export
- Roam JSON export
- ZIP export with assets
- Import from various formats (Markdown, Org-mode, etc.)
- Publication and static site generation

**Files to Reference**:
- `src/main/frontend/handler/export/`
- `src/main/frontend/components/export.cljs`

## Important Missing Features (Medium Priority)

### 6. **PDF Integration**
**Current State**: KMP has PDFViewer placeholder
**Missing**:
- PDF viewing and annotation
- Highlight management
- Page navigation
- Search within PDFs
- PDF asset management
- Integration with blocks and references

**Files to Reference**:
- `src/main/frontend/extensions/pdf/` (entire directory)
- `src/main/frontend/handler/assets.cljs`

### 7. **Whiteboard System**
**Current State**: KMP has basic Whiteboard placeholder
**Missing**:
- Shape creation and editing
- Drawing tools
- Shape connections and linking
- Whiteboard asset management
- Collaboration features
- Export functionality

**Files to Reference**:
- `src/main/frontend/handler/import.cljs` (whiteboard import)
- RTC worker for whiteboard sync

### 8. **Flashcards & Spaced Repetition**
**Current State**: Not implemented in KMP
**Missing**:
- FSRS algorithm implementation
- Flashcard creation from blocks
- Review scheduling
- Card statistics
- Review interface

**Files to Reference**:
- `src/main/frontend/extensions/fsrs.cljs`
- `src/main/frontend/components/content.cljs` (flashcard context menus)

### 9. **Advanced UI Components**
**Current State**: KMP has basic component placeholders
**Missing**:
- Command palette with fuzzy search
- Date picker
- Property dialogs
- Settings management UI
- Onboarding flow
- Notification system

**Files to Reference**:
- `src/main/frontend/components/command_palette.cljs`
- `src/main/frontend/components/datepicker.cljs`
- `src/main/frontend/components/property/`
- `src/main/frontend/components/settings.cljs`

### 10. **Asset Management**
**Current State**: Basic file system exists
**Missing**:
- Asset upload and processing
- Image optimization
- Asset linking and embedding
- Asset organization and search
- External asset handling

**Files to Reference**:
- `src/main/frontend/handler/assets.cljs`
- `src/main/frontend/components/assets.cljs`

## Supporting Features (Lower Priority)

### 11. **Mobile-Specific Features**
**Missing**:
- Camera integration
- Haptic feedback
- Mobile gestures
- Touch-optimized UI
- Mobile-specific shortcuts

### 12. **Advanced Theming**
**Missing**:
- Custom theme creation
- Theme importing/exporting
- Advanced color customization
- Font management

### 13. **Performance & Monitoring**
**Current State**: Basic PerformanceMonitor exists
**Missing**:
- Advanced profiling tools
- Memory usage tracking
- Performance optimization suggestions
- Crash reporting

### 14. **Advanced Graph Features**
**Missing**:
- Graph visualization
- Link analysis
- Backlink management
- Tag management
- Graph statistics

## Data Model & Migration

### 15. **Complete Data Model**
**Current State**: Basic Models.kt exists
**Missing**:
- Full block, page, and property models
- Relationship definitions
- Migration scripts from DataScript
- Validation schemas

### 16. **Repository Layer Completion**
**Current State**: Basic repositories exist
**Missing**:
- Complete CRUD operations
- Query optimization
- Caching strategies
- Transaction management

## Implementation Strategy

1. **Phase 1**: Core editor functionality (blocks, pages, basic editing)
2. **Phase 2**: Search and query system
3. **Phase 3**: Plugin system foundation
4. **Phase 4**: Advanced features (PDF, whiteboard, flashcards)
5. **Phase 5**: Polish and optimization

Each subsequent task file will focus on implementing one of these feature areas completely.
