# ADR-008: Two devices editing one sheet merge element by element; the conflict copy stays as the undecidable fallback

**Status**: Accepted (2026-11-25, epic E14; answers open question 7 of `research/world-class-critique.md` with "yes, phone plus tablet is the natural setup")
**Date**: 2026-11-25

## Context
ADR-001 made each sheet one file, `sheets/<uuid>.v2.json`, written only when the bytes on disk still hash to the saver's `baseSha`. A file that changed under the saver (a git pull) was never overwritten: the in-memory scene went to `conflicts/<uuid>.v2.conflict-<hash8>.json` and the user chose Keep mine or Use theirs (critique finding 16). Nothing was lost, but two phones editing one sheet never produced a combined drawing.

## Decision

### 1. Where the merge runs
`SceneMerge.merge(base, ours, theirs)` (pure, commonMain, `sketch/v2/merge/`) returns `Either<MergeRefusal, MergeResult(doc, report)>`. `SheetPersister.persistOnce` calls it, under the same `SidecarSlugLock` and the same read of the file that the `baseSha` check used, when that check says `Changed`. The merged scene is encoded with the one codec and written with the existing write path, so `SidecarBehindGuard`, the `StaleOpaque` replan, the writer lease and durability are all unchanged. `base` is the document of the bytes `baseSha` names: the loaded sheet at open (`SheetTarget.loadedDoc`), then the bytes the saver last wrote or adopted. A saver without a base (`loadedDoc == null`, a never-saved sheet) never merges.
The merge is opt-in per saver (`SheetSaverPorts.mergeOnConflict`, set by the editor session). Off, or on any `MergeRefusal`, an unreadable or missing file, or a second merge before the editor took the first, the saver does exactly what it did before: it writes the conflict copy and raises the `SaveConflict` banner.
Not touched: the no-unsaved-edits path. A file that changed while the editor holds nothing unsaved still raises the Reload prompt (`onExternalChange`).

### 2. Merge unit and the version stamp
The unit is the element id. `ElementBase.version` already exists and is bumped by every edit, but it is a per-element edit count: it carries no clock and no device id, so two devices that each edit once both reach the same number. It is therefore used only as a cache stamp (the draw list caches by `(id, version, opacity, style)`), never to decide a winner. Adding a wall-clock or device-id stamp would need every edit path to write it and would still depend on device clocks; not done (see section 9).

### 3. Rules per element (three-way against `base`)
- Only one side has it: absent from base is an **add** (kept); present in base and unchanged on the surviving side is a **delete** (dropped).
- **Delete against edit keeps the edit** (reported as `DeleteVsEdit`). Nothing a person drew is dropped because someone else deleted it.
- On both sides, the element's JSON object (the file's own form, `SheetCodecV2.elementJson`) is merged **field by field**; `style.*` and `extras.*` keys are separate fields, so an agent's `provenance` accept and a move on the other device both survive. A field changed on one side takes that change.
- Fields that only make sense together are one field: `x,y`, `cx,cy`, `rx,ry`, `w,h`, `text,orig`, `host,seg,off`, `a0,a1`. A move on both devices therefore never mixes one device's `x` with the other's `y`.
- **Same field changed on both sides to different values**: the greater canonical JSON text (`CanonicalJson`, compared as UTF-16 code units) wins. This is a total order on values, which is what makes the merge commutative, idempotent and associative on content. It is arbitrary by design, because no recency exists in the file; the loser is reported (`SameField`) so the notice can say so. Add against add of one id behaves the same (`AddAdd`).
- If the two sides disagree on the element kind, the element is taken whole by the same order (`TypeChange`). If a field-wise result is refused by the model (a closed wall with two points), the element is taken whole from the greater side (`Unbuildable`).
- Result `version`: both sides equal, the higher; result equals one side only, that side's (plus one when the sides' versions are equal); otherwise the higher plus one. So a changed element never keeps a version the editor's cache already holds, and merging a merge result back changes nothing (absorption).

