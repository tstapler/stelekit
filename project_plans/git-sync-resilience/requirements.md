# Requirements: git-sync-resilience

**Date**: 2026-09-23
**Type**: feature addition (hardening of an existing cross-platform subsystem)
**Complexity**: 4 — high-stakes / cross-cutting

## Problem Statement
SteleKit's git sync (clone/fetch/push, backed by JGit) has no resilience against transient
network interruption. A user hit this directly: cloning a large graph on Android failed with
`Clone failed: Software caused connection abort` — the JVM's canned message for a locally
torn-down blocking socket (screen lock, app backgrounded, OS/OEM battery-manager network
throttling), not an out-of-memory symptom. One interrupted socket read fails the whole
operation with no recovery, and — worse for large repos — restarts the transfer from zero on
manual retry.

This affects both platforms that currently implement git sync:
- **Android** (`AndroidGitRepository.kt`): clone runs as a plain suspend call on the setup
  screen's own coroutine scope (`GitSetupScreenSaveLogic.performCloneAndSave`), not a
  foreground service or `WorkManager` job — only the periodic background *fetch* uses
  `WorkManager` (`WorkManagerSyncScheduler.kt`). Android's Doze mode and OEM battery managers
  (Xiaomi, Samsung, Huawei, OnePlus, Asus) kill network access for backgrounded/idle apps —
  already documented as a known risk in `project_plans/git-integration/research/pitfalls.md`
  section 1, but that mitigation was only ever built for the periodic sync path, never the
  initial clone/fetch/push screens.
- **Desktop/JVM** (`JvmGitRepository.kt`): identical gap in the shared JGit call pattern — no
  connect/read timeout, no retry, no depth limit. Exposure trigger differs (laptop
  sleep/hibernate, Wi-Fi handoff) but the failure mode and fix are the same code shape.

iOS (`IosGitRepository.kt`) is currently a complete stub — every operation returns
`DomainError.GitError.NotSupported("iOS")` pending kgit2 integration — and Web has no git sync
implementation at all (no `jsMain` git code exists). Neither has an in-scope clone/fetch/push
path to harden today.

## Baseline
Today, a user starts a clone/fetch/push and must keep the app foregrounded (Android) or the
machine awake (Desktop) with an unbroken network connection for the entire operation. One
transient failure — a network hiccup, a screen lock, an OS network-kill mid-transfer — aborts
the whole operation with a raw `CloneFailed`/`FetchFailed`/`PushFailed` error and no automatic
recovery. Retrying a large clone starts over from 0% every time, so a user on a spotty
connection with a large graph can be unable to complete an initial clone at all.

## Users / Consumers
SteleKit end users who sync a wiki graph via git on Desktop or Android, especially those with
larger graphs (longer transfer time → larger exposure window) or less reliable
connectivity/power management (mobile networks, aggressive OEM battery savers, laptops that
sleep).

## Success Metrics
- A transient transport failure (e.g. `SocketException`, connection timeout) during
  clone/fetch/push is retried automatically with backoff, without user intervention, instead
  of failing the operation outright.
- A clone/fetch/push interrupted partway through a large-repo transfer resumes via
  shallow-clone-then-progressive-deepen checkpointing, not a full re-download of the whole
  repository, on the next attempt — see
  `project_plans/git-sync-resilience/decisions/ADR-001-resume-means-shallow-then-deepen.md` for
  why literal byte-level/transferred-bytes-remaining resume is infeasible (no JGit, native-git, or
  alternative-library support exists) and what this delivers instead. This does **not** guarantee
  comparable-to-bytes-remaining retry latency in all cases: a retry after failing partway through
  the *initial shallow clone* still re-transfers that shallow step from zero (no partial credit
  within a single shallow-clone attempt — acceptable because the shallow pack is small by
  construction, not because partial credit was achieved); only a failure *after* the shallow step
  has already succeeded, during a subsequent deepen (`unshallow()`) attempt, avoids
  re-transferring the already-fetched shallow history.
- On Android, an in-progress clone/fetch/push survives the app being backgrounded or the
  screen locking (foreground service keeps the transfer alive instead of the OS killing its
  network access).
- Large-repo clones default to a depth-limited (shallow) clone, shrinking both transfer time
  and the window during which an interruption can occur.
- The wizard's Step 5 progress UI shows retry/resume status ("Retrying… attempt 2/4",
  "Resuming — fetching remaining history") rather than looking hung during a multi-attempt
  operation.
- Regression: existing clone/fetch/push behavior for the common case (stable connection,
  foregrounded app, small-to-medium repo) is unchanged in latency or UX.

