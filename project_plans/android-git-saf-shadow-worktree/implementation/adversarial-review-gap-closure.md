# Adversarial Review: Gap-Closure Additions (android-git-saf-shadow-worktree)

**Date**: 2026-08-29
**Scope**: New material only (Task 3.1.2c, 8.1.2c, 8.2.1d, Story 8.2.3, Epic 8.4, Story 0.1.1 edit)
**Verdict**: CONCERNS (0 blockers, 1 concern, 2 minors)

## Method

Read `requirements.md`, `validation.md` in full, and `plan.md`'s new sections in full (Story
0.1.1, Task 3.1.2c + Story 3.1.2, Task 8.1.2c, Task 8.2.1d, Story 8.2.3, all of Epic 8.4, the
Dependency Visualization, Summary of files touched). Cross-checked every factual claim against
real source in this repo (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`,
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitRepository.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/security/AndroidCredentialStore.kt`,
`kmp/build.gradle.kts`) and independently verified the three new JGit test-support Maven
coordinates against Maven Central's Solr search API (not just web search) plus the actual
`SshTestGitServer.java` / `AppServerBase.java` source from `eclipse-jgit/jgit`'s GitHub mirror.

## Concerns

- [ ] **Epic 8.4's SSH/HTTPS round-trip tasks (8.4.1b, 8.4.1c) don't account for a real,
  pre-existing asymmetry in `AndroidGitRepository`'s auth wiring: `clone()` takes an explicit
  `auth: GitAuth` parameter and resolves it via `configureAuth()`, but `fetch()`/`push()` take
  only a `config: GitConfig` and resolve auth via the *separate* `configureTransport()` method,
  which reads `config.authType`/`config.sshKeyPath`/`config.httpsTokenKey` and (for HTTPS) calls
  `credentialAccess.retrieve(...)`.** Verified directly in
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`: `fetch()`
  (line 103) and `push()` (line 281) both call `.also { configureTransport(it, config) }`, never
  `configureAuth`; `configureTransport`'s `SSH_KEY` branch (line 466-472) only attaches a session
  factory `if (transport is SshTransport && config.sshKeyPath != null)`, and its `HTTPS_TOKEN`
  branch (line 462-465) does `config.httpsTokenKey?.let { credentialAccess.retrieve(it) } ?: return`
  — i.e., it unconditionally needs a real `credentialAccess.retrieve()` call to succeed, or the
  branch exits without attaching any credentials provider at all.
  Both Task 8.4.1b and 8.4.1c's text say only "repeat the commit → `fetch()` → `push()` →
  remote-inspection round trip from 8.4.1a" without mentioning that this repeat needs its own,
  separately-populated `GitConfig` (and, for HTTPS, a working credential lookup) — distinct from
  the `GitAuth` object passed to `clone()`. Concretely:
  - **SSH (8.4.1b)**: fixable with a one-line addition — the `GitConfig` used for `fetch()`/`push()`
    needs `authType = GitAuthType.SSH_KEY` and any non-null `sshKeyPath` placeholder (the injected
    `sshKeyProvider` still takes precedence inside `buildJschSessionFactory`, per
    `AndroidGitRepository.kt:441-444`, so the path never needs to resolve to a real file — it just
    needs to be non-null to pass the `config.sshKeyPath != null` gate in `configureTransport`).
    This is a real gap in the task text, but a small one to close.
  - **HTTPS (8.4.1c)**: more serious. `configureTransport`'s `HTTPS_TOKEN` branch requires
    `credentialAccess.retrieve(config.httpsTokenKey)` to return "testpass". Task 8.4.1c's
    `AndroidGitRepository(...)` construction call never mentions passing a custom
    `credentialAccess` (the constructor's `credentialAccess: CredentialAccess = CredentialStore()`
    default, per `AndroidGitRepository.kt:38-40`/plan.md:546-552, would apply). The real
    `CredentialStore` on Android (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/security/AndroidCredentialStore.kt`)
    is backed by `EncryptedSharedPreferences`/Android Keystore — and that file's own doc comment
    (lines 19-24) explicitly names Robolectric+Keystore interaction as troublesome enough that the
    class added a whole test-only constructor seam "without needing to fight Robolectric's
    Keystore shadow behavior." As written, Task 8.4.1c's `fetch()`/`push()` calls would either hit
    that exact friction, or (if the test's `GitConfig` leaves `authType`/`httpsTokenKey` unset)
    silently skip attaching credentials and get a `401`/`TransportException` from the
    `server.authBasic(ctx, "GET", "POST")`-protected embedded Jetty server — the opposite of the
    acceptance criterion ("a real clone() → commit() → fetch() → push() cycle succeeds").
  **Recommendation**: before implementing Story 8.4.1, amend Task 8.4.1b/8.4.1c's text to
  explicitly construct a `GitConfig` with the matching `authType`/`sshKeyPath` (SSH) or
  `authType`/`httpsTokenKey` (HTTPS) for the `fetch()`/`push()` calls, and for HTTPS specifically,
  inject a fake `CredentialAccess` via the constructor's existing (already-present, already-public)
  `credentialAccess` parameter that returns the test token for the chosen key — sidestepping the
  real Keystore path entirely rather than fighting it. This is a small, mechanical plan-text fix,
  not an architecture problem — the underlying production code (`configureAuth`/`configureTransport`)
  is correct and unchanged by this project; the gap is purely in what the *test* tasks describe
  wiring up.
  This does not block the other ~70 tasks in the plan and is confined to two leaf test tasks
  already carrying acknowledged implementation-time unknowns (key-file format, servlet wiring
  shape) — but it is a third, previously-unflagged unknown, and unlike the other two ("exact API
  call shape TBD"), this one is a genuine test-design gap that would produce a failing (not just
  uncertain) test if implemented literally as written.

## Minors

- Task 8.4.1a/b/c each independently note "no existing precedent in this repo" for composing
  `LocalDiskRepositoryTestCase`/`SshTestGitServer`/`AppServer` (plain-JUnit4, non-Robolectric base
  classes/test harnesses) with this codebase's `@RunWith(RobolectricTestRunner::class)` pattern,
  and flag confirming the composition "the first time it's actually compiled." This is honestly
  flagged, not hidden — noted here only so it isn't lost as a fourth (very minor, self-disclosed)
  implementation-time unknown alongside the two the plan already names.
- Validation.md's Coverage Targets section (`validation.md:85`) still says "all four new
  collaborators" — this "four" is a different, correct count (the four production collaborators:
  `GitShadowWorktree`, `GitWriteBackQueue`, `GitShadowFlushActor`, `AndroidGitRepository`'s new
  branches), unrelated to the `DomainError.GitError`-variant "four→three" miscount the same
  document explicitly corrects two lines later (`validation.md:88`). Confirmed by reading both
  lines — not a leftover error, just close enough in wording to double-check. No action needed.