### 4. Sheet-level data
- Constraints, by `ConstraintId`, atomic: same add/delete/edit rules, greater canonical key wins a double edit.
- Layers, by id, per field (`name`, `visible`, `locked`, `opacity`, `order`). An element whose layer was deleted moves to the default layer (reported).
- Sheet `extras`: per top-level key, same field rule. `nameCounters`: per key maximum. Two devices can issue the same ordinal to different new elements ("Rectangle 5" twice); that is accepted, since ids, not ordinals, are identity, and a renumbering pass would break the merge laws.
- Paint order is rebuilt as (layer order, `zKey`, id), the key `DrawListBuilder` already paints by. Two devices appending at the same z key stack in id order on both.

### 5. Consistency repair (never a crash, never silent)
After the field merge: openings whose host wall is gone are dropped (`OpeningDropped`); `reconcile` runs (dangling bindings, containers, frames, groups, opening refit to a moved wall; each reason is in the report); constraints whose operand or part is gone are dropped (`ConstraintDropped`). A dimensional constraint that the merged geometry no longer satisfies is not touched: the solver's normal conflict status reports it.

### 6. Determinism
Output depends only on the three documents, never on argument order, hash iteration order or floating-point formatting: keys are iterated sorted, values compare through `CanonicalJson` text, doubles are never compared by formatting. Whole-document shortcuts are exact: ours equals theirs returns ours; ours equals base returns theirs byte for byte (a fast-forward writes what is on disk); theirs equals base returns ours. Laws, tested on generated sheets (`SceneMergeLawsTest`): commutative and idempotent byte for byte, absorbing (`merge(base, theirs, merge(base, ours, theirs))` equals the merge), associative on content (element versions excluded, section 3).

### 7. Undecidable: keep the conflict copy
`MergeRefusal`: different sheet uuids; the merged sheet would exceed an element, constraint or layer cap; a merged element cannot be encoded. Also: no base, the file on disk is unreadable, or a previous merge was not yet taken by the editor.

### 8. After the write: the editor must take the merge
The saver publishes `SheetMerge(ours, merged, report)`. The editor replaces its scene with `merge(ours, itsCurrentScene, merged)` (so edits made while the save ran survive), submits it, then calls `acknowledgeMerge`. Until then every save first rebases the submitted scene onto the merge the same way, and a rebase that is refused writes a conflict copy: a stale editor scene can never write over a merge. The user sees a non-blocking toast "Merged changes from another device (N elements)" with a Show action that selects the merged elements and brings the first into view. Provenance/ghost styling was not reused: it is agent-specific (`needsReview`) and Show's selection is the cheaper cue; revisit with user feedback.

### 9. Tombstones (designed, not written in this epic)
With a known `base` a delete is observable (present in base, absent now), so the merge needs no tombstone, and none is written; old builds see no new field. For a base-less merge (a future background reconcile of two files with no common ancestor in memory) the additive form would be `extras.tombstones`: `{ "<elementId>": <versionAtDelete> }`, written by the deleting save, ignored by old builds like any unknown extra, and a deleted id would lose to an edit with a higher version. Garbage collection: drop an entry when the saver has itself written a file that no longer contains the id and the entry's file revision has been the base of a successful save (every device that could resurrect it has since rebased), and cap the map at `SceneLimits.maxElements`. This also covers the older outstanding synced-deletion-tombstone item for sheets; project-level deletes (whole sheets) are a different file and out of scope.

## Compatibility
No new required field, no schema change, no migration, no `.sq` change. A sheet written by a merge is an ordinary schema-2 sheet; older builds read it, and their fail-closed rules (newer schema refused) are unchanged. An older build on the other device still produces conflict copies, which this change leaves alone.

## Known limits
- A same-field conflict resolves by value order, not recency: the "wrong" device's edit can lose. It is reported, and the loser stays in git history.
- Merge cost is linear in the changed elements plus one JSON projection of each; the unchanged majority short-circuits on equality.
- Device behaviour (two real phones, git sync timing) is not covered by CI.

## Alternatives rejected
A per-field Lamport or wall-clock stamp (every edit path changes, clock skew decides winners); CRDT sequence for paint order (the z key already is a fractional index); resurrecting deletions silently on tie (data would reappear unannounced); always writing the conflict copy (status quo).
