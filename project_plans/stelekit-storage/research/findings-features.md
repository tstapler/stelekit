# Research Findings: Features — Block Identity, Undo, and Multi-Device Sync

**Date**: 2026-04-13
**Input**: `project_plans/stelekit-storage/requirements.md`, `research_plan.md`
**Method**: Training knowledge only (cutoff August 2025). Web search unavailable.
**Disclaimer**: All URLs listed are unverified — they reflect known documentation sites as of training data but may have changed. No claims about current product state should be assumed accurate without verification.

---

## 1. Logseq Block Identity and Sync Internals

**Confidence: HIGH** for the file-based (classic) version; **MEDIUM** for the DB version (released late 2024, limited training data).

### 1.1 File-Based Logseq (Classic)

Logseq's classic architecture stores every block as a node in an EDN/Datom graph backed by DataScript (an in-memory Datomic-like database).

**Block UUIDs**:
- Every block is assigned a UUID at creation time and stored as the `id::` property in the markdown file on the same line as (or adjacent to) the block content.
- Example:
  ```
  - This is a block
    id:: 66b4c3e8-0a1a-4c2a-8c3d-1234567890ab
  ```
- The UUID is **embedded in the file itself**, making it the ground truth. The in-memory DataScript DB is rebuilt from file parse on every startup.
- This is the approach SteleKit has explicitly ruled out ("No embedded block IDs" constraint).

**Sync model**:
- Logseq has a paid Sync service (closed-source) that uses delta-based conflict detection.
- The open-source file-sync path relies on the user's external sync tool (git, Syncthing, Dropbox).
- On git conflict, Logseq shows a "version conflict" warning and presents both versions of the file. Resolution is manual.
- The DataScript graph is rebuilt from whichever file wins the conflict, so concurrent edits to the same block result in one version being silently discarded unless the user notices the conflict marker.

**Undo**:
- Classic Logseq undo is session-only and implemented as a DataScript transaction log held in memory.
- There is no cross-restart undo. Restarting the app resets undo history.
- The transaction log is the DataScript `transact!` / `transact-async!` function; each user action produces a datom diff that can be retracted.

**Key lesson for SteleKit**:
- Embedding `id::` in the file is the simplest way to achieve stable block identity across tools, but violates the "no embedded IDs" constraint.
- The DataScript approach (in-memory rebuild on parse) is exactly the current SteleKit problem: it makes SQLite a disposable cache rather than the authoritative state.

### 1.2 Logseq DB Version (2024-2025)

Logseq began shipping a "DB version" that stores the canonical state in SQLite rather than markdown files. Key changes:
- Markdown files become exports/views, not the source of truth.
- Block UUIDs are stored in SQLite; the file no longer contains `id::` properties.
- Sync is rearchitected around SQLite WAL deltas or a proprietary binary format (exact protocol not public).
- The DB version broke git-based sync workflows for many power users, causing significant community friction.

**Relevance**: SteleKit's target architecture (SQLite as authoritative state, markdown as export) closely mirrors what Logseq DB version is attempting. The community friction around losing git-sync is a data point: users who rely on git expect the markdown files to be the canonical format.

**Unverified URL**: `https://docs.logseq.com/` — Logseq DB version documentation

---

## 2. Obsidian Block Identity and Sync

**Confidence: HIGH** for core architecture; **MEDIUM** for specific sync internals (proprietary).

### 2.1 Block Identity

Obsidian does not assign UUIDs to blocks by default. Instead, it uses **block references** via a `^block-id` anchor syntax:

```markdown
This is a paragraph I want to reference. ^my-anchor

In another note: [[filename#^my-anchor]]
```

- Block IDs in Obsidian are **human-readable short strings** (or auto-generated alphanumeric IDs like `^abc123`), **appended to the end of the block's line** in the markdown file.
- They are embedded in the file, similar to Logseq's `id::` approach, but as trailing anchors rather than child properties.
- Block IDs are only created when a block is linked to — unlinked blocks have no ID.

**Key lesson for SteleKit**:
- The "ID only when linked" model avoids polluting every block with metadata, but means new blocks have no persistent identity until they are referenced.
- For undo/redo and version history, this is insufficient: every block needs a stable ID from creation.
- The trailing-anchor approach (`^id`) is less visually intrusive than Logseq's `id::` child property approach, but still embeds metadata in the file.

### 2.2 Obsidian Sync (Proprietary)

Obsidian Sync is a closed-source paid service. From user reports and the official documentation:
- It uses **server-side versioning**: each file version is stored as a snapshot on Obsidian's servers.
- On conflict, it detects divergent versions by comparing content hashes and timestamps.
- It creates a conflict copy (similar to Dropbox) rather than attempting a merge.
- Version history goes back up to 12 months (depending on tier); users can restore individual file snapshots.
- Sync is at the **file level**, not the block level.

**Key lesson for SteleKit**:
- File-level snapshot versioning is simpler but coarser than block-level versioning. It cannot distinguish "added paragraph 2" from "deleted paragraph 3" in the same file.
- The conflict-copy approach (rather than merge) aligns with SteleKit's "forked block" concept — preserve both versions, let the user decide.

