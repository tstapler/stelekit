# Requirements: SteleKit Storage Architecture

**Status**: Draft | **Phase**: 1 — Ideation complete  
**Created**: 2026-04-13

## Problem Statement

SteleKit is a Kotlin Multiplatform Logseq migration that stores notes as markdown files on disk and indexes them into SQLite. The current architecture treats SQLite as a disposable cache: every file re-parse does a `DELETE all blocks → INSERT all blocks` cycle. This causes:

- **Data loss risk**: a blank file re-parse wipes all in-memory content (the 16:55 production incident)
- **FK violations**: `saveBlocks` can run for pages whose `savePage` failed, hitting `SQLITE_CONSTRAINT_FOREIGNKEY`
- **No stable block identity**: block UUIDs are derived from `content + position`, so any content edit produces a new UUID and resets version history
- **No undo/redo**: there is no operation log; state is always the current file snapshot
- **No merge semantics**: when two machines edit the same page concurrently and sync via git, one machine's changes overwrite the other's
- **Conflict detection is UI-only**: the B2/B3 guards in `StelekitViewModel` are application-layer patches over a storage-layer problem

The system needs to be redesigned so that SQLite is the authoritative CRDT state, markdown files are a human-readable serialization format, and sync (both local file-watcher and remote git) is a merge operation rather than a replace operation.

## Success Criteria

The architecture is successful when the following are all true:

1. **Undo/redo works across app restarts** — the user can undo a change made in a previous session
2. **Git sync merges cleanly** — pulling changes from a remote that edited the same page produces a forked conflict marker (both versions preserved), never silent overwrite
3. **Find and replace works across the graph** — batch rename of a term across all pages correctly identifies which block-versions to update
4. **Block identity is stable** — a block edited 10 times has UUID continuity and a version history of 10 entries
5. **Markdown files remain human-readable** — no synthetic metadata embedded in the content (no `id::` properties scattered through prose)
6. **Re-import is a no-op for unchanged content** — re-parsing a file that hasn't changed results in zero DB writes

## Scope

### Must Have (MoSCoW)
- Stable block UUIDs that persist across content changes and app restarts
- Persistent operation log stored in SQLite (insert block, update block content, delete block, move block)
- Merge-based reconciliation when file state diverges from DB state
- Conflict detection and forked-block representation when concurrent edits are detected
- Undo/redo driven by the operation log (session-persistent and cross-restart)
- Markdown export that round-trips cleanly: `DB state → .md file → re-import → same DB state`
- Best-effort Logseq compatibility: files are readable in Logseq, SteleKit may use a sidecar file for metadata it cannot embed in markdown

### Out of Scope
- Real-time collaboration (live CRDTs, operational transform over a network)
- Logseq `id::` property embedding in markdown content blocks
- Automatic conflict resolution (user must manually resolve forked blocks)
- Custom SteleKit markdown dialect incompatible with standard parsers

## Constraints

- **Tech stack**: Kotlin Multiplatform (JVM + Android + iOS), SQLDelight 2.3.2, SQLite, Compose Multiplatform
- **Markdown format**: Must be readable in Logseq (best-effort); sidecar metadata files acceptable
- **Multi-machine sync**: Git-based, asynchronous — both machines can edit concurrently before syncing
- **No embedded block IDs**: Files must stay clean; `id::` properties are not acceptable in content
- **Existing data**: Migration path required for existing SQLite databases (existing block UUID scheme must be migrated or rebuilt on first run)
- **Single-user per machine**: No real-time concurrent writes on a single machine

## Context

### Existing Work

The following bugs and fixes have already been implemented in this session, all of which are symptoms of the architectural gap being addressed here:

- **B1 fix** (`FileRegistry`): `scanDirectory` now initialises `contentHashes` so mtime-only bumps don't trigger re-parse
- **B2 fix** (`GraphLoader.parseAndSavePage`): Blank-file guard refuses to wipe non-empty in-memory state
- **B3 fix** (`StelekitViewModel`): Two-tier protection (editing block OR dirty blocks) suppresses external file changes
- **SQLITE_BUSY fix** (`DriverFactory.jvm`): PRAGMA settings now applied via `Properties` to all per-thread connections
- **FK violation fix** (`GraphLoader`): `savePage` failure is now an early return; blocks are never written for uncommitted pages

The current block UUID scheme is `hash(filePath + parentUuid + siblingIndex + content)`. Changing this to `hash(filePath + parentUuid + siblingIndex)` (position-only) is an agreed next step but was paused pending this architecture review.

`DatabaseWriteActor` serialises all SQLite writes through a single coroutine channel, which resolved `SQLITE_BUSY` contention during parallel graph loading.

### Key Architectural Decisions Already Made
- `DatabaseWriteActor` stays (serialised write channel is correct)
- `GraphLoader` produces the "desired state" from file parsing
- `GraphWriter` produces markdown from DB state
- Markdown files are the sharing format; SQLite is the local state

### Stakeholders
- Tyler Stapler (sole developer, primary user)
- Future: multi-device personal use (desktop + mobile on same git repo)

## Open Questions for Research

1. What is the right operation log schema? (event sourcing patterns, SQLite-native approaches)
2. How do we represent a "forked" block in the DB and surface it in the UI?
3. What is the right sidecar file format for block identity metadata? (`.stelekit/` directory? front matter? per-page `.meta` file?)
4. What is the merge algorithm when git pulls a changed `.md` file — how do we reconcile the file's block tree against the DB's operation log?
5. How do we handle the `left_uuid` linked-list ordering in a CRDT context — is it sufficient or does it need a fractional index?
6. Is SQLite WAL sufficient for the operation log, or do we need a separate append-only table?

## Research Dimensions Needed

- [ ] Stack — SQLite event sourcing patterns, operation log schemas, KMP-compatible CRDT libraries
- [ ] Features — how Obsidian, Logseq, Notion, and Roam handle block identity, sync, and undo
- [ ] Architecture — event sourcing vs. CRDT vs. operational transform; merge algorithm design; sidecar file formats
- [ ] Pitfalls — known failure modes in document CRDT systems; SQLite append-only log performance; git merge conflicts on binary SQLite files
