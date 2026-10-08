# Build vs. Buy: cross-graph page merge

Date: 2026-10-07. Inputs: `requirements.md`, code read of `transfer/GraphMergeService.kt` (82 lines), `transfer/qrcode/QrImportService.kt` (182), `git/merge/*` (BlockDiff3.kt 205, Diff3.kt 121, SequenceDiff.kt 109, JournalMergeService.kt 135), `kmp/build.gradle.kts:86`, plus web search (links inline). Library claims come from search snippets, not from reading source: UNVERIFIED beyond that.

## Key framing

The requirement is NOT a three-way merge. There is no common base between two graphs. It is a two-way additive union (match by UUID, then by identical content; properties union; flag conflicts; never delete), which must be idempotent. Most merge libraries solve a different problem (diff3 needs a base; CRDTs need shared history from creation). That biases the answer toward custom logic over a thin, already-owned diff primitive.

## 1. OSS libraries

### 1a. Text diff / diff3 ports
Candidates: [kotlin-multiplatform-diff](https://github.com/lppedd/kotlin-multiplatform-diff) (java-diff-utils port, JVM/JS/native; no fuzzy patch), [GitLive kotlin-diff-utils](https://klibs.io/project/GitLiveApp/kotlin-diff-utils), [baole/diff-kotlin](https://github.com/baole/diff-kotlin), [PubNub diff-match-patch port](https://klibs.io/package/com.pubnub/pubnub-3p-diff-match-patch).

- Pros: battle-tested Myers/LCS; KMP-ready. SteleKit already depends on one (`io.github.petertrr:kotlin-multiplatform-diff:1.3.0`, `kmp/build.gradle.kts:86`), so adopting costs nothing.
- Cons: they produce diffs and patches over lines/chars, not outline trees. None gives a union merge. They need a base for a 3-way merge. Character-level DMP patching can corrupt block structure. Adopting a second library adds nothing over what we have.
- Verdict: **Viable only as the already-present primitive (LCS for ordering merged siblings); Not recommended as the merge solution.**

### 1b. CRDT / tree-merge libraries
Tree-move CRDTs exist ([Kleppmann et al. move op, e.g. Grove](https://hexdocs.pm/grove/readme.html), Elixir only). Search found no maintained KMP CRDT library with an outline-tree type (only [kuilt](https://klibs.io/project/tractat-us/kuilt), a networking lib with replicated types; not evaluated in depth). Loro/Automerge have Rust-core bindings, not KMP-complete (UNVERIFIED; not checked).
- Pros: principled convergence and idempotence by construction.
- Cons: need op history/IDs present from block creation. Our graphs are plain markdown files with possibly-divergent UUIDs, and the requirement is a one-shot import, not live sync (multi-device sync is out of scope). Would force a storage-model change and native deps on 4 platforms including WASM and iOS.
- Verdict: **Not recommended.**

### 1c. Existing in-repo three-way merge (`BlockDiff3` / `Diff3`)
Already block-granular with a content+level key and conflict chunks. Needs a base. Could fake an empty base, but then every shared block conflicts or double-adds. Not a fit as-is.
- Verdict: **Viable for reuse of `blockKey` and normalization ideas, Not recommended as the engine.**

## 2. SaaS
Local-only, no network (requirements: security classification internal, no network). Hosted merge or sync services violate this and add nothing for a local file operation.
- Verdict: **Not recommended (N/A).**

## 3. LLM-generated bespoke merge vs. library; when custom is justified

No library matches the semantics (section above), so custom is justified. The risk is correctness, which is the real question: an LLM-written merge is acceptable only if the spec is machine-checked. Recommended guardrails:
- Implement as a pure function in `commonMain` (`merge(target: List<Block>, source: List<Block>): MergeOutcome`) with no repository access, so it is testable once for all four platforms (CLAUDE.md: pure logic in commonMain/commonTest).
- Property tests with `kotest-property` (`Arb`/`checkAll` inside `runTest`, per CLAUDE.md) as the acceptance gate, over generated block trees:
  - Idempotence: `merge(merge(t,s), s) == merge(t,s)`.
  - No loss: every target block and every source block (by UUID or normalized content) is present in the result.
  - Additive only: target blocks keep content, and relative order of target siblings is preserved.
  - Commutativity of the block set (not order) where no conflicts exist; properties union is associative.
  - Self-merge is the identity; empty source is the identity.
  - Round-trip through `LogseqPageSerializer` / parser preserves the merged tree.
- Keep example tests for the named cases (same UUID different content = conflict; same content different UUID = deduplicated).
- Verdict: **Recommended** (custom pure function, property-test-gated, LLM-assisted authoring is fine because properties, not review, establish correctness). Custom is not justified for the diff/LCS primitive itself; reuse the existing library there.

## 4. Fork / adapt existing SteleKit code

| Asset | What it gives | Gaps | Verdict |
|---|---|---|---|
| `GraphMergeService` (`transfer/GraphMergeService.kt`) | Snapshot-then-merge flow, `GraphMergeResult` shape, logging, writeActor use | Whole-graph `getAllPagesSnapshot()` plus full markdown held in memory (violates 8k-page SLO); skips existing names (the exact bug); no selection or dry-run; relies on single-open-graph | **Recommended to evolve in place** (keep API/result shape; replace the skip branch with a call to the new merge function; chunk the snapshot) |
| `QrImportService` (`transfer/qrcode/QrImportService.kt`) | Markdown import via `GraphLoader` with writeActor, collision lookup (`findCollision`), `CollisionChoice`, disambiguation | Collision model is page-level (rename/replace/skip), not block-level | **Viable**: reuse as the "write a new page" path; do not extend with merge logic |
| `git/merge/BlockDiff3`, `Diff3`, `SequenceDiff` | Block flattening, content+level `blockKey`, LCS ordering, conflict chunk type | Three-way, needs base; operates on `ParsedBlock` rather than DB `Block`; lives under `git` package | **Viable**: lift `blockKey`/flatten helpers into a shared util; **Not recommended** to reuse `merge()` |
| `JournalMergeService` | Journal-specific flow with backup file, confidence warning, conflict markers | Tied to `ConflictFile`/git; out of scope per requirements | **Not recommended** (explicitly out of scope) |

## Recommendation

1. Build a small pure two-way "additive union" merge in `commonMain` (new file beside `transfer/`), keyed UUID first then normalized content+level, reusing `BlockDiff3`'s key idea and the existing diff library only for sibling ordering.
2. Gate it with kotest-property invariants above before wiring any UI.
3. Evolve `GraphMergeService` and reuse `QrImportService` for new-page writes; do not adopt a CRDT or new third-party merge dependency.

## Open items (not resolved here)
- Whether normalized (vs. exact trimmed) content is the "same block" test: a product decision; the property tests should be parametrized on the key function.
- Cross-graph write architecture (multi-open `GraphManager` vs. file write vs. inbox) is outside build-vs-buy and needs its own research note.