### 2.3 Obsidian Community Plugins Relevant to SteleKit

**Confidence: MEDIUM** — plugin ecosystem was active as of training data but plugin APIs evolve.

- **Obsidian Git** (`obsidian-git`): Automates git push/pull on a schedule. Does not perform smart merge — relies on git's built-in text merge, which can corrupt markdown with conflict markers mid-paragraph.
- **Periodic Notes / Templater**: Not directly relevant to storage, but shows demand for stable block references across templates.
- **Block Reference Counter**: Tracks which blocks are referenced; shows that users want block-level identity even when the tool doesn't natively provide it.

**Unverified URL**: `https://obsidian.md/plugins` — Obsidian plugin directory

---

## 3. Roam Research Block UID Scheme and Multiplayer Sync

**Confidence: MEDIUM-HIGH** — Roam's architecture was described in public talks and blog posts through 2023; internal details are inferred.

### 3.1 Block Identity

Roam assigns every block a **9-character alphanumeric UID** (e.g., `abc123XYZ`) at creation time. This UID:
- Is stored in the Roam database (proprietary backend, not exposed as a file).
- Is used in block references: `((abc123XYZ))`.
- Is stable across all edits to block content, position changes, and moves between pages.
- Is never visible in the "source" of the document (Roam has no markdown-file export as a primary format).

**Key lesson for SteleKit**:
- Roam proves that stable, creation-time UUIDs are both practical and desirable for block-level references.
- The UID scheme is short enough to be human-copyable but still effectively unique at personal-scale graph sizes.
- The lack of a file-based format is what Roam users cite as the primary vendor lock-in risk.

### 3.2 Multiplayer Sync

Roam supports real-time multiplayer editing (multiple users in the same graph simultaneously). The implementation:
- Uses **Firebase Realtime Database** (or a similar CRDTish backend) to propagate operations in near-real-time.
- Each block operation (insert, update content, move, delete) is sent as a delta to the server.
- The server serializes concurrent edits using last-write-wins at the block level for content, and a linked-list merge for ordering.
- Tombstoning: deleted blocks are marked deleted, not physically removed, to preserve reference integrity.

**Key lesson for SteleKit**:
- Last-write-wins at the block level (content) with linked-list merge for order is a practical simplification of full CRDT.
- Tombstoning deleted blocks is important for the operation log: physical deletes break the version history.
- SteleKit does not need real-time multiplayer but does need the same fundamental semantics for async git-based sync.

---

## 4. Notion Block Model and Version History

**Confidence: HIGH** for public behavior; **LOW** for internal implementation details.

### 4.1 Block Identity

Notion assigns every block a **UUID v4** at creation time, stored in Notion's proprietary database.
- UUIDs are exposed in the URL for pages (`notion.so/Page-Title-<uuid>`) and are stable.
- Block-level UUIDs are accessible via the Notion API.
- Blocks are never directly addressed in a markdown-like source format — Notion has no plain-text source of truth.

### 4.2 Version History

- Notion stores **full page snapshots** in version history (not operation logs).
- The free plan has a 7-day rolling history; paid plans extend this significantly.
- Versions are stored server-side; the client fetches a specific snapshot on demand.
- There is no operation log or undo tree visible to users — it is snapshot-based.

**Key lesson for SteleKit**:
- Snapshot-based history is simpler to implement but provides coarser granularity.
- For SteleKit's requirements (undo across restarts, block-level merge), an operation log is more appropriate than snapshots.
- The Notion model confirms that per-block UUIDs at creation time is the industry standard for serious block-based editors.

### 4.3 Notion Offline / Sync Conflicts

Notion's offline support is limited and optimistic:
- Edits made offline are queued locally and synced on reconnect.
- Conflict handling is last-write-wins at the page level (as of 2024 reports) — concurrent offline edits to the same block from two devices can result in silent data loss.
- This is widely cited as a known limitation.

**Key lesson for SteleKit**:
- Even a sophisticated tool like Notion fails at multi-device concurrent offline edits. This validates SteleKit's explicit requirement to handle this case correctly rather than hoping it doesn't happen.

---

## 5. Bear / Craft / iA Writer — Lightweight Local-First Apps

**Confidence: MEDIUM** — these apps have less published technical detail.

### 5.1 Bear

- Uses SQLite as primary storage; markdown files are exports.
- Block identity: no block-level identity — Bear does not support block references. Documents are treated as atomic units.
- Sync: uses Apple CloudKit for cross-device sync. Conflict resolution is last-write-wins at the note level.
- Undo: session-only; uses macOS NSUndoManager.

### 5.2 iA Writer

- Stores files as plain markdown on disk; no database.
- No block identity; no block references.
- Undo: OS-level text undo only.
- Sync: relies entirely on iCloud Drive or Dropbox at the file level.

