# ADR-003: LFS guard semantics: what is refused, where, and the one exception

**Status**: Proposed (ships first, Phase 1; hold-back behaviour is an owner veto point, O6)
**Date**: 2026-10-11
**Deciders**: Repo owner (Tyler Stapler)
**Related**: ADR-001, ADR-002, `../research/pitfalls.md` R3, R5, R9, R16, `../research/features.md` section 3

## Context
Safety floor (`../requirements.md`): on every platform SteleKit never commits or uploads a raw binary for a path whose `.gitattributes` says `filter=lfs`. Today there is no LFS awareness anywhere in `kmp/src`. Staging entry points found by reading the code:
- `GitSyncService.sync()` step 5: `findFilesWithConflictMarkers` then `stageSubdir` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt:321-331`).
- `GitSyncService.commitLocalChanges` stages with `stageSubdir` at `:580` and does NOT run the conflict-marker guard (a gap the LFS guard must not copy).
- `GitSyncService.resolveConflicts` calls `markResolved` at `:618`, `:641`, `:692`, then commits.
- Repository adapters: `JvmGitRepository.kt:243,245` (`git.add`), `:430` (`markResolved`), `:253` (`commit`); `AndroidGitRepository.kt:256,258,266,495`.
- Web: `WasmGitWriteService.commitGitHub` (`:385`, blobs via `createBlob` `:457`) and `pushViaGitLab` (`:991`).
- iOS: unreachable today (stub), but must call the guard if git ships.

## Decision
### 1. What is a violation
A path P in the set of changed, added or staged paths is a violation iff both hold:
1. `LfsPathPolicy.isTracked(P)` is true: the resolved `filter` attribute for P equals `lfs` (last matching rule wins per attribute; deeper `.gitattributes` overrides shallower; `-filter` and `!filter` clear it; `filter=other` replaces it). `.gitattributes` files themselves are never tracked (git-lfs never tracks them).
2. The content to be committed is not a spec-valid pointer (`LfsPointer.parse` returns Valid) and is not the empty file.
Reasons recorded: `RawBinary`, `CorruptPointer` (parses as pointer-like but CRLF, BOM, unsorted keys, over 1023 bytes, etc.), `ConflictMarkers` (a pointer file with merge markers).
Exempt: zero-byte files (git-lfs stores empty files as raw empty blobs; INFERRED, confirmed by the differential test in Task E1.2e when `git-lfs` is on PATH, otherwise the exemption is documented as unconfirmed).

### 2. Where it runs (all of these, none optional)
| Layer | Site | Mechanism |
|---|---|---|
| Service (input per section 2a) | `sync()` step 5, `commitLocalChanges`, `resolveConflicts` (before every `markResolved`) | `GitSyncService.enforceLfsGuard` before staging |
| Repository (defense in depth) | JVM and Android `commit()` | `LfsIndexVerifier` walks the index; any tracked path whose staged blob is not a pointer aborts the commit with `LfsRawBinaryRefused` and un-stages it |
| Web | `commitGitHub`, `pushViaGitLab`, `createBlob` (path parameter) | `LfsGuard` on the dirty set before any blob is created; `createBlob` re-asserts |
| Web read-merge | `applyRemoteContent` | Never writes decoded binary; pointer text is written as is |
| iOS | Future git implementation | Must call `LfsGuard` before `stageSubdir`; regression test asserts the stub never reaches commit |

Attribute source: working tree plus index plus `.git/info/attributes`, evaluated per operation, never cached across a pull (R16). Web fetches `.gitattributes` files from the remote at the base ref for the tree being committed; a network failure while fetching them fails closed with `NetworkFailure`; a 404 means "no rules".

### 2a. Guard input per mode
With `LfsMode=AUTO` and the clean filter active for that repo (`cleanFilterActive`, read back from config), a raw working file at a tracked path is expected and is converted by `git add`; the service guard then does NOT judge the working file. The authoritative check is `stagedLfsViolations` (the `LfsIndexVerifier`) after staging: any tracked path whose index blob is not a pointer is un-staged and held. In `GUARD_ONLY`, `OFF`, flag-off Android, system-git sparse graphs and iOS, the working file is judged before staging. On web the service-level coordinator is `Delegated` (returns nothing); `WasmGitWriteService` is the sole guard, using rules fetched from the remote at the base sha, and decides held (ReadOnly) versus converted (Direct, Proxy). The pre-commit index verifier runs in every mode on JVM and Android. `LfsLegacyRawDetector` (section 4) runs only when the filter is active.

### 3. Behaviour on violation: hold back, do not block
Violating paths are excluded from staging (`stageSubdir(config, excludePaths)`: stage the pattern, then reset the held paths in the index; web: removed from the dirty-set snapshot used for the commit, they stay dirty). Everything else commits and pushes. Sync state becomes `LfsActionRequired(held)` (non-fatal). The held files stay in the working tree untouched. Rationale: refusing the whole commit (the conflict-marker pattern) would stop all text notes syncing because of one pasted image. The index verifier remains a hard failure (`LfsRawBinaryRefused`), because reaching it means a path escaped the hold-back.
A page that references a held asset is still pushed; the UI says so in the held-files sheet.

### 4. Already-raw-in-history and mass conversion (R5)
- A tracked path whose HEAD blob is raw and whose working file is unchanged is not a change and is not a violation. It is surfaced only in the first-sync review sheet and as a persistent count in the LFS settings section ("N files stored raw but tracked by LFS"), never as a per-sync `LfsActionRequired` nag; they are silently excluded from staging until the user confirms. A graph with no `filter=lfs` rule renders no LFS UI.
- When the clean filter is active (JVM or Android after Story E2.3) a tracked file whose content equals the raw HEAD blob would show as modified (clean output is a pointer). `LfsLegacyRawDetector` classifies these as `LegacyRawPending`: they are NOT auto-committed. The user confirms once per graph (`LfsMigrationConfirmation`, same pattern as `FirstSyncConfirmation`), after which the conversion commit is allowed.
- A tracked file the user actually edited is converted normally (pointer committed, object uploaded).

### 5. `.gitattributes` edits
Evaluated from the working tree and index, so a `.gitattributes` change staged in the same commit applies to that commit. A new rule never retroactively flags unchanged files (they are not in the changed set). Removing a rule permits raw commits for those paths and logs a warning. A commit that modifies `.gitattributes` is surfaced in the sync result text, not refused.

### 6. Override or escape hatch
None in v1. There is no setting that commits a raw binary to a tracked path. The remedy offered in the UI: sync from a platform with LFS support, or move the file out of the tracked folder. Revisiting needs a new ADR.

### 7. The only exception: `LfsStagingRefException` (blocked on owner decision O2)
A raw blob may be created only when ALL hold: strategy `Converter` is enabled (`lfsConverterEnabled`), the target ref matches `^refs/heads/stelekit/lfs-inbox/[A-Za-z0-9_-]+$` (never the configured branch, never any other ref), the paths in that commit are all LFS-tracked, the converter workflow file exists in the repository at the base ref, and the client deletes the ref after the workflow succeeds or the user cancels. `LfsGuard` takes a `StagingRefScope?` parameter; when it is null (default) there is no exception. If O2 is NO the class is not built.

### 8. Failure UX
One-line messages (exact text lives in Task E1.3a and is asserted by `DomainErrorTest`):
- Held: "Sync paused for assets/photo.png: this repository stores that path with Git LFS and SteleKit cannot upload it here yet. The file was not committed. Move it out of the tracked folder or sync from desktop." (proposed text from `../research/features.md` section 3)
- Index verifier: "Refusing to commit assets/photo.png: it must be stored with Git LFS."
The held-files sheet (`LfsHeldFilesSheet`) lists paths with the reason and the remedy.

## Consequences
- The guard is a pure function over (paths, rule set, content probe), tested by property tests on all platforms (`LfsGuardPropertyTest`).
- The `GitRepository.stageSubdir` signature gains `excludePaths: Set<String> = emptySet()`; implementers: JVM, Android, wasm (no-op: wasm uses the dirty-set path), iOS stub, `StubGitRepository`.
- `commitLocalChanges` also gains the existing conflict-marker guard parity check as a side benefit (Task E1.4d notes it; not an LFS behaviour).
