# UX Research: git-sync-resilience

Research agent 5 (UX). Covers retry/resume status UX, mental models, accessibility, error/edge
states, and the Android foreground-service notification for the git-sync resilience feature.

## 0. Current implementation baseline (read before designing)

`Step5TestAndSave` —
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/GitSetupStep5TestAndSave.kt`:

- `cloneProgress: String` (line 42) is free text sourced from JGit's `ProgressMonitor.beginTask`
  title only — `JvmGitRepository.kt:92` (`override fun beginTask(title: String, totalWork: Int) {
  onProgress(title) }`) — and `update(completed: Int) {}` at line 93 is a **no-op**. There is no
  numeric progress (%) plumbed through today. Any "Resuming from 60%" UI depends on the
  architecture/implementation work wiring `update(completed)` through to a percentage — this is a
  hard dependency the UX plan must call out, not assume exists.
- `BackAndSaveRow` (lines 76–104): `val busy = saving || cloneInProgress` disables **both** Back
  and Save while a clone is running. There is no cancel affordance for an in-progress clone —
  `onCancelTestConnection` (line 144) only cancels the pre-save "Test connection" probe, a
  separate operation from the actual clone. Confirmed by reading the composable: Back does not
  cancel today, it is simply inert during `cloneInProgress`.
- `CloneProgressRow` (lines 176–186) and `TestResultRow` (lines 152–174) carry **no accessibility
  semantics** — no `liveRegion`, no `semantics {}` block at all.
- Contrast with the rest of the codebase, which already has an established convention for
  progress-region accessibility: `FolderSyncReconciliationProgress.kt:90,116`,
  `StorageMoveProgressDialog.kt:105,129`, and `SuggestionBottomSheet.kt:104` all wrap their
  progress/status text in `Modifier.semantics { liveRegion = LiveRegionMode.Polite }`. Step 5
  should extend this existing pattern, not invent a new one.
- No working custom foreground-service notification exists to copy.
  `AndroidMeasurementForegroundService.kt` (BLE) is an unimplemented stub (`onStartCommand` just
  has `// TODO(BLE): Start foreground notification...` at line 33, no actual `startForeground`
  call). `DepthModelDownloader.kt` delegates entirely to Android's built-in `DownloadManager`
  (`VISIBILITY_VISIBLE`, line 144), which manages its own system notification — not a pattern this
  feature can reuse, since git sync is JGit-driven, not `DownloadManager`-driven. **The
  foreground-service notification needs to be built from scratch**; there's no in-repo precedent
  beyond the stub's TODO comments (which do correctly flag: dedicated channel, POST_NOTIFICATIONS
  on Android 13+, auto-dismiss on completion).

## 1. Comparable UX patterns: retrying vs. resuming vs. stuck

Three different mental models, three different UI treatments:

| State | User's question | Comparable examples | UI treatment |
|---|---|---|---|
| **Retrying** (transient failure, about to try again) | "Did something just go wrong?" | `git clone` itself doesn't retry natively, but `npm install`/`pip` retry with backoff silently unless `--verbose`; mobile app stores (Play Store/App Store) show a spinning icon with no numeric detail during transient network blips | Brief, low-alarm text near the existing spinner ("Reconnecting…"), same visual weight as normal progress — never a red/error color, never a modal interruption |
| **Resuming** (large transfer picking back up from a checkpoint) | "Am I starting over?" | Dropbox/Google Drive show a percentage or file-count tick-up that continues from where it paused, not from 0; browser downloads restart the progress bar from the resume offset, not zero; game/OS updates show "Resuming download…" with the existing % preserved | The percentage or count must visibly **not reset to 0** — this is the single most important resume-vs-restart signal; if the UI can't yet show a true % (see Section 0's JGit gap), it must at minimum say "Resuming…" rather than replay the "Cloning repository…" copy that implies a fresh start |
| **Genuinely stuck** (retries exhausted or non-retryable) | "Is this ever going to finish?" | Dropbox/Google Drive switch the sync icon to a red X and stop auto-retrying past a threshold, surfacing an explicit action; app stores show "Retry" as a tappable button once auto-retry gives up | Distinct visual break from the previous two states — different color (error, not neutral), an explicit action button, and copy that says what to do next, not just that it failed |

**Avoiding the "attempt 2/4 = 25% doomed" anxiety spiral**: the pattern good UIs use is to keep
the attempt counter *subordinate* to a steady, continuous element (a spinner, or better, a
percentage that keeps climbing across attempts) rather than making "attempt N/4" the dominant
piece of text. A user reads "attempt 2/4" as a countdown to failure only when it's the biggest,
boldest thing on screen with nothing else contextualizing it. Pairing it with reassuring
framing — "Retrying… attempt 2/4" *and* keeping the last-known resume % visible underneath —
tells the user progress is preserved and the count is bookkeeping, not a doom clock. Never show
the raw retry count as the primary heading; keep the primary heading as the ongoing action
("Cloning your graph…") and demote the attempt count to secondary/caption-style text.