## Verification highlights (positive findings)

- **Task 3.1.2c is a genuine, non-duplicative gap fix.** Grepped `plan.md` for every occurrence of
  `WorkingTreeWriteBackFailed`: defined at Task 0.1.2a (`plan.md:215`), referenced by Task 3.2.1b's
  caller-side handling (`plan.md:748`), but never constructed anywhere before Task 3.1.2c
  (`plan.md:706-724`). No other task constructs it. The fix is correctly scoped and consistent
  with `GitShadowFlushActor`'s real structure as defined in Task 3.1.2a (same file, same method,
  `flushPage`'s existing `if (ok) {...} else {...}` branch point).
- **SSH/HTTPS test-server research is sound, independently confirmed, not just plausible.**
  Queried Maven Central's Solr search API directly (not just prose web search) and confirmed all
  three artifacts exist at exactly `7.3.0.202506031305-r`: `org.eclipse.jgit:org.eclipse.jgit.junit`,
  `org.eclipse.jgit:org.eclipse.jgit.junit.ssh`, `org.eclipse.jgit:org.eclipse.jgit.junit.http`
  (each with `.jar`/`.pom`/`-sources.jar`/`-javadoc.jar`). Fetched the actual
  `SshTestGitServer.java` and `AppServerBase.java` source from `eclipse-jgit/jgit` on GitHub and
  confirmed: `SshTestGitServer(String testUser, Path testKey, Repository repository, KeyPair
  hostKey)` exists exactly as cited, `public int start()`/`public void stop()` exist exactly as
  cited; `AppServerBase` (which `AppServer` extends) has `setUp()`, `tearDown()`,
  `addContext(String)`, `authBasic(ServletContextHandler, String...)`, `getURI()` exactly as
  cited. The plan's own flagged uncertainty about `testKey`'s on-disk format is validated as a
  real, still-open question — the Javadoc confirms `testKey: Path` is a **public**-key file
  (`readPublicKey(testKey)` internally), which the plan did not overclaim.
