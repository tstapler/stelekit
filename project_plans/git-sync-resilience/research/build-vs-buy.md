# Research: Build vs. Buy — git-sync-resilience

Agent 6. Evaluates whether each piece of this project should be built from scratch or
sourced from an existing library/pattern.

## 1. Retry/backoff mechanism

**Verdict: use what's already on the classpath — `arrow-resilience`'s `Schedule`. No new
dependency, no hand-rolled loop.**

This repo already depends on `arrow-resilience:2.2.1.1`
([kmp/build.gradle.kts:91](https://github.com/tstapler/stelekit/blob/5e0b634dbeb867a729a8fd7be6acf575fdf1d131/kmp/build.gradle.kts#L91))
and already has an established retry pattern built on it:
[`kmp/src/commonMain/kotlin/dev/stapler/stelekit/resilience/RetryPolicies.kt`](https://github.com/tstapler/stelekit/blob/7cc02be951cd6423eb40721838a709af5d12f2a3/kmp/src/commonMain/kotlin/dev/stapler/stelekit/resilience/RetryPolicies.kt) —
a `RetryPolicies` object of named `Schedule<Throwable, T>` constants, e.g.:

```kotlin
val fileWatchReregistration: Schedule<Throwable, kotlin.time.Duration> =
    Schedule.exponential<Throwable>(100.milliseconds)
        .doUntil { _, duration -> duration > 5.seconds }
```

`Schedule` already provides `.exponential()`, `.jittered()`, `.recurs()`, `.doUntil()`, and
composable combinators — exactly the exponential-backoff-with-jitter primitive requirement.md
asks about, confirmed via [Arrow's own retry/repeat docs](https://arrow-kt.io/learn/resilience/retry-and-repeat/)
and the [`arrow.resilience.retry` API reference](https://apidocs.arrow-kt.io/arrow-resilience/arrow.resilience/retry.html).
`GitSyncService` also already has a hand-written (non-`Schedule`) retry path for rate-limit
responses — [`GitSyncService.scheduleRateLimitRetry`](https://github.com/tstapler/stelekit/blob/6a3bbbf0d19f0307b10f68ee69a9bb4b39c8eb8c/kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitSyncServiceRateLimitRetryTest.kt) —
which is a second, independent precedent this project's retry work should probably converge
with rather than add a third pattern.

Confirmed directly rather than assumed: `kotlinx-coroutines-core` (also on the classpath,
[kmp/build.gradle.kts:94](https://github.com/tstapler/stelekit/blob/5e0b634dbeb867a729a8fd7be6acf575fdf1d131/kmp/build.gradle.kts#L94))
has no built-in retry combinator of its own — no `retry {}` — it only provides the primitives
(`delay`, structured concurrency) that any retry loop, including Arrow's, is built on.

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **`arrow-resilience` `Schedule`** (already on classpath, already has a codebase pattern) | Zero new dependency; matches existing `RetryPolicies.kt` convention; composable (`.jittered()`, `.and()`, `.doUntil()`); reviewers already know it | None specific to this use — the only "cost" is writing the git-transport-specific schedule and retry-predicate | **Recommended** |
| Hand-rolled ~30-line backoff helper | No abstraction to learn | Reinvents what `Schedule` already does; diverges from the existing `RetryPolicies.kt` convention for no reason | Not recommended |
| `kotlin-retry` (michaelbull) or similar third-party lib | N/A | New dependency for something already covered; appears unmaintained since ~2021 per search results — not independently verified beyond search summary, flagged as such | Not recommended |

## 2. Resumable git transfer

**Verdict: requirements.md's constraint is correct and should not be revisited — there is no
existing library or JGit extension that solves resumable pack transfer at the byte/protocol
level. "Resume" in this project should mean checkpoint-and-restart of the git *operation*
(depth-limited retry), not byte-level resume of a partial pack.**

- **JGit itself**: no resumable-clone/fetch API exists in 7.3.0 or any version searched.
  `TransportHttp` exposes `setTimeout()` (added specifically to abort hung transfers — see the
  [`TransportHttp: abort on time-out or on SocketException`](https://git.eclipse.org/c/jgit/jgit.git/commit/?id=bdb7357228c6611cea2d266255c7751bd9ed368e)
  commit) but nothing checkpoint/resume-related.
- **Native git itself has no resumable clone either.** Two claims surfaced by an initial
  search-result summary — a `git clone --continue` flag and a `git fetch --resume-pack`
  flag — were **verified false** against git-scm.com's own `git-clone` and `git-fetch`
  documentation (no such flags exist in either). Flagging this explicitly because it's exactly
  the kind of plausible-sounding hallucination this research had to catch rather than relay.
- **The closest real attempt** is a 2016 git-mailing-list proof-of-concept,
  ["Resumable clone revisited"](https://ratatoskr.run/git/2016/06/7671772/t) — the client
  hashes what it already has, the server verifies the hash and resumes byte transfer from
  there. The author's own patch 8/8 commit message says it "cannot be merged in its current
  form"; it never landed in mainline git. It required **disabling parallel delta search
  server-side** to make pack output deterministic/resumable, which this project cannot
  require of arbitrary GitHub/GitLab/self-hosted remotes it talks to. It also explicitly
  flagged corruption risk from "frankenstein packs" assembled across multiple
  `pack-objects` runs. This is strong evidence that byte-level resume is not a viable target
  even ten years later — it needs server-side cooperation this app has no control over.
- **`johnzeng/ResumableGitClone`** ([repo](https://github.com/johnzeng/ResumableGitClone)) is
  the one concrete "resumable clone" tool found. It is a bash wrapper around the `git` CLI
  (not a JVM/Kotlin library, not adaptable code — but its *strategy* is worth borrowing): it
  clones incrementally by depth (`--depth 1`, then deepening), tracking progress in a
  `.resumable_git_depth` file, and resumes by re-running with the last successful depth. This
  validates the practical approach requirements.md already leans toward — depth-limited
  shallow clone plus retry — as a substitute for true byte resume, not just a separate scope
  item.
- **No alternative JVM git library solves this either.** No JGit fork or JGit-adjacent JVM
  library with resumable pack transfer turned up in search. (Not independently exhaustive —
  see gap note below.)

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| True byte-level resume (protocol-level, JGit-custom) | Would give the "avoid re-transferring already-fetched bytes" SLO literally | No prior art landed anywhere, even in mainline git after a decade; requires server cooperation outside this app's control; real corruption risk (frankenstein packs) per the 2016 POC's own author | **Not recommended** — do not build this |
| Checkpoint-by-depth: shallow clone (depth=1) first, retry with backoff, then progressively `unshallow`/deepen on success, each depth step being a complete, fsck-valid pack | Achieves the *practical* goal (don't restart from zero on a large repo) without server cooperation or byte-level state; each checkpoint is a fully valid, JGit-integrity-checked repo state (see §4); matches `ResumableGitClone`'s field-tested strategy | Not literal byte resume — an interruption mid-depth-step still re-fetches that step's data | **Recommended** — this is what requirements.md's "resume from checkpoint" should concretely mean |
| Adopt a different transport/library with native resume | Out of appetite per requirements.md | — | Ruled out already; research confirms no such library exists in the JGit-compatible JVM ecosystem anyway, so the alternative wouldn't even be available |

**Gap**: this search was not exhaustive of every JGit fork on Maven Central/GitHub; if the
plan phase wants stronger confidence, a targeted search of `jgit-dev` mailing list archives
and Eclipse Gerrit for "resumable" or "checkpoint" tickets would close the gap. Everything
found here points the same direction, though.

## 3. Android foreground service scaffolding

**Verdict: reuse `CoroutineWorker` + `setForeground()` on the existing WorkManager
infrastructure, matching this repo's existing `WorkManagerSyncScheduler.kt` pattern — not a
hand-rolled `Service`, and not User-Initiated Data Transfer (UIDT) as the primary mechanism
given this project's `minSdk`.**

This repo already has two different existing patterns to choose between:

1. **`CoroutineWorker`**, already used for periodic git fetch —
   [`WorkManagerSyncScheduler.kt`](https://github.com/tstapler/stelekit/blob/13519d145f51e1fca7469796467a18421200ff6b/kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt) —
   though that existing worker is *not* currently foregrounded (it's plain periodic
   background work, not a long, user-visible transfer).
2. **Hand-rolled `Service`**, used for BLE measurement —
   [`AndroidMeasurementForegroundService.kt`](https://github.com/tstapler/stelekit/blob/64dca6da44ccf660415fbe403f60ef17427c353f/kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/measurement/ble/AndroidMeasurementForegroundService.kt),
   with `foregroundServiceType="connectedDevice"` declared in the manifest — a genuinely
   different domain (a long-lived Bluetooth *connection*, not a bounded data transfer).

For a bounded, user-initiated, one-shot data transfer (initial clone during the setup
wizard), the fit is `CoroutineWorker.setForeground()`:
- Android's own guidance: implement `getForegroundInfo()` and pass it to `setForeground()`
  within `doWork()`, calling it before the long-running task kicks off — per
  [Android Developers — long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running).
- This is strictly additive to `WorkManagerSyncScheduler`'s existing infrastructure — same
  `WorkManager` instance, same constraints/retry machinery, one new one-time
  `OneTimeWorkRequest` that calls `setForeground()` instead of `WorkManagerSyncScheduler`'s
  existing bare periodic request.
- A hand-rolled `Service` (the BLE pattern) would duplicate WorkManager's retry/constraint/
  backoff machinery this project also needs for question 1, for no added capability — the
  only reason `AndroidMeasurementForegroundService` is a bare `Service` is that BLE
  connection lifecycle doesn't map onto WorkManager's finite-work model at all; a git
  clone/fetch does.

**One real caveat surfaced by research, worth flagging to the plan phase rather than silently
adopting**: Android 14+ introduced **User-Initiated Data Transfer (UIDT) jobs**, and Google's
own background-work guidance now says explicitly: *"If your use case is for transferring data
over a network ... in response to an explicit user request, we recommend using the
user-initiated data transfer job instead of the `dataSync` foreground service type"*
([Android Developers — user-initiated data transfer](https://developer.android.com/develop/background-work/background-tasks/uidt)).
Google Maps saw a 10% download-reliability improvement switching to UIDT
([Android Developers Blog, Sept 2024](https://android-developers.googleblog.com/2024/09/google-maps-improved-download-reliability-user-initiated-data-transfer-api.html)).
This is exactly this project's use case (explicit user-initiated wizard-step data transfer).
However: this repo's `minSdk = 26`
([kmp/build.gradle.kts:1449](https://github.com/tstapler/stelekit/blob/5e0b634dbeb867a729a8fd7be6acf575fdf1d131/kmp/build.gradle.kts#L1449))
is far below UIDT's Android 14 (API 34) floor, so UIDT could only ever be an API-gated
enhancement layered on top of the `CoroutineWorker`/`setForeground()` baseline, never a
replacement for it — building UIDT support now would be scope creep against a "Large, not
XL" appetite unless the plan phase decides the API-34+ reliability win is worth the added
branch. Recommend the plan phase note it as a fast-follow, not build it in v1.

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| `CoroutineWorker` + `setForeground()` on existing `WorkManagerSyncScheduler` infra | Reuses existing dependency, existing per-graph work-naming convention, existing retry/constraint machinery; matches Google's own guidance for `CoroutineWorker` foreground work | `dataSync` foreground service type still needs the Android 14 `FOREGROUND_SERVICE_DATA_SYNC` permission + manifest declaration (net-new, but small — same shape as the existing BLE `connectedDevice` declaration) | **Recommended** |
| Hand-rolled `Service` (mirror `AndroidMeasurementForegroundService`) | Existing precedent in-repo | Duplicates retry/backoff/constraint machinery WorkManager already gives for free; BLE's reason for being a bare `Service` (indefinite connection lifecycle) doesn't apply to a bounded transfer | Viable but not recommended — strictly more code for less capability here |
| User-Initiated Data Transfer (UIDT) job | Google's explicitly recommended mechanism for this exact use case; measured reliability win at Google Maps | Requires API 34+; this app's `minSdk` is 26, so it can only be an additive, API-gated path, not the baseline | Viable as a fast-follow, not for v1 |

## 4. LLM-generated vs. battle-tested code for resume/checkpoint logic

**Verdict: build the checkpoint/retry-taxonomy orchestration (where a wrong choice degrades
UX, not data), but do not reimplement pack/object integrity verification — JGit already does
this by construction and this project should lean on it rather than add a parallel checksum
layer.**

Two different things are being asked for under "resume/checkpoint logic," and they carry very
different risk profiles:

- **Retry-taxonomy classification** (which exceptions are retryable vs. terminal —
  `SocketException`/timeout vs. bad credentials vs. 4xx auth failure) and **checkpoint
  orchestration** (which depth step to retry at, backoff scheduling) are ordinary
  control-flow/business logic. A bad classification here produces a bad *user experience*
  (retrying something unretryable, or giving up on something transient) — annoying, not
  data-destructive. Normal LLM-assisted implementation + normal test coverage is proportionate
  here.
- **Object/pack integrity** is the part where a bug silently corrupts a user's wiki graph.
  Here, JGit already does the safety-critical work and should not be reimplemented:
  - `PackParser` computes and verifies the pack's trailing SHA-1 checksum as it streams —
    per its own design (confirmed via the [`PackParser` API](https://archive.eclipse.org/jgit/site/5.4.2.201908231537-r/apidocs/org/eclipse/jgit/transport/PackParser.html)
    and JGit's own pack-safety hardening work,
    [`index-pack: Avoid disk...`](https://ratatoskr.run/git/2016/06/7368263/t)), a partial or
    corrupted pack fails this checksum and the operation aborts *before* anything is
    committed to the object database. This is exactly the guarantee this project needs: a
    resumed/retried transfer that produces a bad pack is rejected by JGit itself, not by
    custom code this project would have to get right.
  - `org.eclipse.jgit.lib.ObjectChecker` additionally validates that received objects
    (commit/tree/tag/blob) are structurally well-formed
    ([JGit `ObjectChecker` source](https://git.eclipse.org/c/jgit/jgit.git/tree/org.eclipse.jgit/src/org/eclipse/jgit/lib/ObjectChecker.java)) —
    JGit's receive path can be configured to run this (`ReceivePack.setCheckReceivedObjects` /
    equivalent on the transport side).
  - Practical implication for the checkpoint design in §2: because each depth-step of a
    shallow-then-deepen clone is itself a complete pack transfer that JGit will refuse to
    finalize if corrupt, the "resume" logic never needs to reconstruct or validate a
    *partial* pack itself — it only needs to decide "did the last full step succeed, if not
    retry that step." That is a meaningfully smaller, lower-risk problem than byte-level
    pack-splicing would have been, which is a further argument for the checkpoint-by-depth
    approach in §2 over any custom partial-pack-splicing logic.

| Component | Risk if wrong | Build approach |
|---|---|---|
| Retry-taxonomy classification (exception → retryable?) | UX degradation only | LLM-assisted, standard test coverage, extra scrutiny on the classification table itself (this is the one place a wrong call has any real cost) |
| Checkpoint/depth-step orchestration | Wasted retries, not corruption (JGit rejects bad output) | LLM-assisted + tests; keep it dumb (which depth step to resume at), not clever |
| Pack/object integrity verification | Silent wiki-graph corruption | **Do not build — lean on JGit's built-in `PackParser` checksum verification and `ObjectChecker`.** Add a regression test asserting a truncated/corrupted transfer is rejected rather than partially committed, but don't write a parallel verifier |

## 5. Fork or adapt

**Verdict: nothing found is close enough to fork or adapt as code.** The two nearest
candidates:
- `johnzeng/ResumableGitClone` — bash CLI wrapper, not JVM code; its *strategy*
  (depth-based incremental resume) is worth adopting conceptually (folded into §2's
  recommendation) but there is no code to port.
- The 2016 git mailing-list "resumable clone" patch series — never merged, requires
  server-side cooperation this project doesn't have; not adoptable at all.

No prior art was found in this repo's git history or other `project_plans/` entries for git
transfer retry/resume specifically (`RetryPolicies.kt` and `GitSyncServiceRateLimitRetryTest`
are the closest in-repo precedent — already covered in §1 — but they address rate-limiting,
not transport-interruption resume). No other OSS Android git client's resume implementation
was located in this pass; if the plan phase wants to check further, worth a targeted look at
MGit's or Pocket Git's source for their retry handling, but nothing surfaced unprompted.

## Summary

1. **Retry/backoff**: use `arrow-resilience`'s `Schedule`, already on the classpath with an
   established in-repo pattern (`RetryPolicies.kt`) — no new dependency.
2. **Resumable transfer**: no library solves byte-level resume (confirmed via JGit, native
   git, and a 2016 proof-of-concept that never merged); "resume" should mean depth-checkpoint
   retry (shallow clone, then deepen), not byte-level pack splicing.
3. **Foreground execution**: reuse `CoroutineWorker.setForeground()` on the existing
   `WorkManagerSyncScheduler` infrastructure, not a hand-rolled `Service`; flag UIDT as an
   API-34+ fast-follow given `minSdk = 26`.
4. **Build vs. trust**: build the retry-taxonomy/checkpoint orchestration with normal
   scrutiny; do not reimplement pack integrity checking — JGit's `PackParser` checksum
   verification and `ObjectChecker` already provide the corruption-safety guarantee needed.
5. **Fork/adapt**: nothing forkable found; `ResumableGitClone`'s depth-based strategy is
   worth adopting conceptually, not as code.
