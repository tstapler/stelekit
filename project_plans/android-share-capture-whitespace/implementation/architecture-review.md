# Architecture Review: android-share-capture-whitespace
**Date**: 2026-08-10
**Verdict**: CONCERNS

**Post-review update (2026-08-10)**: The blocker below was resolved by a repair pass and
independently re-verified CLEAN by a scoped adversarial re-review (see
`adversarial-review.md`, "re-review pass" — confirmed `AndroidManifest.xml:24` claim corrected,
`@Config(sdk = [29], application = Application::class)` added to Task 2.1.1a, and remediation (3)
below satisfied via a verified local run of `:androidApp:testDebugUnitTest`, 86/86 passing —
further reinforced by a new Task 4.1.1b requiring confirmation via the real GitHub Actions run,
added in response to `pre-mortem.md` P1 #2). Retained below for the historical record of what was
found; treat as resolved, not open.

## Constitution Check
`docs/adr/ADR-000-architecture-constitution.md` does not exist in this repository (`docs/adr/`
contains ADR-001 through ADR-017, no ADR-000). No constitution to check against — proceeding
directly to the three lenses.

## Blockers (resolved — see post-review update above)
- [x] Task 2.1.1a / Risk Control row "`CaptureViewModelTest` accidentally exercises `save()`..." —
  **the plan's stated justification for test safety is factually false.** The plan claims: "confirmed
  via `AndroidManifest.xml` inspection that no `android:name` is set on `<application>`, so
  `ApplicationProvider.getApplicationContext<Application>()` returns a plain `Application`." Direct
  inspection of `androidApp/src/main/AndroidManifest.xml:24` shows
  `<application android:name="dev.stapler.stelekit.SteleKitApplication" ...>` — `android:name` **is**
  set. Under Robolectric, `ApplicationProvider.getApplicationContext<Application>()` instantiates the
  manifest-declared class, i.e. the real `SteleKitApplication`, not a plain `Application`. Reading
  `SteleKitApplication.onCreate()` (`androidApp/src/main/kotlin/dev/stapler/stelekit/
  SteleKitApplication.kt:58-104`) shows it unconditionally initializes `DriverFactory` (real SQLite
  driver), `CredentialStore`, `AndroidCameraProvider`, `ARCoreDepthProvider`,
  `AndroidMotionSensorProvider`, `OnnxMonocularDepthEstimator` (loads an ONNX model),
  `KableBleScanner`, `PlatformFileSystem`, and a real `GraphManager` — all wrapped in one outer
  `catch (e: Throwable)` that swallows failures and logs them. So the new `CaptureViewModelTest`
  will **not** get a lightweight `Application`; it will trigger this entire heavy, Android-framework-
  and-native-dependent init path on every run, with failures silently swallowed rather than
  surfaced. The plan's "this is safe because it's a plain `Application`" reasoning is the load-bearing
  premise for Task 2.1.1a and is wrong. Separately, the plan's own citation — "mirroring
  `MediaSessionObserverTest`" — does not actually validate this pattern:
  `MediaSessionObserverTest.kt` never calls `ApplicationProvider.getApplicationContext()` at all (it
  tests pure companion-object logic), so it is not precedent for the `ApplicationProvider` path.
  Three *other* existing test files (`AudiobookAutoSettingsTest.kt`,
  `AudiobookNoteScreenCarTest.kt`, `QuickTagScreenCarTest.kt`) do call
  `ApplicationProvider.getApplicationContext()` against the real manifest, so this pattern has been
  used before in this codebase — but since `androidApp/src/test` has never run in CI
  (`requirements.md`'s own finding), nobody has confirmed those three tests currently pass; this repo
  has no automated evidence either way, and I could not execute `:androidApp:testDebugUnitTest`
  in this environment to check directly (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset, no Android SDK
  installed — build fails at configuration time with "SDK location not found", verified by running
  `./gradlew :androidApp:testDebugUnitTest --no-daemon`).
  **Remediation**: (1) Fix the manifest claim in the plan/Risk Control table before implementation —
  don't ship a plan whose safety argument rests on a disproven fact. (2) Before writing
  `CaptureViewModelTest`, either (a) explicitly override the Robolectric-instantiated Application
  class for this test via `@Config(application = Application::class)` (Robolectric supports
  overriding the manifest-declared `<application>` per test/class), which gets the "plain
  `Application`" property the plan actually wants, or (b) if reusing the real `SteleKitApplication`
  is intentional, say so explicitly and note the tradeoff (slower test, dependent on Robolectric
  shadow support for camera/ARCore/BLE/ONNX, failures silently swallowed by the outer
  `catch (Throwable)` rather than surfaced). Option (a) is cheaper and matches what the plan actually
  intended. (3) Before landing Phase 3 (CI wiring), run `:androidApp:testDebugUnitTest` locally at
  least once against an Android SDK to confirm the pre-existing 8 test files (unrelated to this
  plan) are currently green — Task 3.1.1a's "add the task to CI" will otherwise surface any
  pre-existing breakage in those files as unrelated collateral damage blocking this small feature's
  merge, with the plan carrying no contingency for that.

## Concerns
- [ ] Epic 3.1 / AC8 scope — CI wiring turns on the *entire* `androidApp/src/test` source set (10
  files today: `CaptureShareTextTest`, `CaptureViewModelTest` plus 8 pre-existing files under
  `auto/`), not just the two files this plan adds/touches. This is required by AC8's literal wording
  ("CI must run `androidApp/src/test`") and is the right outcome, but the plan's Risk Control table
  and Phase 4 verification treat "green" as solely a function of this plan's own new tests. If any of
  the 8 untouched pre-existing files are already flaky/broken (plausible, since none has ever run in
  CI), Task 4.1.1a's "confirm exit 0" step will fail for a reason this plan doesn't own or explain how
  to triage. Recommend: add an explicit sub-step to Task 3.1.1a (or a new Task 3.1.1a-pre) to run
  `:androidApp:testDebugUnitTest` in isolation once, before wiring it into CI, specifically to baseline
  the pre-existing 8 files' pass/fail state — separating "pre-existing breakage, out of scope" from
  "this plan's regression" if Phase 4 fails.
- [ ] `normalizeShareWhitespace`'s placement in `CaptureActivity`'s companion object (Task 1.1.1a) —
  a general-purpose, stateless string-normalization utility is being added to an `Activity` class's
  companion object alongside unrelated Android lifecycle constants (`PREFS_NAME`,
  `KEY_TILE_PROMPTED`). This is defensible at the current scope (single caller, ~10 lines, no second
  use case found per `research/build-vs-buy.md` §4's repo-wide search), and the Pattern Decisions
  table already rejected over-engineering (Strategy pattern) for the same YAGNI reason, so a separate
  utility object is arguably premature abstraction too. Flagging only so the trade-off is explicit
  rather than implicit: if a second share/paste/import path in the app later needs the same
  normalization, it will need to reach into `CaptureActivity`'s companion object (`internal`
  visibility, module-scoped) or duplicate the regexes — extract to a small top-level object at that
  point, not now.

## Nitpicks
- The plan's Pattern Decisions table evaluates *where in the call chain* to hook normalization
  (Options A/B/C) but never separately considers *what class should own the function* — in practice
  this collapsed into one decision (Option B = both "new function" and "lives in `CaptureActivity`").
  Worth noting for future readers that these are two different axes, even though the answer for this
  scope happens to be the same for both.
- Lens 2 / primitive-obsession check: `normalizeShareWhitespace(text: String): String` is
  appropriately plain `String -> String` — no wrapper type (e.g. `NormalizedShareText`) is
  warranted here. It's an `internal`, single-caller, side-effect-free transform with no invariant
  beyond "is a string"; a wrapper would add ceremony (allocation, unwrap-at-every-call-site) with no
  corresponding safety gain, since nothing downstream distinguishes normalized from raw text at the
  type level (both `buildShareText`'s callers and `CaptureViewModel.initializeText` just take
  `String`). Confirmed as a non-issue, not forced.
- Build-vs-buy consistency (Lens 3 item 11): `research/build-vs-buy.md` recommends bespoke stdlib
  regex, no library — Task 1.1.1a's implementation (`Regex("[ \t]{2,}")`,
  `Regex("\n[ \t]*(?:\n[ \t]*)+")`, plain `.replace()` calls, no imports beyond `kotlin.text`) matches
  this exactly. No drift found.
- DDD/data-model lens (Lens 1 item 3): confirmed genuinely N/A — verified no new data class, no
  change to `ShareContent` (`androidApp/.../CaptureActivity.kt:172`, unchanged `text: String,
  imageLocalPath: String?`), and `normalizeShareWhitespace` operates on a bare `String`, not a
  domain model. The plan's own claim that this introduces no new data model holds up.
- Parse-at-boundary (Lens 2 item 7): confirmed the boundary is respected — `parseShareIntent()`
  (`CaptureActivity.kt:127`) is the existing raw-Intent boundary, and `buildShareText` (called from
  inside it) is where normalization is now applied, before the value ever reaches
  `CaptureViewModel.initializeText`. Manual-entry text via `updateText` never touches this boundary
  function, which structurally guarantees AC6's scope boundary rather than relying on developer
  discipline.
- Strategy-pattern rejection (Lens 3 item 8): correct call. One normalization behavior, no second
  implementation anticipated or requested by any AC — a pluggable `Normalizer` interface here would
  be unjustified indirection. Agree with the plan's reasoning.