- **No numbering collisions.** Grepped `plan.md` for `3.1.2c`, `8.1.2c`, `8.2.1d`, `8.2.3`, and
  every `Epic 8.4`/`Story 8.4.x`/`Task 8.4.x` reference — all are unique, newly-appended numbers
  with no pre-existing usage, and every forward/backward cross-reference (Story 0.1.1 → Task
  8.4.1b, Task 8.1.2c → Task 3.1.2c, Task 8.4.3a → Task 5.2.1c, validation.md's gap rows → their
  matching plan.md tasks) resolves to a real task that says what's claimed.
- **No `:kmp`/`:androidApp` module-boundary or visibility defects in the new material** — the
  exact recurring defect class that blocked rounds 8-10. All Epic 8.4 new files (and all of Epic
  8.1-8.3's) are listed under `kmp/src/androidUnitTest/kotlin/...`, confirmed by grepping the
  Summary of files touched and every task's own `Files:` line — none in `androidApp/`. Task
  8.2.1d's `resolveForJGit` `private` → `internal` widening is the only new visibility change in
  this round; grepped for other mentions and confirmed it's the sole place this change is made
  (matches the file's real current visibility, confirmed at
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt:415`).
- **Epic 8.4's construction calls match real signatures.** `AndroidGitRepository`'s constructor
  (Task 2.1.1a: `sshKeyProvider`, `credentialAccess`, `pathResolver`, `fileSystem`, `context`) and
  `GitAuth.SshKey(keyPath, passphraseProvider)` / `GitAuth.HttpsToken(username, tokenProvider)`
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitRepository.kt:71-83`) both match Epic
  8.4's usage exactly, field-for-field.
- **Story 0.1.1's acceptance-criteria edit is correct and no longer dangling** — cites Task
  8.4.1b by number (`plan.md:181`), which is a real task that exists and does what's claimed.
- **`kmp/build.gradle.kts` facts check out**: current `org.eclipse.jgit:org.eclipse.jgit` is
  already `7.3.0.202506031305-r` (line 322) and `org.eclipse.jgit.ssh.jsch` is still
  `5.13.3.202401111512-r` (line 325) — exactly the skew Task 0.1.1a claims to fix. `junit:junit:4.13.2`
  is already present in `androidUnitTest` (line 336), matching the plan's claim that JGit's
  `provided`-scope `junit` dependency is already satisfied.

## Readiness verdict

**Ready for Phase 5 with one pre-implementation fix recommended, not required as a hard gate.**
Six of the seven gap-closing items (Task 3.1.2c, Task 8.1.2c, Task 8.2.1d, Story 8.2.3, Story
8.4.2, Story 8.4.3, Story 8.4.4 — six stories/tasks, all independently verified against real
source) are clean: correctly scoped, non-duplicative, numbered without collision, and living in
the right Gradle module. The one substantive concern — Tasks 8.4.1b/8.4.1c's `fetch()`/`push()`
calls needing separate, unaddressed `GitConfig`/`credentialAccess` wiring beyond what `clone()`
needs — is real and verified against actual source, but it is narrowly scoped to two leaf test
tasks within one story, doesn't touch production code or architecture, and is cheap to fix in the
plan text itself before an implementer starts Story 8.4.1. Recommend amending Task 8.4.1b/8.4.1c's
text with the fix described above before implementation reaches Epic 8.4 (which is explicitly
sequenced last in Phase 8 regardless, per the Dependency Visualization); it does not need to block
starting Phase 0 through Phase 7 or the rest of Phase 8.