## 2. User mental models (non-expert audience)

This repo's target audience is Logseq-migration personal-wiki users, not git experts (per
`CLAUDE.md`'s project overview and the requirements doc's framing). Recommended vocabulary split:

**Surface (what the user sees by default):**
- "Reconnecting…" not "Retrying… attempt 2/4" as the *primary* line — but the attempt count is
  still valuable as secondary/caption text per Section 1, so: primary = "Reconnecting…",
  secondary (smaller, muted) = "Attempt 2 of 4".
- "Resuming — 60% done" not "Resuming from byte offset 40MB" or pack-transfer terminology.
- Never surface raw exception text (`SocketException`, `Software caused connection abort`,
  JGit's `TransportException` class names) in the primary UI. This is exactly the bug report that
  motivated this project (`requirements.md`'s baseline: `Clone failed: Software caused connection
  abort` was the raw, un-translated error shown to the user).

**Hidden but available (for the minority who want detail or need to file a bug report):**
- An expandable "Details" disclosure or a debug/log view that has the real exception message and
  transfer internals — mirrors how Dropbox/Drive keep a "View sync history" or activity log
  behind a menu rather than in the main status row. This satisfies the "no fix without root
  cause" instinct of a power user without burdening the primary audience.

## 3. Accessibility

**Live region pattern for Step 5's retry/resume status:**
- Reuse the codebase's existing convention (`Modifier.semantics { liveRegion =
  LiveRegionMode.Polite }`, as used in `FolderSyncReconciliationProgress.kt` and
  `StorageMoveProgressDialog.kt`) on the container `Row`/`Column` that holds the progress text —
  `polite`, not `assertive`: this is a background operation, not a user-blocking alert, and
  `assertive` would interrupt whatever the screen reader is already announcing.
- **Avoid over-announcing every retry tick.** Per the ARIA live-region best practice (MDN, and
  the NVDA/Chrome batching behavior), rapid successive text changes to a polite region get
  coalesced or dropped inconsistently across screen readers. The fix used by mature
  implementations: debounce/coalesce the announced text so a screen reader is not asked to
  re-announce on every percentage tick — announce meaningfully at state *transitions*
  (attempt N → N+1, "resuming" → new %, failure), not on every progress-monitor callback. A
  reasonable rule: update the live-region text at most once per retry attempt and at most every
  few percentage points of resume progress (e.g., every 10%), not on every JGit callback.
- Keep the same `contentDescription`/icon pattern already used for `TestResultRow` (lines
  160–166) for the terminal states (success check, error icon) — that part of the existing
  pattern is fine to keep as-is, just extend it to the new retry-exhausted / non-retryable states
  with distinct icons (see Section 4).

**Android foreground-service notification accessibility:**
- Notification title/body text is read by TalkBack automatically when announced — no special ARIA
  equivalent needed, but the content itself must carry the same "why," not just a raw percentage:
  e.g., "Syncing graph — 60%" reads fine, "myrepo.git: 60%" does not (jargon).
  `POST_NOTIFICATIONS` permission and a dedicated low-importance channel (already correctly
  flagged as a requirement in the `AndroidMeasurementForegroundService.kt` stub's TODO comments)
  are prerequisites, not accessibility items per se, but they gate whether the notification is
  shown at all.
- Avoid `setOnlyAlertOnce(false)`-style repeated sound/vibration on every progress update — that
  is the notification-channel equivalent of the live-region over-announcing problem, and doubly
  disruptive for a screen-reader/TalkBack user who gets the update spoken *and* an alert tone
  each time. Use `setOnlyAlertOnce(true)` so only the first post/initial state change alerts.

## 4. Error states and edge cases

**Retries exhausted vs. non-retryable — must look visually distinct** (per the pitfalls
research's transient-vs-permanent distinction referenced in the task):
- *Retries exhausted* (all N attempts hit transient failures): the operation *could* work later —
  copy should say so and offer a same-context "Try again" that starts a fresh retry/resume cycle
  from the last checkpoint, not from 0. E.g., "Couldn't finish after 4 attempts. Check your
  connection and try again — your progress is saved." Icon: warning, not a hard error/X — this is
  recoverable.
- *Non-retryable* (bad credentials, repo not found, 403/404, auth failure): retrying is pointless
  and the current auto-retry logic must not spin through 4 attempts on a 401 — that's both bad UX
  (looks like it's "trying" when it can never succeed) and wasted time/battery. Surface
  immediately, no attempt counter shown at all, and point at the actual fix: "Repository not
  found — check the URL in Step 2" / "Authentication failed — check your token/SSH key in Step
  3." This is a design constraint on the retry-classification logic (which errors count as
  retryable) that the UX depends on — flag as a cross-cutting dependency with the architecture
  research, since the UI cannot tell these apart if the underlying retry policy doesn't
  distinguish them.

**Manual cancel of a resumable clone mid-transfer:**
- Add an explicit, always-visible "Cancel" action during `cloneInProgress` — today there is none
  (Section 0: Back is simply disabled, not repurposed as cancel). Mirror the existing
  `onCancelTestConnection` pattern (line 144) which already proves the composable can host a
  cancel action next to an in-progress spinner — the same shape should be added to
  `CloneProgressRow`/`BackAndSaveRow` for the clone itself.
- **Partial state on cancel: keep it, don't discard.** This is consistent with the resumable-clone
  premise of the whole feature (success metric: "resumes from where it left off, not from 0%")
  and with how Dropbox/Drive/browser downloads treat a paused/cancelled transfer — the partial
  data stays on disk so a later retry resumes rather than restarts. Discarding on manual cancel
  would be an inconsistent, worse experience than an accidental network drop, which is absurd:
  the user would learn to prefer letting the network drop it rather than cancelling deliberately.
  UI copy on cancel should say so explicitly: "Cancelled — your progress is saved. Resume anytime
  from Step 5." This is a decision the architecture/implementation phases need to honor (don't
  clean up the partial clone directory on user-initiated cancel).

## 5. Foreground-service notification content

Recommended notification, keyed to what the user actually needs mid-background-transfer:

- **Title**: "Syncing {graph name}" (use the graph's display name, not a raw repo URL/path — same
  jargon-avoidance principle as Section 2).
- **Body**: state-dependent, mirroring Step 5's own copy so the two surfaces never disagree —
  "Cloning — 60%" / "Reconnecting… (attempt 2 of 4)" / "Resuming — 45%" / "Sync failed — tap to
  retry" on terminal failure.
- **Progress bar**: yes, indeterminate while `totalWork`/`completed` aren't yet meaningfully known
  (today's state per Section 0), determinate once the resume-percentage plumbing exists. Don't
  fake a determinate bar with a percentage that isn't real — that's worse than an honest spinner.
- **Tap target**: tapping the notification should return to Step 5 ("Test & save") specifically,
  not just the app's home/last screen — the user's context was mid-setup, and dropping them
  anywhere else forces them to re-navigate the wizard to see what happened. This needs a deep-link
  / pending-intent back into the git-setup wizard's Step 5 route.
- **Dismissal**: non-dismissible while active (Android foreground-service requirement, already
  correctly noted in the existing stub's TODO), auto-dismiss on success, and on failure convert
  it to a normal (dismissible) notification rather than leaving a stuck non-dismissible one — an
  un-dismissible failed-state notification is itself a "looks broken" signal.

## 6. Jobs-to-be-done and priority call

- **Functional job**: "get my notes synced" — met by the retry/resume mechanism itself (auto
  recovery, shallow clone default, resumable transfer) — this is the engineering-heavy part, not
  primarily a UX call.
- **Emotional job**: "don't make me worry the app is broken or hung on a slow connection" — this
  is what Step 5's status UI and the notification directly serve. A user staring at an unchanging
  spinner for 90 seconds on a flaky connection, with zero attempt/resume signal, reads that as
  "frozen," not "working." This is the job the requirements doc's explicit user decision (show
  retry/resume status rather than staying silent) was made to serve.
- **Social/trust job**: "this app won't corrupt or lose my wiki mid-sync" — served most directly
  by the cancel-keeps-partial-state behavior (Section 4) and by clearly distinguishing
  retryable-so-still-safe failures from failures that need the user's action — a user who cancels
  or hits a wall should never be left wondering whether their graph is now in a half-written,
  broken state.

**Priority call if appetite runs short**: prioritize **"don't look hung"** (the emotional job)
over an exact resume-percentage. A qualitative but honest and continuously-updating status
("Reconnecting… attempt 2 of 4" with a moving spinner, even without a numeric %) already defeats
the core failure mode described in the bug report — a silently frozen UI with no feedback at all.
A precise resume percentage is a strict enhancement on top of that, and per Section 0 it also
carries a real engineering dependency (JGit's `update(completed)` currently does nothing) that
"don't look hung" does not: attempt-count + spinner + reconnecting copy can ship without any new
progress-plumbing at all. Do not let the percentage-accuracy work block shipping the qualitative
non-hung status.

## Open dependencies for other research/planning tracks

- **Architecture/implementation**: JGit's `ProgressMonitor.update(completed)` is currently a
  no-op (`JvmGitRepository.kt:93`) — true resume-% UI needs this wired through before the "60%"
  copy in this doc can be literal rather than aspirational.
- **Retry-policy classification**: the UI in Section 4 assumes the retry logic itself can tell
  retryable (network) apart from non-retryable (auth/404) errors and only shows the attempt
  counter/auto-retry UI for the former. This is a pitfalls/architecture research concern, not a
  UX one, but the UI design here depends on it.
