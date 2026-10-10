# ADR-002: Block identity, UUID remap, conflict representation, aliases

**Status**: Proposed (UUID round-trip facts gated by Spike 0.1.3). Flips to Accepted when Spike 0.1.3 records (a) explicit `id::` round trip, (b) the duplicate-uuid insert outcome (throws / replaces / ignores), (c) `QrImportService` uuid handling. If (b) shows replace-with-cascade, the clobber guard in Task 1.2.1c is mandatory and this ADR is amended before Phase 1 continues. Spike 0.1.4 does not flip this ADR.
**Date**: 2026-10-07

## Decision
1. **Match order** (pure `mergePage`): (a) explicit UUID (`id::` / `Block.uuid`), (b) remapped-source id
   (`src-id::` property, see 3), (c) whitespace-normalized exact content among siblings of an already-matched
   parent; short/empty content (< 4 chars after trim) matches only when parent context matches. No fuzzy matching.
   The key function is a parameter of `MergePolicy` so tests run both exact-trimmed and normalized.
2. **Same UUID, different content** = conflict. Target block is untouched; the incoming block is inserted as the
   next sibling with a fresh deterministic UUID and block properties `merge-conflict:: true` and
   `merge-source:: <sourceGraphName>`. Reviewed after commit via a "Review N conflicts" list (property query),
   never prompted per conflict. Scalar page/block property clash: keep target, record in outcome. `alias`, `tags`
   union as sets. `id::`, `collapsed::` are never merged as data.
3. **Deterministic remap**: every inserted source block, on every target (active and off-graph), gets
   `uuid' = SHA-256("merge:" + sourceGraphId + ":" + sourceUuid)` truncated to 128 bits and formatted as a UUID,
   and property `src-id:: <sourceGraphId>:<sourceUuid>`. It is NOT `UuidGenerator.generateDeterministic` (two
   FNV-1a 64-bit hashes; non-cryptographic) and there is no "keep the source UUID when free" rule. Remap is
   computed top-down; `((uuid))` refs and `{{embed ((uuid))}}` in all incoming content are rewritten through the
   same map (refs to blocks outside the selection are left as-is). A second run finds the existing `uuid'` and
   yields Unchanged, so merges are idempotent without a new table. Guard: before saving, the active writer
   checks `uuid'` is not already used by a different page (fail the page); the off-graph writer refuses a
   `uuid'` already present in the target file under a different `src-id`. Identity transport: block UUIDs
   survive stage -> disk -> parse because staging is structured JSON (`StagedPage`) and inserted blocks are
   written with `id::` plus `src-id::` (plan Story 1.1.4).
   Conflict sibling uuid: when a block matched by `src-id` (or by the same explicit uuid) has different
   normalized content, the incoming block is inserted as a sibling with
   `uuid'' = SHA-256("merge-conflict:" + sourceGraphId + ":" + sourceUuid + ":" + contentHash(normalized incoming content))`
   (128 bits, UUID-formatted), the SAME `src-id`, and `merge-conflict:: true`. An incoming block is `Unchanged`
   if ANY block under the matched parent has equal `src-id` and equal normalized content (the original copy or
   an earlier conflict sibling), so repeat copies add nothing and each distinct incoming content yields at most
   one conflict sibling. Deleting a conflict sibling in the target recreates it on the next copy.
   *Revisions*: rev. 1 specified `generateDeterministic` plus keep-source-uuid-when-free; rev. 2 withdrew both
   (an off-graph target's DB is closed so a free-uuid oracle is unanswerable, `blocks.uuid` is globally UNIQUE,
   and `insertBlock` is `INSERT OR REPLACE` keyed on uuid, `SteleDatabase.sq:335`, so a missed collision silently
   replaces another page's block) and chose SHA-256; rev. 3 added the conflict-sibling uuid (R4 edit-after-copy).
4. **Aliases**: stored as page property `alias` (`BlockPropertyKeys.ALIAS`, `page.properties`). v1 matches pages by
   filename-derived name only (cheap filename index); source `alias` values colliding with an existing target page name
   produce a "possible duplicate" warning in the dry run. Target-alias -> source-name matching needs a full scan and is deferred.
5. **Journals** match by date (not name string).

## Revision 4 (Repair pass 6): resolving a conflict sibling, and what "Remove this block" means
Judged against the UX review, the rule "deleting a conflict sibling recreates it on the next copy" is KEPT, with the UX changed instead of the model:
- The durable resolution is **Mark resolved** (formerly "Keep both"): it only removes the `merge-conflict::` flag. The block keeps its `src-id` and equal normalized content, so the "any block under the matched parent with equal `src-id` and equal content" rule makes a repeat copy `Unchanged`. It never comes back.
- **Remove this block** (renamed from "Remove copy" in Repair pass 7 to avoid colliding with "Undo this copy", which undoes a whole run) deletes the block, so by design a repeat copy of the same source content re-adds it (and flags it again). The UI must say so before and after the action (UX S8). This is correct because the target holds no record that the user rejected that content.
- Alternative considered and rejected for v1: a tombstone ("rejected `src-id` + content hash") so removed conflicts stay removed. It needs durable per-target state: a new table (`MigrationRunner`, regenerated SQLDelight, ADR churn) or a hidden property on a deleted block (impossible), or a sidecar file the markdown writer must also honour off-graph. That cost is not justified before demand is evidenced (requirements A-DEMAND). Revisit if metric M5 (conflict-sibling rate) or user feedback shows people repeatedly removing the same copies.

## Rejected
Fuzzy matching (wrongly merges "TODO"); per-conflict modal prompts (rejected in requirements); a `merge_provenance`
table (needs MigrationRunner + regenerated SQLDelight; a property is enough).

## Spike result (Story 0.1.3, 2026-10-09)
Verified by `MergeUuidRoundTripSpikeTest` (businessTest, commit 0a1304ef8d).
- `LogseqPageSerializer.serialize` emits only keys in `Block.properties`; it never writes `id::` from `Block.uuid`. Without `properties["id"]` the re-parsed uuid differs (deterministic hash). With `properties["id"]=U` the uuid round-trips, including nested parent links. `id::` injection is required (Task 1.1.4a).
- `SqlDelightBlockRepository.saveBlock` (INSERT OR REPLACE, `blocks.uuid` UNIQUE, `foreign_keys=1`) with an existing uuid on another page succeeds silently: the row moves to the new page and the old row's children and `block_references` are cascade-deleted. It does not throw or ignore.
- Consequence: the clobber guard (Task 1.2.1c) is MANDATORY. Merge must remap or refuse colliding uuids before any save.
- `QrImportService.import` delegates to `GraphLoader.importMarkdownString`. A payload `id::` is honored; blocks without it get uuids seeded by a fresh page uuid. It has no collision guard, so a colliding payload `id::` clobbers another page's block. (Only the `id::` case is tested; the rest is from reading the code.)