**Key lesson for SteleKit**:
- Plain-markdown apps avoid the complexity problem by having no block-level identity requirements. The moment you add block references (like Logseq's `((uuid))` syntax), you need stable block IDs.

---

## 6. Local-First Software Principles (Kleppmann et al.)

**Confidence: HIGH** — this is from a published paper (2019) and subsequent talks through 2024.

The local-first software paper (`https://www.inkandswitch.com/local-first/` — unverified URL) defines 7 ideals for local-first apps. The most relevant to SteleKit:

1. **No spinners**: The local copy is always the source of truth; reads/writes are never blocked on network.
2. **Your work is not trapped on one device**: Data must be portable (export formats matter).
3. **The network is optional**: Full functionality offline.
4. **Seamless collaboration with your colleagues**: Merging must be automatic and correct.
5. **The Long Now**: Data should be readable in 5-10 years; formats must be stable.
6. **Security and privacy**: Encryption and no mandatory cloud dependency.
7. **You retain ultimate ownership**: The user can always export and own their data.

### Relevant Design Principles

**CRDTs as the recommended primitive**:
- The paper recommends CRDTs for conflict-free merging. However, the authors acknowledge that CRDTs impose implementation complexity and that simpler approaches (operation logs with explicit conflict detection) are acceptable for single-user workflows.

**The "merge on sync" model**:
- For git-based sync, the recommended approach is: each device maintains its own operation log; on sync, logs are merged and conflicts are surfaced explicitly rather than silently resolved.

**Sidecar files for metadata**:
- The paper does not prescribe a sidecar format but acknowledges that some metadata (peer IDs, vector clocks, tombstones) cannot be stored in the human-readable format. Sidecar files in a hidden directory (e.g., `.local-first/`) are a common pattern.

---

## 7. Synthesis: Patterns Across Tools

| Tool | Block ID scheme | ID in file? | Undo model | Sync conflict model |
|------|----------------|-------------|------------|---------------------|
| Logseq (classic) | UUID, creation-time | Yes (`id::` child) | Session-only DataScript txn | Manual file-level conflict |
| Logseq (DB ver) | UUID, creation-time | No (SQLite) | Unknown (proprietary) | Unknown (proprietary) |
| Obsidian | Short anchor, on-demand | Yes (`^anchor`) | OS undo manager (session) | Conflict copy (file-level) |
| Roam | 9-char UID, creation-time | No (proprietary DB) | Infinite (server-side) | Realtime CRDT (last-write-wins per block) |
| Notion | UUID v4, creation-time | No (proprietary DB) | Snapshot history (server) | Last-write-wins (note level) |
| Bear | None | No | OS undo (session) | Last-write-wins (note level) |

### Key Takeaways for SteleKit

1. **Creation-time UUID is universal among serious block-based tools** (Logseq, Roam, Notion). Position-derived or content-derived UUIDs are not used in any comparable tool.

2. **The "no embedded IDs" constraint is unusual** — only Roam (proprietary DB) and Logseq DB version achieve this. Both sacrifice file-level portability to do so. SteleKit's sidecar approach is the correct compromise.

3. **Session-only undo is the norm**; cross-restart undo (SteleKit requirement) is a differentiating feature that requires a persistent operation log. No comparable local-first markdown tool has this.

4. **File-level conflict (not block-level) is the norm** for git-based sync. SteleKit's requirement for block-level forked conflict detection is more sophisticated than what any comparable tool provides — which is both an opportunity and a complexity risk.

5. **Tombstoning deleted blocks** (Roam pattern) is important for maintaining reference integrity when a linked block is deleted. The operation log should mark blocks as `deleted` rather than physically removing them.

6. **Last-write-wins at the block level** is an acceptable simplification for content conflicts (Roam does this). Full CRDT for block content (character-level) is generally overkill for single-user async sync.

7. **The Logseq DB version backlash** is a strong signal: users who sync via git and inspect their files with text tools will resist any architecture that degrades file readability. SteleKit's sidecar approach must be designed to be ignorable — the `.md` files must remain 100% clean without the sidecar.

---

## 8. Open Questions Surfaced by This Research

1. **Sidecar granularity**: Should the sidecar be one file per page (e.g., `journal/2024-01-01.md.stelekit`) or one file per graph (`.stelekit/block-ids.json`)? Logseq uses a `.logseq/` graph-level config; Obsidian uses `.obsidian/`. A graph-level sidecar is easier to git-ignore and less visually cluttered.

2. **ID assignment on import**: For existing graphs with no sidecar, SteleKit must assign UUIDs on first import. A random UUID assigned per-block at first stable parse is safer than position-derived or content-derived schemes.

3. **What to do with deleted blocks**: The operation log must retain tombstones. Should they be compacted after a configurable retention window? Roam never compacts (tombstones are forever); this is viable for personal-scale graphs.

4. **Undo history depth**: No comparable tool offers cross-restart undo. A practical limit (e.g., 1000 operations per page, or 30-day rolling window) prevents unbounded log growth without breaking the requirement.

5. **Conflict UI**: The "forked block" UI concept is novel — no comparable tool shows two concurrent versions of the same block in-line. The closest is git conflict markers (`<<<<<<< HEAD`), which are exposed in the file and must be manually resolved. SteleKit should design this UI before implementing the storage layer.
