# ADR-001: Reuse JGit's LFS filters and pointer model; own the network client

**Status**: Proposed (gated by spikes S1, S2 and S5 in Story E2.1; flips to Accepted when Task E2.1f records all three as GO)
**Date**: 2026-10-11
**Deciders**: Repo owner (Tyler Stapler)
**Related**: ADR-004 (client in commonMain), ADR-003 (guard), `../research/stack.md` sections 1, 2, 4, `../research/pitfalls.md` R1, R2, R20

## Context
JVM and Android sync with plain JGit 7.3.0 (`kmp/build.gradle.kts:146,175,342`; `MODULE.bazel:311`), with no `org.eclipse.jgit.lfs`. Clone and merge leave ~130-byte pointer files, and `git add` without the clean filter commits raw binaries into repos whose `.gitattributes` says `filter=lfs`.

Verified facts that decide the shape (`../research/stack.md` section 1, VERIFIED against the 7.3.0 lfs sources):
- `org.eclipse.jgit:org.eclipse.jgit.lfs:7.3.0.202506031305-r` exists (81,728 B, one extra dependency, gson, already in the Bazel lock).
- Its HTTPS path never sends `Authorization` (no `CredentialsProvider`, no netrc). Our PATs and OAuth tokens live in the vault and reach JGit only through `UsernamePasswordCredentialsProvider` (`GitOperationSupport.kt:409-420`, `:501-526`), so stock `BuiltinLFS` fails with 401 on private repos and on every upload to github.com or gitlab.com.
- `SmudgeFilter` short-circuits when `.git/lfs/objects/aa/bb/<oid>` already exists, and `Hooks.prePush` returns a no-op hook unless `filter.lfs.useJGitBuiltin=true`. Both are seams we can use without patching JGit.
- No timeouts, no progress, no resume, no per-object retry, non-atomic download (a truncated file at the final path later passes the `exists` check).

## Options
| Option | Summary | Verdict |
|---|---|---|
| A. Stock JGit LFS (`BuiltinLFS`) | Register and enable; let JGit do everything | Rejected: no https auth (R1, score 25), no timeouts or resume, partial-file hazard |
| B. Hybrid | JGit clean/smudge filters, `LfsPointer`, `.git/lfs` layout; our own commonMain Ktor batch client for download and upload | **Chosen** |
| C. Pure custom | Own clean/smudge `FilterCommand`s registered in `FilterCommandRegistry` plus own store | Fallback only (see go/no-go); duplicates tested code if B works |
| D. Shell out to `git lfs` | ProcessBuilder as in ADR-012 | Rejected as foundation: not on Android/iOS/web, needs credential injection; may be a later Desktop accelerator |

## Decision
Option B.
1. Add `org.eclipse.jgit.lfs` at exactly the core JGit version (Gradle `jvmMain` and `androidMain`; `MODULE.bazel` next to `:311`, then `bazel run @maven//:pin`). Pin must move with core.
2. Call `BuiltinLFS.register()` once (idempotent) so the `jgit://builtin/lfs/{clean,smudge}` filter names exist. Per repo, write `filter.lfs.clean` and `filter.lfs.smudge` to those names, `filter.lfs.required=false`, and an explicit `lfs.url` (remote name is configurable in `GitConfig.remoteName`, JGit only discovers for `origin`). Leave `filter.lfs.useJGitBuiltin` unset so JGit's own pre-push hook and merge-time smudge stay disabled.
3. Network is ours: before checkout, merge, reset or single-file checkout we download needed objects into `<gitdir>/lfs/objects/` (smudge is then purely local); before `git.push()` we upload every object referenced by the pointers in the commits to be pushed. If any upload fails, the push is not attempted (no pointer commit can reach the remote without its object).
4. The "is this a pointer" decision used by the guard is our own spec-strict commonMain parser, never JGit's lenient one (JGit skips comment and blank lines and accepts CRLF; `../research/pitfalls.md` section 1.0).
5. Android is declared supported only after the binary-safe shadow path exists (Story E2.5) and the Android device pass is recorded; until then Android runs guard-only plus placeholders behind `androidLfsEnabled` (default false).
6. iOS: `IosGitRepository` is a stub (`kmp/src/iosMain/kotlin/dev/stapler/stelekit/git/IosGitRepository.kt`, every method returns `NotSupported("iOS")`). Stance: guard plus read-only placeholders; native LFS is deferred until iOS git itself ships (owner decision O4 confirms).

