# Research: Pitfalls — web-git-writeback

Agent 4 of the `web-git-writeback` research phase. Covers rate limiting, CORS,
PAT security, races, partial-failure recovery, Kotlin/Wasm interop, and testing
pitfalls for `WasmGitWriteService` (ADR-015).

## 1. Rate limiting and abuse detection

**GitHub — two distinct limits, both matter here.**
- Primary REST limit: 5 000 req/hr for authenticated (PAT) requests — already noted
  in ADR-015's Consequences. A normal edit session (a handful of dirty files) uses
  roughly `2 + 2N` calls for the 5-step sequence (1 base-tree fetch, N blob creates,
  1 tree create, 1 ref-SHA conflict check, 1 commit create, 1 ref update) — nowhere
  near 5 000/hr even for dozens of files. **Primary limit is not a realistic risk for
  v1's "handful of files" case.**
- **Secondary rate limits / abuse detection — the real risk, not covered by ADR-015.**
  GitHub separately throttles: >100 concurrent requests, >900 "points" per endpoint
  per minute, and (most relevant here) rapid-fire write requests — GitHub's own
  guidance is to **wait at least 1 second between consecutive POST/PATCH/PUT/DELETE
  requests**. `WasmSectionSyncService`'s existing pattern fires up to 10 concurrent
  raw fetches (read path, GETs — fine). If `WasmGitWriteService` reuses that
  concurrency pattern for **blob creation** (a write, one POST per dirty file), a
  session with a moderate dirty set (10–20 files, e.g., a bulk import or "select
  all pages" scenario) firing blob creates concurrently is a plausible secondary-limit
  trigger. **This should be flagged in planning**: blob creates should be
  sequential (or lightly throttled, e.g. small concurrency cap + inter-request
  delay), not parallelized like the read-path raw fetches.
- Secondary-limit responses come back as **403 or 429**, sometimes with
  `x-ratelimit-remaining` still > 0 (i.e., you can be secondary-limited even with
  primary quota left — a check against `x-ratelimit-remaining` alone is not
  sufficient to decide whether to retry).
- `Retry-After` header convention: when present, do not retry before it elapses;
  otherwise use exponential backoff. **This matches the existing
  `WasmSectionSyncService.githubFetch()` retry logic** (`Retry-After` header or
  `1 shl retryCount` capped at 60s, max 4 retries) — that logic should be extracted
  and reused for the write path rather than reimplemented, per the requirements'
  "Rabbit Hole" about host-detection duplication. Note the existing retry loop
  only handles `429`; it does not check for `403`-as-secondary-limit, which GitHub's
  docs say is equally possible for abuse detection. **Gap to fix when reusing this
  helper for writes**: also retry on 403 responses whose body/headers indicate a
  secondary rate limit (GitHub returns a `documentation_url` pointing at the abuse
  rate limit doc, or the `retry-after` header itself is a reliable signal
  independent of status code).

**GitLab.** Default authenticated API limit on GitLab.com is 2 000 requests per
user per minute (project/group-scoped write endpoints can carry additional
per-minute limits, e.g. protected-branch pushes are separately throttled). The
single-call commits API means a write session is 1–2 requests
(conflict pre-check via `start_branch`/compare, then the commit) — **GitLab's
write path is essentially immune to rate limiting for normal edit sessions**, which
reinforces ADR-015's preference for the GitLab path when available. GitLab also
returns `Retry-After` on 429.

**Recommendation for planning**: (a) throttle GitHub blob-create concurrency
(sequential or ≤3 concurrent with a small delay) instead of reusing the 10-way
concurrency used for read-path raw fetches; (b) extract the existing
`githubFetch` retry/backoff helper into shared code so the write path doesn't
reimplement it inconsistently; (c) treat both 403-with-abuse-signal and 429 as
retryable-with-backoff.

## 2. CORS: read path vs. write path

ADR-013's read path uses **raw content URLs** (`raw.githubusercontent.com`) for
file bodies plus the GitHub REST tree API for listings — these are simple GET
requests. ADR-015's write path is different in kind: it issues **POST/PATCH
requests with JSON bodies and custom `Authorization` headers** to
`api.github.com` and `gitlab.com/api/v4`, which triggers CORS **preflight**
(`OPTIONS`) requests that raw GET-only usage never exercises.

- **GitHub**: `api.github.com` does send `Access-Control-Allow-Origin: *` and
  supports preflight for GET/POST/PATCH/PUT/DELETE with `Authorization` and
  `Content-Type` in `Access-Control-Allow-Headers`. This is a documented,
  intentional part of GitHub's API design (GitHub's own docs cover CORS/JSONP
  cross-origin usage) — **not a de-facto risk**, but it should be explicitly
  verified in a real preflight `OPTIONS` request against `api.github.com/repos/.../git/blobs`
  during implementation, not just assumed from the read-path's success, since the
  ADR doesn't call out that write calls exercise preflight while read calls don't.