**Closure note**: Phases 1-2 (retry/backoff, shallow clone, resume) alone do **not** close
this project's originating bug report — the reported failure is Android-specific
(foreground-killed transfer), so it is only actually fixed, and only validated, by Phase 3
(Android foreground-service survival) plus its real-device gate. See
`project_plans/git-sync-resilience/implementation/plan.md`'s top note and
`implementation/validation.md`'s **Phase 3 Manual Release Gate** section — do not close the
originating issue/backlog item until that gate has passed.

## Appetite
Large (3–6 weeks)
*(User confirmed: real budget for retry+backoff, depth-limited clone, Android
foreground-service survival, AND true resume-from-interruption across Desktop + Android — not
just config flags.)*

## Constraints
- No deadline stated.
- No existing feature-flag infrastructure in this codebase (grepped `kmp/src/commonMain` —
  none found); risk control below relies on the standard release-please pipeline instead of a
  runtime flag.
- No existing crash-reporting/analytics/telemetry infrastructure in this codebase either, so
  there is no usage data on how many users hit this bug — the Large appetite is justified by
  the reported incident's severity and reproducibility, not by volume data.
- Must not regress the existing `@DirectSqlWrite`, dispatcher-matrix, and coroutine-scope-
  ownership rules in `CLAUDE.md` — any new background work (e.g. an Android foreground
  service) must own its own `CoroutineScope`, never receive a `rememberCoroutineScope()`.
