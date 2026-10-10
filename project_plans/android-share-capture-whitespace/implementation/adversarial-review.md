# Adversarial Review: android-share-capture-whitespace (re-review pass)

**Date**: 2026-08-10
**Verdict**: CLEAN
**Scope**: Re-review of 2 previously-BLOCKED items after repair pass, plus light spot-check of concern fixes.

## Blockers
None.

## Concerns
None found in the re-checked spots.

## Minors
- The Risk Control table's `android:name` row (plan.md:108) is a single very long prose cell (~250 words) covering the correction, the real safety mechanism, the empirical baseline, and the `@Config` fix all at once. Accurate and coherent, but dense enough that a reviewer skimming the table could miss the actual mitigation. Not blocking — no factual issue, just a readability nit inherited from how the repair was written.
- Carried forward from the prior review (not re-verified this pass, out of scope): no test exercises a blank-line run at the very start/end of the payload; `MediaSessionObserverTest.kt`'s stale "run without Robolectric" doc comment sits directly under a `@RunWith(RobolectricTestRunner::class)` annotation (pre-existing bug in a file this plan only mirrors, not touches).

## Resolved from prior review

- **Blocker 1 (Task 1.2.1e AC7 contradiction) — CONFIRMED FIXED.** Task 1.2.1e (plan.md:276-286) now explicitly instructs calling `normalize(...)` directly, in these words: "called through `normalize(...)` directly — matching the pattern already established in Tasks 1.2.1a-1.2.1d, NOT `build(clip, extra, subject)`," and gives the correct reason (`buildShareText` re-joins with a hardcoded `"\n"` at `CaptureActivity.kt:197`, verified — read the file, line 197 is exactly `title != null && body.isNotBlank() && title != body -> "$title\n$body"`). The false "exercises `buildShareText`'s source-priority logic end-to-end" framing is gone; the task now correctly notes source-priority coverage already exists via the 12 pre-existing tests. I independently hand-traced the AC7 fixture (`"Example  Page Title\r\n\r\n \r\n\r\nBody   text here.\r\nSecond line."`) through the documented `SPACE_TAB_RUN`/`BLANK_LINE_RUN` regexes and the fixed 4-step order (unify CRLF → NBSP→space → collapse runs → collapse blank lines) and confirmed it produces exactly the documented `"Example Page Title\n\nBody text here.\nSecond line."` when routed through `normalize(...)`. Grepped the whole file for `AC7` and `build(` — no other place still tells the implementer to route this fixture through `build()`.

- **Blocker 2 (false `android:name` claim) — CONFIRMED FIXED.** Read `androidApp/src/main/AndroidManifest.xml:24` directly: it does set `android:name="dev.stapler.stelekit.SteleKitApplication"`. The Risk Control table (plan.md:108) now states this correctly ("DOES set `android:name`... a claim in an earlier draft of this table was factually wrong and has been corrected here") and identifies the true safety mechanism: `SteleKitApplication.onCreate()`'s outer `catch (e: Throwable)` (verified at `SteleKitApplication.kt:58-104`; the catch block is at line 98 and matches the cited line range). Task 2.1.1a (plan.md:328-349) specifies `@Config(sdk = [29], application = Application::class)`, giving `CaptureViewModelTest` a structural fix independent of the try/catch. Grepped the full plan for `sets no`, `no android`, and stray `plain` usages — no leftover false claims anywhere in the file; the only remaining "plain" usages correctly describe the plain-function whitespace transform and the plain-`Application` behavior `@Config` produces (true statements), not the manifest.

- **Spot-check (a) CRLF decision** — present and coherent. Risk Control table row "CRLF-to-bare-`\n` output convention (Decision, applies to Task 1.1.1a)" (plan.md:109) states the deliberate choice and rationale. Task 1.2.1c (plan.md:257-269) adds `` `single crlf break between two lines normalizes to a single bare newline` `` pinning `normalize("line one\r\nline two")` == `"line one\nline two"`. No contradiction with AC4's bare-`\n` case.

- **Spot-check (b) CI blast-radius note** — present and numerically accurate. Risk Control table row (plan.md:110) states Task 3.1.1a makes all 10 files in `androidApp/src/test` merge-blocking (2 new/touched + 8 pre-existing), citing the 86/86-passing local baseline. Verified independently: `find androidApp/src/test -name "*.kt"` returns exactly 10 files today (9 pre-existing including `CaptureShareTextTest.kt`, which this plan touches; `CaptureViewModelTest.kt` doesn't exist yet, consistent with "1 new"). `CaptureShareTextTest.kt` has exactly 12 `@Test` methods today (`grep -c "@Test"` = 12), matching the plan's stated baseline count.

- **Spot-check (c) Scope Decision residual-risk line** — present. Risk Control table's final row (plan.md:111) carries forward the unverified GraphWriter/GraphLoader round-trip baseline as a named residual risk, explicitly "out of scope for this ticket — not investigated or resolved here," matching the prior review's recommendation.

- **Markdown table integrity** — checked pipe-character counts per row for both the Pattern Decisions tables (plan.md:34-38, 43-48) and the Risk Control table (plan.md:100-111): all rows within each table have a consistent pipe count (5, 6, and 3 respectively), so the repair pass did not break table structure.