- **GitLab**: GitLab's CORS story is **more nuanced than GitHub's** and is the
  bigger open risk. GitLab runs two different CORS policies: one for
  cookie/session-authenticated requests (restricted to GitLab's own origin, since
  wildcard + `credentials: include` is disallowed by the CORS spec itself), and a
  separate wildcard (`*`) policy for **token-authenticated, credential-less**
  requests. Since `WasmGitWriteService` authenticates via `Authorization: Bearer
  <PAT>` header (not cookies) and Ktor's `HttpClient` fetch should not set
  `credentials: include`, this should land in the permissive wildcard bucket — but
  this needs to be **verified against a live `gitlab.com/api/v4/projects/{id}/repository/commits`
  POST during research/implementation**, not assumed, because GitLab has a history
  of CORS-related bug reports specifically on POST/write endpoints (as opposed to
  read endpoints), and self-hosted GitLab instances (if ever in scope) may have
  CORS disabled entirely by an admin. **Flag as a planning risk**: confirm the
  exact `fetch()` call must NOT include `credentials: 'include'` or GitLab will
  reject the preflight outright per CORS spec (wildcard origin + credentials is
  mutually exclusive).

## 3. PAT security in the browser

- The PAT necessarily appears in the `Authorization` header of every write
  request, which means it **is visible in the browser DevTools Network tab** for
  the lifetime of the tab/session — this is unavoidable for any browser-native
  API-calling design (not specific to SteleKit's choices) and is explicitly
  accepted by the ADR/requirements as a v1 constraint, not a bug to fix. The
  concrete residual risk is a **shared/public machine**: anyone with DevTools
  access to that browser profile during the session can read the PAT from network
  request headers. Standard mitigations, worth calling out in the implementation
  plan as UI/logging discipline rather than architecture changes:
  - **Never log the PAT** — not the raw token, not a truncated/masked form that's
    still guessable, in any `Logger.*` call, `DomainError.message`, or telemetry.
    Grep the eventual implementation for any string interpolation of the token
    variable outside the `Authorization` header construction itself.
  - Mask the PAT in the credential-entry UI field (standard password-style input)
    and in any debug/settings screen that might echo the configured remote —
    confirm `GitSetupScreen` doesn't already log or display the token in a
    debug panel.
  - The PAT should **never appear in a URL query string** (some git host APIs
    historically supported `?access_token=` — GitHub deprecated this years ago,
    but worth confirming `WasmGitWriteService` only ever uses the header form,
    consistent with the existing `jsFetchWithToken` pattern in
    `WasmSectionSyncService.kt`).
  - Because the PAT is session/memory-scoped only (no persistence, by design), it
    is not written to `localStorage`/`IndexedDB`/OPFS — good, this closes off the
    "PAT survives after tab close and is recoverable from disk" class of risk that
    would otherwise be the bigger concern. The requirements doc is right not to
    treat non-persistence as a bug.
  - Not really mitigable but worth documenting as a known residual risk: a browser
    extension with broad host permissions, or a compromised/malicious extension,
    can read `fetch()` request headers via `webRequest`/`declarativeNetRequest`
    APIs regardless of any application-level mitigation. This is out of scope for
    SteleKit to solve but worth one sentence in the ADR/UX copy ("don't use on a
    machine with untrusted browser extensions") if not already present.

## 4. Race conditions — multi-tab

**ADR-013 already specifies `navigator.locks.request()`** keyed per
`(remote, section)` for the *read* path (section sync). **Verified by code
search: this lock is not actually implemented anywhere in the current
codebase** (`grep -rn "navigator.locks" kmp/src` returns zero matches) — the
read path's Web Locks usage described in ADR-013 §"Atomicity and tab exclusion"
appears to be aspirational/undone, not a working precedent to extend. This is a
pre-existing gap outside this project's scope to fix, but it means **planning
cannot assume a working Web Locks pattern already exists to copy for the write
path** — it would need to be implemented from scratch if the write path adopts
it.

**Does ADR-015 address the write-path race?** No. ADR-015's text does not
mention multi-tab concurrency at all — it's a genuine gap, not something the ADR
consciously deferred. Concretely: two tabs on the same graph, both with git sync
configured, both editing different (or the same) pages:
- Each tab tracks its own in-memory dirty set (per `PlatformFileSystem.kt`'s
  actual, one instance per tab/JS realm) and independently checkpoints to the
  **same** OPFS `.stele-dirty-set.json` path. Without cross-tab coordination, the
  last tab to checkpoint wins, silently dropping the other tab's dirty-path
  entries from the persisted checkpoint (though each tab's *in-memory* set is
  unaffected until reload).
- If both tabs compute a dirty set and race to push near-simultaneously: GitHub's
  5-step sequence's conflict check (step 5, ref SHA fetch before commit) means
  the **second tab to reach the ref-SHA check will correctly detect the first
  tab's already-landed commit** and fall into the conflict/auto-merge path — so a
  torn/corrupted remote write is not the risk. The risk is scoped to: (a) the
  **local dirty-set checkpoint file** getting clobbered between tabs (data
  hygiene, not remote corruption), and (b) both tabs' auto-merge logic
  independently reasoning about "non-overlapping files" using a **stale local
  view of what the other tab already pushed**, which could produce two
  well-intentioned auto-merges that each look correct locally but conflict when
  interleaved — this needs the same file-path-overlap check to also cover
  "files the other tab is concurrently pushing," which isn't observable without
  some form of cross-tab signaling.
- **Recommendation**: extend Web Locks to the write path (`navigator.locks.request("stele-write-${urlSafeRemote}")`,
  exclusive, held for the duration of the dirty-set-read → push → checkpoint-clear
  sequence) — cheap to add, matches the ADR-013 precedent even though that
  precedent isn't implemented yet, and eliminates both the checkpoint-clobber and
  the interleaved-auto-merge risk by serializing entire write-back attempts
  across tabs for the same remote. This should be called out explicitly as new
  scope in planning, not assumed to fall out of ADR-015 for free.

## 5. Partial-failure state beyond orphaned blob/commit objects

ADR-015 already covers the git-object-level partial failure (orphaned blob/tree/
commit objects between steps 4–6, resolved by GitHub GC, retry re-derives from
OPFS). What it does **not** cover is the app's own local state:

- **Dirty-set file**: per the Decision section, the dirty set is only cleared
  "on success" (step 10). If the tab is closed/crashed mid-sequence (anywhere in
  steps 2–9), the dirty-set checkpoint in OPFS is untouched — which is actually
  the **correct** default (nothing is lost, next load sees the same dirty files
  and can retry from scratch, consistent with the "re-derive from OPFS, don't
  resume" rabbit-hole guidance). No cleanup story needed here beyond what's
  already implied — this should be explicitly confirmed as a validation test
  case rather than left implicit, since it's easy to accidentally clear the dirty
  set optimistically (e.g., right after step 2's blob creates succeed, before
  the commit/ref lands) if the implementation isn't careful about *when* "success"
  is defined.
- **UI "syncing" indicator**: this is the actual gap. If a crash happens
  mid-sequence, whatever in-memory state drives the sync-status badge is lost
  with the tab — on next load the indicator should reset to idle/dirty (not stuck
  "syncing" forever), which will happen naturally if the indicator is derived
  from `isDirty()`/dirty-set presence rather than a separate "sync in progress"
  flag that itself needs OPFS persistence. **If the implementation adds a
  persisted "sync in progress" flag** (e.g., to disable the sync button while a
  push is running, which is reasonable UX), that flag needs the same
  crash-safety treatment as the dirty-set checkpoint (written defensively, or
  simply not persisted across reload at all — treat "in progress" as always false
  after a fresh page load, since no in-flight `fetch()` survives a reload anyway).
  **Recommendation**: keep "syncing" as pure in-memory/session state, never
  checkpointed to OPFS — a reload always means "not currently syncing," which is
  trivially correct and needs no recovery logic.
- **Orphaned local blobs from a since-superseded dirty set**: if a user edits a
  file, the sync starts (blob created for content-v1), then the user edits the
  same file again before the sequence completes and it fails — the dirty-set
  re-derivation on retry will pick up content-v2 and create a fresh blob, so the
  v1 blob is simply unreferenced (same GC story as ADR-015's orphaned-object
  case). Not a new risk, just confirms the "always re-derive, never resume"
  approach also correctly handles concurrent local edits during a failed
  sync, not just crash recovery.

## 6. Kotlin/Wasm-specific pitfalls

- **`fetch()` interop error propagation — checked against the existing
  pattern.** `WasmSectionSyncService.githubFetch()` (companion object,
  `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncService.kt:89-109`)
  wraps `jsFetch(...).await<JsAny>()` in `try { } catch (e: Throwable) { ... null }`.
  This **does** correctly catch a rejected Promise as a Kotlin exception at the
  `await()` call site (Kotlin/Wasm's Promise-coroutine bridge throws on
  rejection, consistent with Kotlin/JS behavior) — a network-level fetch failure
  (e.g., DNS failure, CORS rejection, offline) surfaces as a `Throwable` here, not
  a silent hang. **However, the existing pattern swallows the distinction between
  error classes**: network failure, non-2xx HTTP status, and rate-limit-429 all
  collapse to a bare `null` return with only a log line — the caller
  (`syncSection`) can't tell *why* it failed, it just no-ops. For
  `WasmGitWriteService`, this pattern is **not sufficient to reuse as-is**: per
  CLAUDE.md's `Either<DomainError, T>` convention, the write service must
  distinguish these into specific `DomainError` variants (network vs. auth vs.
  conflict vs. rate-limited vs. malformed-response) rather than folding
  everything into one generic failure, since `ADR-015`'s Observability
  Requirements explicitly ask for "which step... failed, and the resulting
  `DomainError`." **Recommendation for planning**: don't literally copy
  `githubFetch`'s `String?`-returning signature; wrap it (or a shared successor)
  to return `Either<DomainError, String>` so step-level failures map to distinct,
  loggable-without-the-PAT error variants.
- **Kotlin/Wasm's `Promise` type constraint**: recent Kotlin/Wasm releases
  restrict `Promise` interop to `JsAny` subtypes only (an intentional tightening
  vs. earlier Kotlin/Wasm versions that allowed arbitrary types) — anything
  passed across the `js()`/`external fun` boundary for the write path (request
  body construction, response parsing) needs `JsAny`-compatible signatures. Since
  the existing `WasmSectionSyncService.kt` already follows this pattern
  (`JsAny` return types on all `js(...)` externals), `WasmGitWriteService` should
  mechanically follow the same style — flagging only because it's an easy thing
  to get wrong if code is drafted against older Kotlin/Wasm examples found via
  web search, some of which predate this constraint.
- **Large base64 request bodies (GitHub blob creation)**: GitHub's blob endpoint
  accepts up to 100 MB raw content; base64 adds ~33% overhead, so GitHub's own
  guidance is to keep source files under ~75 MB. This is a non-issue for typical
  Markdown page files (KB-scale) but **is a real risk for the paranoid-mode case
  explicitly called in-scope by the requirements** ("committing already-encrypted
  `.md.stek` blobs") if any single page or attachment-embedded file is large.
  More practically relevant at SteleKit's actual file sizes: `fetch()` body
  construction from a large base64 string in Kotlin/Wasm means building a
  multi-KB-to-low-MB JS string via interop — not expected to be a bottleneck at
  typical page sizes, but worth a sanity check with a large synthetic file during
  implementation rather than assuming it scales linearly with no interop
  overhead, since string marshalling across the Wasm/JS boundary has historically
  been a performance-sensitive path in Kotlin/Wasm.
- **No test coverage today for interop failure modes**: `WasmSectionSyncServiceTest`
  (`kmp/src/commonTest/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncServiceTest.kt`)
  covers the 429-retry-exhaustion and success-after-retry cases well (`TC-6.4-G`
  tests), which is a good pattern to replicate for the write path's own retry
  logic — but there's no existing test exercising a genuinely rejected Promise
  (vs. a resolved Promise carrying a non-2xx status), which is the
  network-failure/CORS-failure case, not the HTTP-error-response case. Worth
  explicitly validating both failure shapes for the write path, since they take
  different code paths through the `await()` try/catch vs. the status-code check.

## 7. Testing pitfalls (real GitHub/GitLab write APIs)

- **Commit-history pollution / non-idempotency**: unlike the read path (GETs,
  side-effect-free, safe to hit repeatedly in CI), every successful write-service
  test run against a **real** repo creates a real commit. Repeated CI runs against
  a fixed test repo will accumulate commit history indefinitely, and tests that
  assert "the commit I just pushed has SHA X" are inherently non-deterministic
  across reruns unless the test also tears down (force-resets the branch), which
  itself is a destructive, easy-to-get-wrong operation to run unattended in CI.
  Two viable framings, matching the requirements doc's already-flagged Open
  Question:
  - **Recorded fixtures / mocked `fetch()`** for the bulk of unit/integration
    coverage (mirrors the existing test style seen in
    `WasmSectionSyncServiceTest`, which mocks `githubFetch` status/behavior
    directly) — deterministic, fast, no live credentials needed in CI, but
    doesn't catch real API drift (the exact risk the requirements' Feasibility
    Risks section already flags: "GitHub/GitLab API surface... may have shifted
    since ADR-015 was written").
  - **A single gated, opt-in live-API test** (skipped by default, run manually or
    on a schedule, not on every PR) against a disposable throwaway test repo
    dedicated to this purpose, with the test **resetting the branch to a known
    base commit at the start of each run** (not relying on accumulating history
    being harmless) — this is the standard pattern for this class of problem, and
    the requirements doc's own framing ("gated live-API test") already leans this
    direction. **Recommendation**: pick this hybrid explicitly in the plan
    (mocked-`fetch()` unit tests as the CI gate + one manually-triggered live
    smoke test), rather than defaulting to all-mocked (misses real drift) or
    all-live (flaky CI, rate-limit/secret risk) — matches VCR/cassette-style
    industry practice of mocking the bulk of coverage while keeping a narrow
    live-verification lane.
- **Secret handling for a test PAT in CI**: a live test lane needs a real PAT
  with write access to the throwaway test repo. Standard mitigations: store as a
  masked CI secret (never in a committed fixture/cassette — VCR-style tools
  commonly leak tokens into recorded cassettes if the sanitization step is
  forgotten, so any recorded-fixture approach must explicitly scrub the
  `Authorization` header value before committing cassette files, not just before
  printing logs); scope the token to the single throwaway repo, not the whole
  account/org, to bound the blast radius of a leaked CI secret; and treat the gated
  live test as something that must be safely re-runnable without human cleanup
  (auto-reset the branch on every run, as above) so a forgotten manual trigger
  doesn't silently accumulate junk commits for months.
- **Flakiness surface distinct from "network unreliable"**: even a well-behaved live
  test can flake for reasons specific to this feature — GitHub eventual
  consistency after a ref update (a `GET` immediately following the `PATCH ref`
  in a verification step can occasionally read stale data), and the secondary
  rate limit interaction described in §1 if the live test lane runs concurrently
  with other CI jobs hitting the same PAT's quota. Both are worth a short
  retry-with-backoff in the *test* itself (separate from the production retry
  logic under test), not just in the service under test.
