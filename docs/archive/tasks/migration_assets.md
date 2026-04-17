# Migration Plan: Asset Management

## 1. Discovery & Requirements
Asset management handles the storage, retrieval, and display of user-uploaded files (images, videos, PDFs) within the graph.

### Existing Artifacts
- `src/main/frontend/components/assets.cljs`: UI for uploading and displaying assets.
- `src/main/frontend/handler/assets.cljs`: Logic for file handling and path resolution.

### Functional Requirements
- **Upload**: Drag & drop or file picker to add assets.
- **Storage**: Save files to `assets/` folder (default) or user-defined location.
- **Resolution**: Resolve relative paths `../assets/image.png` to absolute paths for display.
- **Management**: Rename, delete, and clean up unused assets.

### Non-Functional Requirements
- **Performance**: Instant rendering of local images.
- **Reliability**: No file corruption during copy/move.
- **Cross-Platform**: Handle path separators correctly (Windows `\` vs Unix `/`).

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **AssetRepository**: Interface for file system operations.
    - Uses **Okio** for multiplatform file system access.
- **PathResolver**: Logic to convert relative graph paths to absolute system paths.
- **AssetCleaner**: Service to scan graph for unused files in `assets/`.

### UI Layer (Compose Multiplatform)
- **Component**: `AssetPreview` (Image/Video player).
- **Component**: `FilePicker` (Platform-specific integration).
- **Integration**: Rich text editor integration (rendering `![alt](src)`).

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Logic: Path Separator Hell [SEVERITY: High]
- **Description**: Windows uses `\`, others use `/`. Hardcoding separators causes broken images on cross-platform sync.
- **Mitigation**: Always normalize paths to `/` internally. Only convert to system separator at the I/O boundary. Use `okio.Path`.

### 🐛 Performance: Large Image Rendering [SEVERITY: Medium]
- **Description**: Loading 10MB+ images directly into memory can cause OOM (Out of Memory) crashes, especially on mobile.
- **Mitigation**: Implement automatic thumbnail generation or downsampling on load (using Coil or platform APIs).

### 🐛 Data Integrity: Filename Collisions [SEVERITY: Low]
- **Description**: Uploading "image.png" twice might overwrite the first one.
- **Mitigation**: Implement auto-renaming strategy (e.g., append timestamp or hash: `image_123456.png`).

## 4. Implementation Roadmap

### Phase 1: File System Logic
- [ ] Implement `AssetRepository` using Okio.
- [ ] Implement `PathResolver` with unit tests for Windows/Unix paths.
- [ ] Implement "Safe Copy" logic (collision handling).

### Phase 2: UI Integration
- [ ] Create `ImageBlock` component in Compose.
- [ ] Implement Drag & Drop handler for the Editor.

### Phase 3: Advanced Features
- [ ] Implement "Unused Assets" scanner.
- [ ] Implement Asset renaming (updating all links in graph).

## 5. Migration Checklist
- [ ] **Logic**: Path resolution works on all platforms.
- [ ] **Logic**: File copying/saving works reliably.
- [ ] **UI**: Images render correctly in the editor.
- [ ] **Tests**: Unit tests for path normalization.
- [ ] **Parity**: Drag and drop functionality restored.

