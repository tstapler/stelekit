# Migration Plan: Zotero Integration

## 1. Discovery & Requirements
Zotero integration allows users to import bibliographic data and PDF attachments from their Zotero library.

### Existing Artifacts
- `src/main/frontend/extensions/zotero.cljs`: API client and UI.

### Functional Requirements
- **Connect**: Authenticate with Zotero API key.
- **Search**: Search Zotero library from within Logseq.
- **Import**: Create a page for a paper, importing metadata (Title, Author, Year, Abstract).
- **Attachments**: Link or download PDF attachments.

### Non-Functional Requirements
- **Rate Limiting**: Respect Zotero API limits.
- **Offline**: Cache Zotero data for offline search.

## 2. Architecture & Design (KMP)

### Logic Layer (Common)
- **ZoteroClient**: HTTP Client (Ktor) to talk to Zotero API.
- **ZoteroMapper**: Convert JSON response to Logseq Page/Block structure.
- **ZoteroCache**: Local SQLite table to store library metadata.

### UI Layer (Compose Multiplatform)
- **Component**: `ZoteroSearchModal`.
- **Component**: `ZoteroSettings`.

## 3. Proactive Bug Identification (Known Issues)

### 🐛 Logic: API Key Security [SEVERITY: High]
- **Description**: Storing API keys in plain text config files is bad practice.
- **Mitigation**: Use the platform's secure storage (Keychain/Keystore).

### 🐛 UX: Large Library Sync [SEVERITY: Medium]
- **Description**: Syncing a library with 10,000+ items takes a long time and might timeout.
- **Mitigation**: Pagination. Background sync. Incremental sync (check `last-modified`).

## 4. Implementation Roadmap

### Phase 1: API Client
- [ ] Implement `ZoteroClient` using Ktor.
- [ ] Implement OAuth or Key-based auth flow.

### Phase 2: Data Mapping
- [ ] Implement logic to format Zotero Item -> Markdown Page.
- [ ] Support custom templates for import.

### Phase 3: UI
- [ ] Create Search Modal.
- [ ] Implement "Insert Citation" command.

## 5. Migration Checklist
- [ ] **Logic**: Can fetch items from Zotero API.
- [ ] **Logic**: Import creates correct page structure.
- [ ] **UI**: Search is responsive.
- [ ] **Parity**: Custom templates are supported.

