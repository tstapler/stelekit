# Research Plan: SteleKit Storage Architecture

**Date**: 2026-04-13  
**Input**: `project_plans/stelekit-storage/requirements.md`

## Subtopics & Search Strategy

### 1. Stack (`findings-stack.md`)
What technology options exist for implementing an operation log / event store in SQLite with Kotlin?

- SQLite event sourcing patterns and schemas
- Kotlin Multiplatform CRDT libraries (e.g. `kotlin-crdt`, `automerge-kotlin`, `yjs` ports)
- Operation log table design (append-only, compaction strategies)
- SQLite WAL for concurrent read/write with an op log
- **Search cap**: 4 searches

### 2. Features (`findings-features.md`)
How do comparable note-taking tools handle block identity, persistent undo, and multi-device sync?

- Logseq block identity and sync internals (transit/datoms, Logseq DB version)
- Obsidian plugin ecosystem (undo history plugins, sync conflict UI)
- Roam Research block UID scheme and multiplayer sync
- Notion block model and version history
- **Search cap**: 4 searches

### 3. Architecture (`findings-architecture.md`)
Design patterns for the merge algorithm and sidecar identity file format.

- Event sourcing vs. CRDT vs. operational transform for local-first apps
- Sidecar file formats for block metadata (`.obsidian/`, `.logseq/`, front matter)
- Fractional indexing for ordered list CRDTs (Logoot, LSEQ, Fugue)
- Local-first software principles (Kleppmann et al.)
- **Search cap**: 5 searches

### 4. Pitfalls (`findings-pitfalls.md`)
Known failure modes and gotchas.

- CRDT document correctness issues (interleaving, tombstone bloat)
- SQLite operation log growth and compaction strategies
- Git merge conflicts on markdown files with concurrent block edits
- Undo/redo history size limits and storage considerations
- **Search cap**: 4 searches
