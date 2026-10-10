# Research: Stack — git-sync-resilience

All JGit API claims below are VERIFIED against the actual pinned jar via `javap`/`unzip`, not
from memory — see commands inline. Codebase claims are VERIFIED via `Read`/`Grep` against this
worktree at commit `0a3cffd` (current HEAD).

## 1. Pinned JGit version

`kmp/build.gradle.kts:145,174-175,341-349` — `7.3.0.202506031305-r` on **both** jvmMain and
androidMain (kept identical deliberately, per the androidMain comment "matches Bazel-resolved
version"). Modules:
- `org.eclipse.jgit:org.eclipse.jgit:7.3.0...` (core, both platforms)
- `org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.3.0...` (Desktop SSH — Apache MINA sshd)
- `org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:7.3.0...` (Android SSH, jsch excluded) +
  `com.github.mwiede:jsch:0.2.21` (fork with ED25519/ECDSA/OpenSSH key support)

7.3.0 was released 2025-06-03. I did not check whether a newer 7.x point release (7.4.x/7.5.x)
changes any API discussed below — no network fetch was done for this; if it matters, diffing the
jgit release notes for `CloneCommand`/`FetchCommand`/`TransportHttp` between 7.3.0 and the latest
7.x tag is a fast follow-up (not done here — UNVERIFIED whether anything changed).

## 2. Timeout APIs (VERIFIED via javap against the pinned jar)

```
$ javap -classpath org.eclipse.jgit-7.3.0.*.jar -public org.eclipse.jgit.api.TransportCommand
public abstract class org.eclipse.jgit.api.TransportCommand<C, T> extends GitCommand<T> {
  public C setCredentialsProvider(CredentialsProvider);
  public C setTimeout(int);                          // seconds
  public C setTransportConfigCallback(TransportConfigCallback);
}
```

`CloneCommand`, `FetchCommand`, and `PushCommand` all extend `TransportCommand`, so
**`.setTimeout(int seconds)` is available on all three** (inherited — not redeclared, hence not
visible in each subclass's own `javap` output, but present via the type hierarchy). This sets the
connect+read timeout JGit hands to the transport layer per-connection.

**This repo already uses it, but only on `testRemote`**: `GitOperationSupport.kt:149-165`
(`testRemoteViaLsRemote`) calls `.setTimeout(TEST_REMOTE_TIMEOUT_SECONDS)` (= 15s) on
`Git.lsRemoteRepository()`. **`clone()` and `fetch()` in both `AndroidGitRepository.kt` (lines
82-118) and `JvmGitRepository.kt` (lines 73-118) never call `.setTimeout()` at all** — confirmed
by `grep -n "setTimeout"` returning zero hits in either file. This is the direct root cause of
the reported bug shape (a blocking read with no configured timeout hangs until the OS tears the
socket down, at which point JGit surfaces the JDK's generic `SocketException` message verbatim).

For HTTP specifically, a finer-grained knob exists one layer down:
```
$ javap -classpath ... -public org.eclipse.jgit.transport.http.HttpConnection
  public abstract void setConnectTimeout(int);
  public abstract void setReadTimeout(int);
```
`HttpConnection` is JGit's pluggable HTTP client abstraction (default impl wraps
`java.net.HttpURLConnection`); `TransportCommand.setTimeout()` is simpler and sufficient unless
distinct connect-vs-read values are wanted, which would require a custom
`HttpConnectionFactory`/`TransportConfigCallback`. Recommend: **use `setTimeout()`** on
clone/fetch/push — matches the existing `testRemote` idiom, no new plumbing needed.

SSH timeout: `SshTransport.setSshSessionFactory(SshSessionFactory)` — the timeout int passed into
`TransportCommand.setTimeout()` flows through to
`JschConfigSessionFactory.getSession(URIish, CredentialsProvider, FS, int tms)` (VERIFIED
signature via javap on `org.eclipse.jgit.ssh.jsch`'s `JschConfigSessionFactory` — package is
`org.eclipse.jgit.transport.ssh.jsch` in 7.x, not the pre-6.x `org.eclipse.jgit.transport.JschConfigSessionFactory`).
So **the same `.setTimeout()` call on the command covers SSH too** — no separate JSch
`Session`-level config needed unless finer control (e.g. distinct handshake vs. data timeout) is
required later.

## 3. Retry/backoff: no built-in in JGit — but a usable idiom already exists in this codebase

JGit has **no retry mechanism anywhere in `CloneCommand`/`FetchCommand`/`PushCommand`** — a single
failed `.call()` throws, full stop. Any retry must wrap the command externally.

This repo already has two relevant precedents — use the first, not the second, as the model:

1. **`kmp/src/commonMain/kotlin/dev/stapler/stelekit/resilience/RetryPolicies.kt`** — named
   `arrow.resilience.Schedule` constants (arrow-resilience `2.2.1.1`, already on the classpath per
   `kmp/build.gradle.kts:91`). E.g. `sqliteBusy = Schedule.recurs<Throwable>(3).jittered()`,
   `fileWatchReregistration = Schedule.exponential<Throwable>(100.milliseconds).doUntil { _, d -> d > 5.seconds }`,
   plus a `testImmediate` zero-delay variant for tests. This is the established
   "named-Schedule-constant + suspend-fun-wraps-it" idiom for retry/backoff in this codebase —
   **the natural place to add e.g. `RetryPolicies.gitTransportTransient`** (exponential backoff,
   capped attempts, jittered) and drive it with `Schedule.retry { ... }` around the
   `Git.cloneRepository().call()` / `.fetch().call()` / `.push().call()` calls, gated on the
   transient-exception taxonomy from §4 below (must NOT blindly retry `AuthFailed`/404s).
2. **`GitSyncService.scheduleRateLimitRetry`** (`GitSyncService.kt:153`, exercised by
   `GitSyncServiceRateLimitRetryTest.kt`) — a **hand-rolled** one-shot `scope.launch { delay(...);
   retry() }` on the service's own long-lived `scope = CoroutineScope(SupervisorJob() +
   PlatformDispatcher.IO + exceptionHandler)` (`GitSyncService.kt:123`), used specifically for the
   HTTP 429 `RateLimited` case (single scheduled retry after `retryAfterSeconds`, cancelled on
   `shutdown()` or a superseding manual call — see the three regression tests in
   `GitSyncServiceRateLimitRetryTest.kt`). This is *not* built on `arrow.resilience.Schedule` — it
   predates/parallels it, presumably because `retryAfterSeconds` is a server-dictated single delay
   rather than a backoff curve. Worth noting for consistency, but the Schedule-based
   `RetryPolicies` object is the better base for the new N-attempt exponential-backoff loop this
   feature needs (transient network failures, not a server-told delay).

Neither existing mechanism currently wraps `clone()`/`fetch()`/`push()` — confirmed by `grep -rn
"RetryPolicies\." kmp/src/*/kotlin/dev/stapler/stelekit/git/` returning no hits outside
`resilience/RetryPolicies.kt` itself. This is greenfield work, not a bug in an existing wrapper.

**Important interaction to design around**: `AndroidGitRepository.openGit()`'s `ensureFresh()`
precondition (shadow-worktree freshness check, called on every open) must not be re-run or
invalidated by a naive outer retry loop that reopens the repo per attempt — the retry should wrap
just the JGit `.call()`, inside the already-open `Git` handle, not the whole
open→operate→close span, or `ensureFresh()`'s cost/semantics multiply per retry. (Flagged as a
Rabbit Hole in requirements.md; confirmed by reading `AndroidGitRepository.kt:87-118`, where
`clone()` doesn't call `openGit()` at all — this interaction is specific to `fetch()`/`push()`/
`status()`, which do.)

## 4. Exception taxonomy (VERIFIED via javap on the pinned jar's class hierarchy)

```
org.eclipse.jgit.api.errors.TransportException  extends GitAPIException      (public API surface,
                                                                                what CloneCommand/
                                                                                FetchCommand/
                                                                                PushCommand.call()
                                                                                actually throws)
  wraps →
org.eclipse.jgit.errors.TransportException       extends java.io.IOException (internal transport
                                                                                layer; becomes the
                                                                                .cause of the above)
  ├── org.eclipse.jgit.errors.NoRemoteRepositoryException  (404 / repo doesn't exist — PERMANENT)
  └── (SocketException/SocketTimeoutException/UnknownHostException surface as the .cause chain
      of the internal TransportException, not as JGit types themselves — they're raw
      java.net/java.io exceptions from the underlying HttpURLConnection or Socket)

org.eclipse.jgit.api.errors.InvalidRemoteException  extends GitAPIException  (malformed URI —
                                                                                PERMANENT, checked
                                                                                before any I/O)
org.eclipse.jgit.api.errors.CanceledException       extends GitAPIException  (ProgressMonitor.
                                                                                isCancelled()==true
                                                                                — NOT a failure,
                                                                                must not retry)
org.eclipse.jgit.api.errors.RefNotAdvertisedException extends GitAPIException (branch/ref doesn't
                                                                                exist on remote —
                                                                                PERMANENT)
org.eclipse.jgit.errors.LockFailedException          extends java.io.IOException (local .git lock
                                                                                contention — worth
                                                                                a short retry, NOT
                                                                                a network issue)
```

**Concrete transient-vs-permanent split for this feature**, built from the above plus what the
JDK actually throws for a torn-down socket (the reported bug: "Software caused connection abort"
is `java.net.SocketException`'s canned message on Linux/Android for `ECONNABORTED`):

| Exception (as caught, after JGit's public API wraps it) | Transient? | Action |
|---|---|---|
| `org.eclipse.jgit.api.errors.TransportException` whose `.cause` (recursively) is `java.net.SocketException`, `java.net.SocketTimeoutException`, `java.net.UnknownHostException`, or `java.io.EOFException` | **Yes** | retry with backoff |
| Same `TransportException`, but `.cause` chain bottoms out in `org.eclipse.jgit.errors.NoRemoteRepositoryException` | No (404/repo gone) | fail immediately |
| Same `TransportException`, `.message` contains HTTP `401`/`403`, or the auth-specific path (JGit auth failures generally surface here too, alongside credential errors) | No (auth) | fail immediately → `DomainError.GitError.AuthFailed` (existing routing, see below) |
| `org.eclipse.jgit.api.errors.InvalidRemoteException` | No (malformed input) | fail immediately |
| `org.eclipse.jgit.api.errors.CanceledException` | N/A — user/job cancellation, not a failure | propagate as cancellation, never retry |
| `org.eclipse.jgit.errors.LockFailedException` | Yes, but local not network — short/no backoff | short retry or fail with a distinct message |
| `org.eclipse.jgit.api.errors.RefNotAdvertisedException` | No | fail immediately |

**This repo's current mapping is coarser than that split.** `GitOperationSupport.kt`'s
`runGitTransportOp` (lines 48-61) catches exactly two buckets today: JGit's public
`org.eclipse.jgit.api.errors.TransportException` → `onAuthFailed` (mapped to
`DomainError.GitError.AuthFailed` by every caller), and everything else → `onFailed` (mapped to
`CloneFailed`/`FetchFailed`/`PushFailed`). **This means a plain transient `SocketException` on a
clone today is misclassified as an auth failure** (any `TransportException`, regardless of
cause, hits `onAuthFailed`), which is itself a bug independent of resilience — worth flagging to
the planning phase, since the retry logic needs to distinguish these cases but the current catch
site actively obscures the distinction before a retry wrapper would ever see it.

`DomainError.GitError` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt:69-110`)
already has cases that fit this feature with no schema change needed: `RateLimited(retryAfterSeconds:
Int?)`, `NetworkFailure(message)`, `Offline`. **`RateLimited` today is only ever constructed from
`wasmJsMain/.../WasmGitWriteService.kt:1151`** (parsing a GitHub REST API 429 response) — it is
*not* wired to any JGit-path exception on Android/JVM (confirmed: `grep -rn "RateLimited(" kmp/src/*/kotlin/dev/stapler/stelekit/git/`
shows zero production construction sites in `androidMain`/`jvmMain`). No new `GitError` subtype
appears necessary for retry/timeout — a `RetryExhausted`-style case may be worth adding for the
UI to distinguish "gave up after N attempts" from a first-try failure, but that's a design call
for the planning phase, not a stack finding.

## 5. Shallow/depth-limited clone (VERIFIED via javap)

```
CloneCommand.setDepth(int)
CloneCommand.setShallowSince(java.time.OffsetDateTime)
CloneCommand.setShallowSince(java.time.Instant)
CloneCommand.addShallowExclude(String) / addShallowExclude(ObjectId)

FetchCommand.setDepth(int)
FetchCommand.setShallowSince(...)  / addShallowExclude(...)
FetchCommand.setUnshallow(boolean)   // "un-shallow" — fetch full history later
```
Both exist natively on JGit 7.3.0, no custom plumbing needed. `setDepth(int)` alone (e.g. depth
50 or 100) is the simplest default-on lever for the "shallow clone by default for large repos"
success metric. `FetchCommand.setUnshallow(true)` is the natural mechanism if a later feature
wants to let the user opt into full history after an initial shallow clone. Neither is called
anywhere in the current codebase (`grep -n "setDepth\|setShallowSince" kmp/src/*/kotlin -r`
returns nothing) — greenfield.

## 6. Resumability — confirmed NOT a JGit built-in

```
$ unzip -o org.eclipse.jgit-7.3.0....jar org/eclipse/jgit/transport/TransportHttp.class
$ strings org/eclipse/jgit/transport/TransportHttp.class | grep -i "range\|resume"
copyOfRange          # java.util.Arrays helper noise, not an HTTP Range header
```
No `Range`/`Content-Range`/"resume" string anywhere in the compiled `TransportHttp` class.
**VERIFIED: JGit's smart-HTTP transport (the protocol GitHub/GitLab/etc. speak — as opposed to
legacy "dumb HTTP" static-file serving, which JGit also doesn't send Range headers for on the
client side either) has no byte-range/partial-pack resume mechanism.** This confirms
requirements.md's Rabbit Hole framing: "true resume" isn't available from the transport layer at
all. Practical options within JGit's actual capabilities:
- **Retry-from-current-shallow-boundary**: after an interrupted shallow clone, a subsequent
  `fetch()` with the same depth against the now-partially-populated repo is cheaper than starting
  the whole clone over, because JGit's pack negotiation only asks for what's missing relative to
  local refs — this is the closest thing to "resume" achievable with stock JGit, and is really
  "retry the operation, let negotiation do less work" rather than literal partial-pack resumption.
- `ProgressMonitor.beginTask(String, int totalWork)` / `update(int completed)` (interface
  VERIFIED — `start`, `beginTask`, `update`, `endTask`, `isCancelled`, `showDuration`) is the only
  progress signal JGit exposes; **both `clone()` implementations currently only forward the
  `title` string to `onProgress`, discarding `totalWork`/`completed`** (see
  `AndroidGitRepository.kt:101-109`, `JvmGitRepository.kt:90-97` — `update(completed: Int) {}` is
  a no-op today). Wiring `completed`/`totalWork` through is the mechanism for the wizard's
  "Resuming from 60%" UI, but it's percent-of-current-attempt, not resume-from-interruption in the
  byte-level sense the requirement's language might suggest — worth clarifying in the plan phase
  so the UI copy doesn't overpromise.

## 7. Android foreground service

Existing pattern to match in this codebase:
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/measurement/ble/AndroidMeasurementForegroundService.kt`
+ manifest declaration at `kmp/src/androidMain/AndroidManifest.xml:67-72`:
```xml
<service android:name="....AndroidMeasurementForegroundService"
         android:foregroundServiceType="connectedDevice" />
```
started via `context.startForegroundService(Intent(context, ...Service::class.java))` from
`BoschGlmKableDevice.kt`/`LeicaDistoKableDevice.kt`. That's for BLE (`connectedDevice` type); this
feature needs the **`dataSync`** type instead — the Android 14+ (API 34)
`ForegroundServiceType` for "data transfer over the network" use cases, which requires:
- Manifest: `<service android:foregroundServiceType="dataSync" .../>` on the new service.
- Manifest permission: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>`
  (a normal, not runtime-prompted, permission — auto-granted at install, unlike
  `POST_NOTIFICATIONS` which the foreground notification itself requires separately on API 33+).
- `android.permission.FOREGROUND_SERVICE` (the general foreground-service permission) is still
  required alongside the typed one.
- Android 15 (API 35) tightened `dataSync` further with a **6-hour aggregate timeout** per app
  (docs: `dataSync` services are killed if the app accumulates >6h of `dataSync` foreground-service
  time in a rolling 24h window) — unlikely to bind a single clone/fetch/push, but worth a note if
  a user has multiple large-graph syncs the same day. I did not independently verify this 6-hour
  figure against Android source in this pass (no network fetch done) — flagging as **UNVERIFIED,
  cite Android's official foreground-service-types docs in the plan phase before relying on it.**
- Runtime: `ContextCompat.startForegroundService(context, intent)` then
  `Service.startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)`
  inside the service.

This repo already depends on `androidx.work:work-runtime-ktx:2.9.1`
(`kmp/build.gradle.kts:331`), which is used today only for the periodic background *fetch*
(`WorkManagerSyncScheduler.kt` — `PeriodicWorkRequestBuilder<GitSyncWorker>`, 15-minute floor,
`NetworkType.CONNECTED` constraint). For the initial clone (long-running, user-initiated,
needs-to-survive-backgrounding), the idiomatic androidx pattern paired with a foreground service
is `CoroutineWorker.setForeground(ForegroundInfo(...))` inside `doWork()` — i.e. an **expedited
`OneTimeWorkRequest`** whose `CoroutineWorker.getForegroundInfo()`/`setForeground()` promotes it
to a foreground service with `dataSync` type, rather than a hand-rolled bound `Service` like the
BLE one. This fits `WorkManager`'s existing role in this codebase (already the sanctioned pattern
per `GitSyncWorker`) better than introducing a second, unrelated service-management mechanism.
Concretely: `GitSetupScreenSaveLogic.performCloneAndSave` (today a plain suspend call on the
screen's own scope, per requirements.md's Baseline) would enqueue a `OneTimeWorkRequest` for a new
`GitCloneWorker : CoroutineWorker`, which calls `setForeground()` before starting the JGit clone.

Per this repo's own coroutine-scope rule (`CLAUDE.md` — "never pass `rememberCoroutineScope()` to
a class that outlives the composable"): a `CoroutineWorker` already owns its coroutine context
via WorkManager's own dispatcher, not a Compose-scoped one, so this shape is compliant by
construction — no new scope-ownership footgun here, unlike a hand-rolled `Service` where care
would be needed.

## Summary of what's genuinely missing vs. what to reuse

| Need | Exists already? | Where |
|---|---|---|
| Retry/backoff primitive | Yes — reuse `arrow.resilience.Schedule` idiom | `resilience/RetryPolicies.kt` |
| Timeout on clone/fetch/push | No (only on `testRemote`) | needs `.setTimeout()` added in `AndroidGitRepository.kt`/`JvmGitRepository.kt` |
| Shallow clone | No | `CloneCommand.setDepth()`/`FetchCommand.setDepth()` available, unused |
| Transient-vs-permanent exception routing | No — current code over-collapses into 2 buckets, 1 of them wrong for SocketException | `GitOperationSupport.kt`'s `runGitTransportOp` needs a 3rd branch |
| Android foreground survival | Pattern exists (BLE service) but wrong type (`connectedDevice`); periodic-fetch `WorkManager` pattern exists and is the better base to extend | `WorkManagerSyncScheduler.kt`, `AndroidMeasurementForegroundService.kt` |
| Resumability | Confirmed absent from JGit's transport; only "retry from shallow boundary" and progress-fraction UI are achievable | N/A — bounds the feature's actual deliverable |
