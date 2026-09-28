# Adversarial Review: llm-service Implementation Plan (re-review after patch)

**Reviewer stance**: skeptical senior engineer, Phase 3 gate before Phase 4 (validation).
**Verdict: CONCERNS** (upgraded from prior BLOCKED)

This is a re-review of `project_plans/llm-service/implementation/plan.md` after a patch
pass addressed the prior review's 3 BLOCKED and 4 MAJOR findings. All 7 are verified
resolved in substance, not just in wording. Two of them (B3/iOS scope, M2/
`VoiceCaptureSettings.kt`) required checking that new content actually exists (not just a
promissory reference), and one review pass caught the plan mid-edit with a stale/
duplicate-numbered Epic 5 — the version checked here is the final, stable file
(1768 lines, md5 `e699a9fb4e9b9fb5a5fded1b2ffa9abf` at review time). No fatal issues
remain. Two non-fatal editorial/consistency defects remain in the document's own summary
tables, unrelated to the original findings — these should be fixed before Phase 4 but do
not block starting it.

---

## Prior BLOCKED findings — resolution status

### B1. Credential migration race-safety — RESOLVED

Story 2.1 Task 2.1d adds `CredentialAccess.storeBlocking()` with an `AndroidCredentialStore`
override calling `prefs.edit().putString(key, value).commit()` (synchronous, durable-before-
return), explicitly scoped to the migration path only — existing git-credential call sites
are untouched, preserving "no behavior change" everywhere except this one documented
carve-out. Story 2.2 Task 2.2d adds `LlmCredentialStore.setApiKeyBlocking()` delegating to
it. Story 2.3's migration (`LlmCredentialMigration.runIfNeeded()`) now explicitly calls
`setApiKeyBlocking()` (not `setApiKey()`) for the write, with the acceptance criteria
spelling out *why* in the same crash-window terms as ADR-011 and the original finding
(`apply()`'s synchronous in-memory update vs. asynchronous disk flush, and the read-back
check's blindness to that gap). Task 2.3c's test list explicitly asserts the migration
calls `setApiKeyBlocking()` not `setApiKey()` (regression guard against silent reversion)
and covers durable-write-failure and corrupted-read-back edge cases. This is a complete,
correctly-reasoned fix — verified against the live `AndroidCredentialStore.store()` /
`GraphWriter.kt` code, matches ADR-011's Decision section exactly.

### B2. `Settings.containsKey()` capability — RESOLVED

New Story 1.7 (Epic 1 — a hard prerequisite epic, landing before Epic 8 per the sequencing
overview) adds `containsKey(key: String): Boolean` to the `Settings` interface with
implementations for all three actually-existing platform actuals (android/jvm/wasmJs),
correctly noting there is no iOS `Settings` actual today (a real, correctly-scoped-out
pre-existing gap). Task 1.7b requires updating every fake `Settings` test double
(`VoiceSettingsTest.MapSettings` etc.) so downstream tests can rely on it. Ordering is
correct: Story 1.7 ships in Epic 1, before Epic 8 Story 8.2 (the existing-install guard)
needs it. Minor nit, not a defect: Story 8.2's acceptance criteria describes the check in
prose ("key was already present in `Settings` pre-upgrade") without literally naming
`containsKey()`, but Story 1.7's own text explicitly says it exists "as a prerequisite for
Epic 8 Story 8.2's install-vs-upgrade guard" and Task 1.7b references "downstream tests
(Story 8.2c)" — the linkage is clear enough for an implementer.

### B3. Epic 5 (iOS on-device) scope/CI framing — RESOLVED

Epic 5 is now 5 stories / 27 tasks (was 3 stories / ~11 tasks), stories 5.1–5.5 with
sequential, non-duplicated numbering (verified — no duplicate story or task IDs exist
anywhere in the current document; an earlier read mid-session caught a transient state
with Story 5.2/5.3 reused twice and mislabeled task IDs, but the file has since settled to
the corrected, consistent version checked here):

- **Story 5.1** stands up the repo's first-ever Xcode project (previously just "identify
  the path during implementation").
- **Story 5.2** is a new, explicit cinterop bridge smoke test (`PingShim`, a trivial no-op
  `@objc` method) that must pass before Story 5.3 starts — this directly retires the
  B3 concern that the real Foundation Models integration would be the first time a
  hand-authored `.def` + custom Swift shim is exercised in this codebase.
- **Story 5.3** (Swift shim, real methods) explicitly builds on Story 5.2's now-proven
  mechanism rather than re-deriving it.
- **Story 5.4** (cinterop `.def` for the real shim) and **Story 5.5** (`iosMain`
  `LlmProvider` implementation) round out the original scope.

**CI framing corrected**: Epic 5's goal section now states plainly that `ci-ios.yml`,
`build-native-libs.yml`, and `release.yml` run on `macos-latest` today (the original "CI is
Linux-only" claim was wrong), and correctly narrows the real gap to the two documented
upstream blockers (Gradle #17559 classloader mismatch, K2/Compose-Multiplatform klib
incompatibility) that `ci-ios.yml` itself already works around via a JVM proxy compile.
Story 5.3's manual-verification task repeats this corrected framing rather than the old
one. This is a real, substantive fix, not a relabel — verified against the actual repo
state cited (still zero `.xcodeproj`/`.swift`/`Podfile` files, confirmed in this pass too).

One residual, non-blocking inconsistency: **ADR-013 itself** (`decisions/ADR-013-ios-on-
device-llm-swift-shim.md`, lines 54–58 and 133–135) still contains the original phrasing
("this project's CI is Bazel/Gradle on Linux for JVM/Android... No CI lane can exercise
this path") that plan.md's Epic 5 goal section now explicitly calls out as imprecise. Since
plan.md is the implementation-driving artifact and now self-corrects with the accurate
GRADLE-17559/K2 framing, this doesn't block implementation, but the ADR should get the same
one-paragraph correction for consistency the next time it's touched.

---

## Prior MAJOR findings — resolution status

### M1. Story 7.6 synthesis candidate-selection heuristic — RESOLVED

Story 7.6 now has an explicit "Candidate-selection heuristic (explicit, not an
implementation-time judgment call)" bullet: the ≤20 content-fetched pages are exactly the
union of (a) backlinks via `BlockSearchRepository.getLinkedReferences(pageName, limit,
offset)` (bounded/paginated) and (b) pages the current page itself links to, resolved from
already-loaded `[[wiki-link]]` names via `getPagesByNames()`. It explicitly forbids adding
a new tag-similarity/full-text-relevance scan-based heuristic, and states the truncation
priority (backlinks over outbound links) when the union exceeds 20. Task 7.6b's test is
extended to assert the *selected candidate set* matches this heuristic exactly, not just
that the query count stays low — closing the exact loophole the original finding flagged
("a future implementer substituting a scan-based heuristic that happens to still call
`getPagesByNames()` would still fail the test"). This is a complete fix.

### M2. `VoiceCaptureSettings.kt` retirement — RESOLVED

New Story 6.6 ("Retire `VoiceCaptureSettings.kt`'s redundant Anthropic/OpenAI key fields")
exists in full, with acceptance criteria, file list, and two tasks (6.6a removes the 4 call
sites + UI fields, 6.6b adds/updates screenshot coverage and is the explicit checkpoint
Story 2.4 and Story 8.4 both depend on). The call-site citations (lines ~36-37, ~183-184)
were verified directly against the live file in this pass and are accurate. Story 2.4's
"blocked on Epic 6 Story 6.6" gate now points at a real, fully-specified story — this is
worth calling out because an earlier read during this same review session caught a
transient state where Story 6.6 was referenced three times but not yet written; it exists
now in the stable version.

### M3. `wasmJs` Ktor client engine — RESOLVED (already correctly fixed, re-confirmed)

Story 1.8 adds `ktor-client-js:3.1.3` to `wasmJsMain` with a build-verification task, and
Epic 3 Task 3.3d requires re-confirming `bazel build //kmp:web_app` after Gemini/generic-
OpenAI-compatible land, plus a manual CORS check with an explicit instruction to hide/
disable remote-provider configuration on the web target if CORS blocks it in practice.
Story 6.2's acceptance criteria now includes the corresponding web-target constraint bullet,
correctly cross-referencing Task 3.3d's finding rather than asserting an outcome that
hasn't been verified yet. Complete, correctly sequenced fix.

### M4. Story 7.4 wrong citation — RESOLVED

Story 7.4 now contains an explicit "**Citation correction (post-adversarial-review)**"
paragraph: it states the original `GraphWriter.kt:528-529` citation was wrong (verified
again in this pass — that line is still a fire-and-forget `currentScope.launch {
writeActor.savePage(updatedPage) }` inside `GraphWriter.kt` itself, with no error
surfacing), and redirects to `StelekitViewModel.sendSnackbar()` (verified present at line
1784, with an existing call site at line 1258 for the external-file-conflict flow — exact
match to what the original finding asked for). Complete fix.

---

## New findings from this pass

### C1 (non-blocking, editorial). Implementation Order Summary table has two stale story-range rows

**Where**: plan.md, "Implementation Order Summary" table.

The table's "Stories" column says:
- Epic 5 row: `5.1–5.3` — actual epic now has **5.1–5.5** (5 stories, per the B3 fix above).
- Epic 6 row: `6.1–6.5` — actual epic now has **6.1–6.6** (Story 6.6, per the M2 fix above).

Both are leftover from before the two epics were expanded; the table wasn't updated in
lockstep with the body content it summarizes. Low risk in practice — the epic bodies
themselves are internally consistent and unambiguous (no duplicate/missing IDs, confirmed
by scanning every `### Story` and `##### Task` heading in the document) — but a reader or
a Phase 5 dispatcher skimming only this table would undercount the work in both epics.

**Fix**: update the two table cells to `5.1–5.5` and `6.1–6.6`.

### C2 (non-blocking, editorial). "Total: 8 epics, 34 stories, 87 tasks" is stale/wrong

Actual counts in the current document (verified by counting every `## Epic`, `### Story`,
and `##### Task` heading): **8 epics, 39 stories, 102 tasks** — not 34/87. The gap (+5
stories, +15 tasks) is consistent with the cumulative effect of the B3 fix (+2 stories in
Epic 5), the M2 fix (+1 story in Epic 6), and the smaller additions elsewhere (Story 1.7,
Story 1.8) not having been rolled into this summary line when it was last written.

**Fix**: update the total line to match (39 stories, 102 tasks), or regenerate it
mechanically from the heading counts rather than hand-maintaining it, to avoid the same
drift recurring after the next edit.

---

## What remains correct from the original review (re-confirmed, not re-litigated)

The original review's "confirmed correct" section (CLAUDE.md architectural rules, Android
on-device tri-state fix, Epic 7's bounded-read primitives existing as claimed, most
line-number citations, scope discipline vs. requirements.md, test coverage adequacy) was
not touched by the patch and remains accurate on spot-check — in particular
`GraphWriter.savePage(page, blocks, graphPath)` was re-verified in this pass to still be a
`suspend fun` returning `Unit` (not `Either`), matching the plan's repeated claim.

The two MINOR items from the prior review (N1: Android on-device download-progress UX
omission, N2: `JournalMergeReviewScreen`'s actual signature lacks a distinct `onReject`
callback) were not addressed by the patch and remain open as non-blocking polish notes —
neither was in scope for this patch pass and neither blocks Phase 4.

---

## Verdict

**CONCERNS.** All 3 original BLOCKED findings and all 4 original MAJOR findings are
resolved in substance, verified against the live repository, not just reworded. The plan is
implementable as written. The only remaining issues are two stale summary-table entries
(C1, C2) that don't affect the epic/story/task content itself — fix them for hygiene before
Phase 4, but they are not a gate.