### 3a. Deferred objects, pointers in the working tree, and global git config (review blockers B3, C5)
JGit's `SmudgeFilter` downloads unauthenticated when an object is missing, which would bypass `LfsDownloadPolicy` (caps, metered, first-sync review) and fail 401 on private repos; and with the pointer text left in the working tree the clean filter must not hash it again. Spike S5 decides. If it is NO-GO, the baseline is our own `jgit://stelekit/lfs/{clean,smudge}` filters (no network, pointer-in/pointer-out, leave pointer on smudge when absent). In `GUARD_ONLY` and `OFF` modes the repo-local config points at a passthrough filter so a user's global external `git-lfs` config never runs; `BuiltinLFS.register()` is process-global but inert without per-repo config.

## Go/no-go for the spikes (Story E2.1)
| Spike | GO when | NO-GO fallback |
|---|---|---|
| S1 filter activation | With `useJGitBuiltin` unset and `filter.lfs.*` pointing at `jgit://builtin/lfs/*`: `git add` puts a pointer in the index and the object in `.git/lfs`; `checkout` restores content from `.git/lfs` with zero requests seen by `FakeLfsServer`; `status` is clean | Register our own `FilterCommand`s under `jgit://stelekit/lfs/{clean,smudge}` using the commonMain `LfsPointer` and `JvmLfsObjectStore` (option C-lite); everything else in this ADR is unchanged |
| S2 clone sequencing | `setNoCheckout(true)` at `setDepth(DEFAULT_CLONE_DEPTH)` completes; pointers in the HEAD tree are discoverable by `TreeWalk`; after pre-seeding the store, `checkout` smudges locally | Replace `cloneRepository` with `init` + `remoteAdd` + `fetch(depth)` + prefetch + `checkout`; same hook points, more code in `JvmGitRepository.clone` |
| S3 SSH `git-lfs-authenticate` | Auth href and headers obtained through the SSH session our transport already holds (MINA on JVM, JSch on Android) | SSH-origin repos use the HTTPS LFS endpoint derived from the remote URL when an https token exists; otherwise `LfsNotSupportedHere("ssh")` and guard-only. SSH LFS stays out of v1 |
| S5 deferred objects and hooks | Checkout with a missing object makes zero requests to the server (or a refusal we intercept) and leaves the pointer text; `status` with a pointer in the working tree is clean and `add` does not make a pointer to a pointer; a `git lfs` `pre-push` hook script does not break a push through our upload | Our own `jgit://stelekit/lfs/*` filters are the baseline (see 3a); hook coexistence NO-GO means SteleKit pushes only when no `git lfs` hook script is present, otherwise `LfsNotSupportedHere("hook")` |
| S4 Android shadow bytes | A Robolectric test proves the current String path corrupts non-UTF-8 bytes (negative), then a byte path round-trips them | Android stays guard-only (`androidLfsEnabled=false`) |

## Consequences
- Positive: tested clean/smudge/pointer code, one auth path (the vault token), shared client with web, atomic verified downloads, retry and progress.
- Negative: we maintain a Ktor batch client (ADR-004) and a ~40-line reimplementation of `LfsPrePushHook.findObjectsToPush`; Bazel lock re-pin needs the 16 GB machine (`MODULE.bazel:149-158`).
- The stale comment at `MODULE.bazel:139` says "Android uses 5.13.x"; `kmp/build.gradle.kts:342` pins 7.3.0 for Android. Task E2.8a corrects the comment while touching the file.

### Expected spike outcome and status rule
S5 is expected to be NO-GO with the stock smudge filter (it downloads unauthenticated, `../research/stack.md`), making our own `jgit://stelekit/lfs/{clean,smudge,passthrough}` filters the baseline; S1 therefore also runs against those names. ADR-001 becomes Accepted when either filter family passes S1, S2 and the S5 safety criteria; item 2 above is then rewritten to name the filters actually chosen. If neither passes, ADR-001 stays Proposed and Phase 2 stops. `git lfs` hook scripts are never edited by SteleKit; a recorded hook conflict yields `LfsNotSupportedHere("hook")`.

## Spike results
Appended by Task E2.1f (S1..S5 outcome, date, evidence command output).