- JGit (7.3.0 on both Desktop and Android per `AndroidGitRepository.kt`'s doc comment) has no
  first-class resumable-clone API — true resume needs custom handling built on top of, or
  alongside, JGit's transport layer (see Rabbit Holes).

## Non-functional Requirements
- **Performance SLO**: not specified beyond "resume should avoid re-transferring
  already-received bytes" — no hard p99 target.
- **Scalability**: sized for the graphs this app already handles (large-graph regression
  tests in this repo use an 8,000+ page graph as the reference scale for warm-start/import
  work — reuse that scale as the "large repo" baseline for clone/fetch benchmarking, not a
  fixed byte count).
- **Security classification**: internal — this touches transport/auth code
  (`AndroidGitAuthConfigurer`/`JvmGitRepositoryAuth`) but does not change what credentials are
  stored or how; no new data leaves the device.
- **Data residency**: not applicable (user-configured git remotes, unchanged).

## Scope
### In Scope
- Retry-with-backoff for transient transport failures (`SocketException`, connect/read
  timeout, and similar) on `clone`, `fetch`, and `push`, on Desktop and Android — same JGit
  call shape on both platforms.
- Explicit connect/read timeout configuration for JGit transports (currently unset — falls
  back to JGit/JSch defaults) so a stalled connection fails fast enough to trigger retry
  instead of hanging indefinitely.
- Depth-limited (shallow) clone as the default for new clones of large repos, per the existing
  mitigation already named in `docs/tasks/git-sync.md:35`.
- Resume-from-interruption for clone (and fetch, where applicable) so a retried operation
  continues from its last checkpoint rather than re-transferring from zero — the plan phase
  must determine the concrete mechanism (see Rabbit Holes) given JGit's limited native
  support.
- Android: a foreground service (or equivalent wake-lock-holding mechanism) that keeps an
  in-progress clone/fetch/push alive when the app is backgrounded or the screen locks,
  including the required notification and Android 14 foreground-service-type declaration.
- Wizard UX: Step 5 ("Test & save") progress display shows retry attempt count and/or resume
  percentage during a multi-attempt operation.
- Regression coverage proving the common-case (stable network, foregrounded, small/medium
  repo) path is unaffected.

### Out of Scope
- iOS git support (still a `NotSupported` stub — out of scope until iOS git ships as its own
  project).
- Web git support (no implementation exists — out of scope).
- Any new feature-flag/runtime-toggle infrastructure — none exists in this codebase today and
  this project won't introduce one (see Risk Control).
- Changing what credentials are stored, how they're stored, or the auth flows themselves
  (`AndroidGitAuthConfigurer`/`JvmGitRepositoryAuth` internals stay as-is beyond timeout
  wiring).
- Sparse checkout (JGit's support is limited per `project_plans/git-integration/research/
  pitfalls.md:318`) — depth-limiting is in scope, path-based sparse checkout is not.
- Desktop background-survival equivalent to the Android foreground service (Desktop has no
  OS-level background-execution restriction analogous to Android's — per
  `project_plans/git-integration/research/pitfalls.md:60`, the only Desktop risk is
  sleep/hibernate mid-transfer, which retry+resume already covers without needing a
  service-equivalent).

## Rabbit Holes
- **True resumable clone is not a JGit built-in.** JGit's `CloneCommand`/`FetchCommand` have
  no checkpoint/resume API; the git wire protocol itself supports resuming a fetch via
  `have`/`want` negotiation against partially-received refs, but partially-received *pack
  data* is not resumable via stock JGit. The plan phase must resolve whether "resume" means
  (a) git-protocol-level resumption (re-negotiate from the last fully-received object/pack,
  discarding a partial in-flight pack chunk) or (b) byte-level HTTP range-resume (only
  possible over smart-HTTP with a server that supports it, not SSH) — these have very
  different implementation costs and guarantees. Treat this as the single highest-uncertainty
  item in the whole project; research phase should pull in JGit's own resumability
  limitations before planning commits to a mechanism.
- **Android foreground service type declaration (Android 14+).** Foreground services must
  declare a specific type (`dataSync`, `shortService`, etc.); picking the wrong one or missing
  the manifest/runtime permission pairing is a common source of `ForegroundServiceStartNotAllowedException`
  at runtime, not compile time — needs explicit validation against a real device/emulator on
  a recent API level, not just code review.
- **Retry/backoff interacting with the shadow-worktree freshness precondition.** `AndroidGitRepository.openGit()`
  runs `ensureFresh()` before working-tree-touching ops (see `CLAUDE.md`'s coroutine/dispatcher
  section) — a retried clone/fetch must not leave the shadow worktree in a half-synced state
  that a subsequent freshness check silently accepts as "fresh."
  `AndroidGitRepositoryStorageGuardTest`-style regression coverage should extend to the
  retry/resume paths.
  addresses the "Software caused connection abort" screenshot, and (2) applying it to the
  same JGit call shape on Desktop — before generalizing to fetch/push retry, to keep the
  highest-value fix first even within a Large-appetite project.
- **Distinguishing transient vs. permanent transport failures.** Auth failures
  (`AuthFailed`), 404/repo-not-found, and genuinely offline (no network at all) must NOT be
  retried the same way as a mid-transfer `SocketException` — retrying a bad-credentials error
  4 times with backoff is pure wasted latency and a worse UX than failing fast. The plan phase
  needs a concrete taxonomy of which JGit/transport exceptions are retryable.

## Alternatives Considered
- **Shallow clone only, no resume/retry/foreground-service work** (the smaller "Hardening
  only" bundle) — rejected by the user in favor of full resilience; captured here since the
  research/plan phases should still evaluate whether depth-limiting alone closes most of the
  real-world failure window before committing to the more expensive resume mechanism.
- **Third-party resumable-download library** — not applicable; this is a git object-transfer
  problem (delta/pack-aware), not a generic file download, so a generic resumable-download
  library doesn't solve the actual transfer format.
- **Switching off JGit to a library with native resume support** — out of scope for this
  project's appetite; JGit is deeply integrated (shadow worktree, merge/conflict handling,
  auth) and a transport swap is its own migration-sized effort.

## Feasibility Risks
- JGit's lack of native resumability (see Rabbit Holes) may force a smaller "resume" contract
  than the success metric implies (e.g. resume at the ref-negotiation level, not mid-pack byte
  level) — research phase must confirm what's actually achievable and requirements' success
  metric may need to be revisited after research, not treated as fixed.
- Real device/emulator testing is required to validate the Android foreground-service and
  Doze-kill behavior — this session has no attached Android device (`adb devices` returned
  empty); implementation/verify phases must flag this as a manual-testing dependency rather
  than assuming CI/unit tests alone prove the fix.
- No existing regression test exercises a mid-transfer network interruption on either
  platform — new test infrastructure (fault injection into the JGit transport) is itself
  nontrivial work within the Large appetite.

## Observability Requirements
Standard structured logging via the existing `Logger` (`AndroidGitRepository`/
`JvmGitRepository` already instantiate `Logger("...")`) is sufficient — this is a client app
with no oncall/alerting infrastructure. Each retry attempt and resume checkpoint should be
logged with attempt number, failure reason, and outcome, so a user-reported sync failure can
be diagnosed from local logs without new telemetry infrastructure.

## Risk Control
No feature-flag system exists in this codebase (confirmed by grep) and this project will not
introduce one. Risk control is the standard release-please pipeline: land behind normal PR
review, ship in a regular patch/minor release, and rely on `git revert` + an expedited patch
release (per `CLAUDE.md`'s Release Process section) as the rollback procedure if a regression
surfaces. Given git sync is a core, frequently-exercised path, prioritize thorough
regression coverage (see Success Metrics) as the primary risk mitigation over staged rollout,
which this app has no mechanism for.

## Open Questions
- Does "resume" ultimately mean git-protocol-level re-negotiation or true byte-level partial-pack
  resumption? (See Rabbit Holes — resolve in research/plan, not here.)
- What's the concrete taxonomy of retryable vs. non-retryable JGit/transport exceptions?
  (See Rabbit Holes — resolve in plan phase.)
- Which Android foreground-service type is correct for a potentially multi-minute git
  transfer, and what are the Android 14+ manifest/runtime-permission requirements? (Research
  phase.)
