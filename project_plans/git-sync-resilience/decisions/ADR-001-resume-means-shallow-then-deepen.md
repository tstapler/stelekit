# ADR-001: "Resume" means shallow-clone checkpoint + retry, not byte-level pack resume

**Status**: Accepted
**Date**: 2026-09-23
**Project**: git-sync-resilience

## Context

`requirements.md`'s success metrics ask for a clone/fetch "interrupted partway through a
large-repo transfer" to "resume from where it left off (not from 0%)... measured by comparable
retry latency to transferred-bytes-remaining, not full re-download time." Read literally, this
implies byte-level resumption of a partially-received pack.

Three independent research passes confirm this is not achievable:

- `research/stack.md` §6: decompiled JGit 7.3.0's `TransportHttp` — no `Range`/`Content-Range`
  header support anywhere in the class. JGit's `ObjectDirectoryPackParser` writes to a temp file
  and only renames it into the object database on a *successful* parse; an interrupted transfer
  leaves no usable partial pack.
- `research/build-vs-buy.md` §2: native `git` itself has no resumable clone either — a
  `git clone --continue` flag that an initial web search suggested was verified false against
  git-scm.com's own docs. The one real attempt at protocol-level resume (a 2016 git
  mailing-list proof-of-concept, ["Resumable clone
  revisited"](https://ratatoskr.run/git/2016/06/7671772/t)) never merged; its own author called
  it un-mergeable in its current form, it required disabling parallel delta search server-side
  (which this project cannot demand of arbitrary GitHub/GitLab/self-hosted remotes), and it
  carried a documented corruption risk from "frankenstein packs" assembled across multiple
  `pack-objects` runs.
- `research/architecture.md` §1c: confirmed empirically against this codebase's own JGit
  integration that an interrupted `clone()` cannot be resumed at the pack level.

## Decision

"Resume" is redefined, and implemented, as **checkpoint-by-depth**: a clone defaults to shallow
(`CloneCommand.setDepth(DEFAULT_CLONE_DEPTH)`), so every retry re-transfers only the bounded
shallow pack rather than the full repository history. Once a shallow clone succeeds, that
checkpoint (`git_config.clone_depth_state = SHALLOW`) is durable — a later "deepen"
(`FetchCommand.setUnshallow(true)`) is itself a separate, independently retryable step, and a
failure there leaves the repository in its already-successful shallow state, not half-cloned.

This is not literal byte-level resume. An interruption mid-shallow-clone still re-transfers that
shallow step's data from zero on retry. What it delivers is the practical goal implied by the
requirement: bounding the cost of a retry to a small, fixed-size unit instead of the full
repository, so a large-repo clone becomes completable on a flaky connection.

## Consequences

- The success metric in `requirements.md` ("comparable retry latency to
  transferred-bytes-remaining") should be read as "retry cost is bounded by shallow-clone depth,
  not full-repo size" — the plan phase reworded this rather than treating the literal wording as
  a hard target research/architecture confirmed is infeasible.
- Every retry of an *unwidened* shallow clone still re-transfers the full shallow pack — there is
  no partial credit within a single shallow-clone attempt. This is acceptable because the shallow
  pack is small by construction (depth-bounded), not because partial credit was achieved.
- Shallow history has real downstream costs the plan must also address: `MergeStrategy.RECURSIVE`
  can produce a degraded or failing merge once local/remote history diverges past the shallow
  boundary (Story 2.1.5), and any UI/logic assuming full history (`countRemoteCommitsBestEffort`,
  future commit-log views) must tolerate a shallow repository.
- JGit's `PackParser` trailing-checksum verification and `ObjectChecker` already guarantee a
  corrupt/partial transfer is rejected before anything is committed to the object database
  (`research/build-vs-buy.md` §4) — this project does not need, and must not build, a parallel
  pack-integrity verifier on top of the checkpoint mechanism.

## Alternatives Considered

- **True byte-level/protocol-level resume** — rejected: no prior art landed anywhere, even in
  mainline git after a decade; requires server-side cooperation outside this app's control;
  real corruption risk per the 2016 POC's own author.
- **Switch to a different transport library with native resume** — rejected: no such
  JGit-compatible JVM library exists (searched, not found); a transport swap is also out of
  appetite and would forfeit JGit's deep integration with this codebase's shadow-worktree/merge/
  auth handling.
