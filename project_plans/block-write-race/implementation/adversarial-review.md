# Adversarial Review: Block Write Serialization Fix

**Plan**: `project_plans/block-write-race/implementation/plan.md`
**Verdict**: CONCERNS
**Reviewer**: Automated adversarial subagent + planning agent follow-up verification

---

## Top 3 Findings

### Finding 1 (LOW — INVALID after verification)

**Claim**: `applyContentChange` debounce assumption unverified. If a debounce exists before `writeActor.updateBlockContentOnly`, structural ops could arrive in the actor queue before their content write.

**Verification**: `BlockStateManager.applyContentChange` (line 524–552) calls `writeContentOnly(blockUuid, content)` at line 535 — immediately, with no debounce before the actor enqueue. The debounce applies only to the disk path (`queueDiskSave` at line 551). FIFO guarantee is intact.

**Status**: INVALID — no action required.

---

### Finding 2 (MEDIUM — VALID)

**Claim**: `Channel.isEmpty` returns `true` as soon as the actor *dequeues* a request, before the DB transaction commits. An external file sync event during a slow `mergeBlocks` (10–100ms on Android) would see `hasPendingWrites == false` and suppress the conflict dialog incorrectly.

**Verification**: Confirmed. The actor's processing loop dequeues the `Execute` request and then calls `request.op()` — the channel is empty the moment `receive()` returns, potentially 10–100ms before the SQL transaction inside `op()` completes.

**Status**: VALID. The plan's claim that `isEmpty` is "reliable within a single coroutine dispatch" is incorrect — it covers only the enqueue→dequeue gap, not the dequeue→DB-commit gap.

**Plan patch applied**: Story 3 updated to use an `AtomicInt` active-ops counter instead of `isEmpty`. See plan.md — Story 3, Task 3a (revised).

---

### Finding 3 (LOW — VALID but mitigated by existing guards)

**Claim**: `deleteBlockStructural` bypasses undo-logging on an unverified assumption that the block is always empty. A non-empty block routed through `handleBackspace` branch B could be silently deleted without recovery.

**Verification**: Both `deleteBlock` call sites in `handleBackspace` are guarded by explicit `currentBlock.content.isEmpty()` checks (lines 995 and 1017). Content is empty by the time the delete fires from the in-memory state read before the coroutine suspends. However, a pending content write in-flight could mean the DB has older content than the actor queue — the delete would remove a block that logically has typed content. The existing `actor.deleteBlock` (with op-logger) would capture the DB-side content (empty/stale), not the pending content. Using `deleteBlockStructural` is no worse than `actor.deleteBlock` for this edge case, but the plan's "no meaningful undo" reasoning is imprecise.

**Status**: VALID concern but lower risk than claimed. Mitigated: the `currentBlock.content.isEmpty()` guard is read from in-memory `_blocks` state which already has the latest typed content (optimistic). The delete only fires if the in-memory content is empty. If the user typed something but the content write is still pending, the UI would show non-empty content and the guard would NOT trigger the delete. The race does not apply to the delete paths.

**Plan patch applied**: Story 1 clarifies `deleteBlockStructural` semantics; wording tightened to document the `isEmpty` guard dependency.

---

## Assessment

The plan is structurally sound. Finding 2 is the only actionable correction: the `hasPendingWrites` approximation using `Channel.isEmpty` is weaker than documented. The patch (AtomicInt counter) is small and additive. Findings 1 and 3 do not require plan changes.

After the Story 3 patch: **CLEAN for implementation.**
