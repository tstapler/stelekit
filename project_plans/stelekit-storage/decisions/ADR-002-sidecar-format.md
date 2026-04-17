# ADR-002: Sidecar File Format

**Status**: Proposed
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

## Context

SteleKit cannot embed block identity metadata in markdown content (requirement: no `id::` properties, no `^anchor` syntax). It must store this metadata externally. The sidecar file provides the bridge between the human-readable markdown file and the authoritative SQLite identity store.

The sidecar must serve three purposes:
1. **UUID recovery after git pull** — when a collaborating machine pulls changes, the sidecar maps content hashes to UUIDs so blocks with edited content can retain their identities
2. **Git diff quality** — the sidecar file will be committed to git alongside the markdown file; it must produce clean, human-readable diffs when blocks are added, edited, or reordered
3. **Regenerability** — if the sidecar is lost or corrupted, it must be fully reconstructible from the markdown file and the SQLite database

Candidate formats evaluated:
- **Per-page NDJSON** (one JSON line per block) in `.stelekit/pages/<slug>.meta.json`
- **Graph-level manifest** (one JSON file listing all blocks across all pages)
- **YAML front matter** (block UUIDs embedded in the markdown file's YAML header)
- **JSON array** (single JSON object per file, all blocks as an array)

## Decision

Use **per-page NDJSON** in `.stelekit/pages/<slug>.meta.json`.

Each line in the file represents one block:
```
{"uuid":"<uuid>","hash":"<content-hash>","pos":<sibling-index>,"parent":"<parent-uuid-or-null>"}
```

Blocks are written in document order (depth-first, same order as the markdown file). The `.stelekit/` directory is committed to git alongside the markdown files.

## Rationale

**NDJSON produces one-line-per-block diffs.** A `git diff` of a page where three blocks were edited shows exactly three changed lines in the sidecar, one per block. A JSON array would mark the entire file as changed (arrays are not line-stable). A YAML front matter approach would require parsing mixed-format files. NDJSON is the only format that gives git diff semantics that match markdown diff semantics.

**Per-page files keep git history scoped.** A graph-level manifest means every edit to any page touches the same file, creating merge conflicts on the manifest whenever two machines edit different pages concurrently. Per-page files scope git merge conflicts to the page that was actually edited.

**NDJSON is regenerable.** Given a markdown file and a SQLite database, the sidecar can be reconstructed by querying `SELECT uuid, content_hash, position, parent_uuid FROM blocks WHERE page_uuid = ?`. There is no information in the sidecar that cannot be recovered from these two sources.

**`.stelekit/` directory is a well-established sidecar convention.** Obsidian uses `.obsidian/`, Logseq uses `logseq/`. Using a dotfile directory keeps metadata invisible in standard file browsers while remaining accessible to git.

**The `hash` field enables content-based UUID recovery** across position changes. When machine B pulls a change from machine A, and block X was moved from position 2 to position 5, the sidecar on machine A maps `hash(block X content) → UUID`. Machine B finds the same content hash, assigns the original UUID, and the block retains identity despite the position change. This is the primary Phase 3 use case.

## Consequences

### Positive
- One-line-per-block git diffs: adding a block adds one line; editing adds/removes one line
- Per-page files prevent cross-page sidecar merge conflicts
- Fully regenerable from DB state (no information loss if sidecar is deleted)
- Simple to parse: standard JSON per line, no schema evolution complexity in Phase 3
- `.stelekit/` directory has precedent in the ecosystem

### Negative (accepted costs)
- Two files per page on disk (`.md` + `.meta.json`). A 1,000-page graph creates 1,000 extra files. Acceptable for personal-scale use.
- `.stelekit/` must be listed in `.gitignore` exclusions or explicitly `git add`-ed. Users must be instructed to commit the sidecar alongside markdown.
- If the sidecar is not committed to git (user ignores it), Phase 3 UUID recovery degrades gracefully to position-derived UUIDs (Phase 1 behavior).

### Risks
- A user who commits `.stelekit/` but not `.md` files (or vice versa) will have a desynchronized sidecar. `SidecarManager` should log a warning when the sidecar's block count diverges significantly from the parsed block count.
- NDJSON requires that each line is a complete JSON object. A write failure mid-file leaves a truncated last line. `SidecarManager.read()` must handle truncated lines gracefully (skip and log).

## Alternatives Rejected

- **Graph-level manifest** (`stelekit.manifest.json`): Rejected because a single shared file becomes a git merge conflict hotspot whenever two machines edit different pages concurrently. The per-page design avoids this entirely.
- **YAML front matter in the markdown file**: Rejected because it embeds metadata in the content file, violating the "no embedded metadata" requirement. The markdown file must remain clean for Logseq and standard parsers.
- **JSON array per file**: Rejected because JSON arrays are not line-stable for git diff. Adding one block at position 2 shifts all subsequent array entries, producing a noisy diff. NDJSON does not have this property.
- **UUID-per-line plain text**: Considered for its simplicity, but rejected because it cannot encode the `hash` field needed for content-based UUID recovery. The hash is the core value of the sidecar.
