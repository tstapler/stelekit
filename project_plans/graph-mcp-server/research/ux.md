# UX Research: graph-mcp-server

**Date**: 2026-09-14
**Scope**: human-in-the-loop review UX for MCP-client-proposed graph edits (requirements.md).

## 0. Codebase correction: Epic 7 is already built

`requirements.md` (written earlier this research phase) states Epic 7 "is fully specced but
not yet implemented — zero matches for `PendingSuggestion`/`ApprovalGate`/`ProposedEdit` in
`kmp/src/commonMain` today." That is no longer true. As of commit
[`61b894cffe`](https://github.com/tstapler/stelekit/commit/61b894cffe) ("feat(suggestions):
unified inbox for matcher and LLM suggestions on page scan"), the full pipeline exists and is
wired into `StelekitViewModel`:

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/PendingLlmSuggestion.kt` — sealed type
  with `BlockEdit`, `TagChange`, `NewPage`, `UnlinkedReference` variants (one more variant than
  architecture.md's illustrative sketch).
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSuggestionInbox.kt` — flat in-memory
  `MutableStateFlow<Map<String, PendingLlmSuggestion>>`, unbounded, no TTL, no size cap
  (relevant to §4c below).
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSuggestionWriter.kt` — re-validates
  staleness at accept time and materializes the write via `GraphWriterPort.savePage` (relevant
  to §4a below).
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/llm/LlmSuggestionReviewScreen.kt`
  — one-at-a-time review queue with prev/next navigation, per-item accept/discard, and a
  double-tap-confirmed bulk "Apply all" / single-tap "Discard all".
- `StelekitViewModel.acceptLlmSuggestion`/`rejectLlmSuggestion`/`proposeLlmSuggestion` (lines
  ~411-490) — the re-validate-before-execute race guard, modeled explicitly on
  `acceptJournalMerge`/`abortJournalMerge`.

**Implication for planning**: this item does not need to build the approval-gate UI from
scratch. It needs to (a) add an MCP-sourced propose path that calls
`LlmSuggestionInbox.propose()` with a new `sourceProviderId` (or equivalent) so the review
screen can distinguish "proposed by the page-scan matcher" from "proposed by an external MCP
client," and (b) decide whether the existing `PendingLlmSuggestion` variants are sufficient for
MCP tool calls or need a new variant. The inbox, writer, staleness re-check, and review screen
are already shipped, tested infrastructure — reuse them, don't fork them.

---

## 1. Comparable UX patterns

**Directly analogous precedent — Obsidian "Vault Audit AI" MCP companion plugin.** This is the
closest real-world analog found: an MCP client calls `get_note` to read, then `propose_change`
(`CREATE_NOTE` / `UPDATE_NOTE` / `DELETE_NOTE`) instead of writing directly. The proposal does
not touch the vault — a companion app stores it until the user opens a review modal showing
operation type, path, summary, and a text diff, with explicit Approve/Reject buttons. This is
architecturally identical to SteleKit's `LlmSuggestionInbox` → `LlmSuggestionReviewScreen`
pattern, independently arrived at by a different tool solving the same problem (external LLM
client wants to write to a local personal-knowledge store, human must gate every write).
**Smart Note Agent** (another Obsidian plugin) reinforces the same shape: "every write operation
is presented as a unified diff for your review and requires explicit approval before anything is
written to disk."

**Cursor Composer / GitHub Copilot edit review.** Per-hunk accept/reject with a before/after
diff view is the dominant pattern for "AI proposes, human reviews" in code editors. Cursor is
called out as the strongest implementation: the agent's plan, the proposed edits, and
accept/reject controls are shown together, and the accept/reject cycle is fast enough "to feel
like editing rather than waiting." Copilot's equivalent works well for single-file diffs but
"gets awkward when edits span multiple files" — a documented pain point directly relevant to
SteleKit's `NewPage` proposals (multi-block, effectively multi-hunk).

**What works, mapped onto the existing `LlmSuggestionReviewScreen`:**
- Diff view (before/after) — already present via `BlockDiffView`.
- One-click accept/reject per item — already present.
- Batch accept — already present (`onAcceptAll`), and already implements the double-confirm
  mitigation the approval-fatigue research below recommends.

**What's a documented pain point, not yet addressed:**
- Multi-file/multi-block changes are harder to review as a single diff — `NewPage`'s
  `NewPagePreview` renders the whole proposed block tree at once with no per-block
  accept/reject; a user who wants 8 of 10 proposed blocks has to accept-all-or-manually-edit,
  no partial acceptance. Worth flagging as a v2 gap, not necessarily a v1 blocker.
- Email/calendar "AI drafted this, review before sending" (Gmail "Help me write") pattern:
  drafts stay inert until explicit send, with inline "good suggestion/bad suggestion" feedback.
  SteleKit's inbox already matches the "inert until explicit accept" half; it has no feedback
  mechanism (thumbs up/down) for tuning future proposals — out of scope per requirements.md
  ("Out of Scope: agentic loop"), but worth naming as a deliberate non-goal rather than an
  oversight.

**Approval fatigue — directly applicable finding.** The "cry-wolf effect" research (aviation/
hospital-alarm literature, cited by multiple 2026 AI-UX sources) holds that once a signal's
false-alarm/no-real-review rate passes roughly 50%, people stop reading and start clicking yes.
An MCP client that can call a `propose_write` tool repeatedly in a single conversation risks
exactly this failure mode — an inbox that fills with 15 near-duplicate block edits trains the
user to hit "Apply all" without reading. The existing double-tap "Apply all" confirmation is a
partial mitigation but does not prevent fatigue building up *before* that point (i.e., the user
may already be numb to individual diffs after suggestion #10). This is a stronger argument for
rate-limiting or batching proposals per MCP `propose_*` tool call (§4c) than the requirements
doc currently surfaces.

Sources:
- [Diff / Patch Review for AI Designers](https://aiuxplayground.com/glossary/diff-patch-review/)
- [Reviewing and accepting changes — Cursor Beginner Course](https://theneuralbase.com/cursor/learn/beginner/reviewing-and-accepting-changes/)
- [Vault Audit AI — Obsidian Plugin](https://community.obsidian.md/plugins/ai-knowledge-hub)
- [Smart Note Agent — Obsidian Plugin](https://community.obsidian.md/plugins/smart-note-agent)
- [Confirmation Fatigue and the Protocol Gap in Agentic AI Oversight](https://changkun.de/blog/ideas/human-in-the-loop-agents/)
- [Approval Fatigue — Encyclopedia of Agentic Coding Patterns](https://aipatternbook.com/approval-fatigue)
- [How to Review AI-Generated Email Drafts Before Sending Them](https://www.jotform.com/help/how-to-review-ai-generated-email-drafts-before-sending-them/)

---

## 2. User mental models: notification vs. silent inbox

**Codebase fact-check: SteleKit desktop has no OS-level notification mechanism today.**
Verified by direct search:
- `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt` uses Compose Desktop's
  `application { Window { ... } }` — no `Tray`/`ApplicationScope.Tray` composable anywhere in
  `jvmMain`, no `java.awt.SystemTray`/`TrayIcon` usage anywhere in the codebase.
- The only notification system that exists is `dev.stapler.stelekit.ui.NotificationManager` —
  an **in-app, in-window** toast/snackbar system (`activeNotifications: StateFlow<List<Notification>>`,
  auto-dismiss timers, 50-item history). It requires the SteleKit window to be visible/foreground;
  it cannot notify the user if the app is minimized, backgrounded, or the window is closed.
- Sidebar/`AppState` has no badge/counter for pending suggestions — `pendingConflicts` and
  `llmSuggestionReviewVisible` exist as boolean/map state but nothing renders "3 pending" as a
  persistent visual affordance outside the review screen itself being open.

**External research: the field's current answer is "notify + inbox," not "inbox alone."** The
"Notify / Review / Question" oversight-level framework (LangChain) and the "Agent Inbox" pattern
(modeled on email/support-ticket queues, not chat threads) both treat "an agent produced
something needing your attention" as a first-class event that should reach the user outside the
app's foreground window — the standard shape is: event → inbox entry (always) + push/OS
notification (opt-in escalation, not mandatory). Ambient-agent UX writing explicitly frames
silent accumulation as a known failure mode: agents "operating invisibly" without any ambient
signal is called out as a problem to solve, not a stable baseline.

**Recommendation for this feature, given the codebase as it stands:** v1 should not block on
building a system tray/OS notification (net-new platform surface, no existing hook to extend —
this is a real, non-trivial addition, not a config flip). Silent inbox-accumulation *matches*
what SteleKit already does for `PendingConflict`/`DiskConflict` (file-watcher conflicts also
accumulate silently until the user opens the app and notices). But two gaps are worth flagging to
planning rather than silently inheriting:
1. **No persistent badge today** even inside the open app — a user who dismisses the review
   screen (`dismissLlmSuggestionReview()`) has no sidebar affordance reminding them suggestions
   are still pending, unlike `pendingConflicts` which at least surfaces via file-path lists
   elsewhere in `AppState`. A minimal fix (sidebar count badge) is cheap and directly closes the
   "user forgets, MCP client thinks its work vanished" gap without touching OS-level APIs.
2. If the MCP server can run while the SteleKit window is closed (open question 3 in
   requirements.md — does it share the running app's process or run standalone), "silent
   inbox" becomes materially worse: the user has *no* way to know a proposal exists until they
   next open the app, for however long that is. This is an argument for resolving open question
   3 toward "MCP server only runs when the desktop app is running" for v1, specifically because
   it keeps the existing in-app notification affordance meaningful — an independent background
   MCP process would need net-new OS notification plumbing to meet the same bar.

Sources:
- [Inbox: A Notification System for Agent-to-Human Updates](https://openagents.org/blog/posts/2026-05-26-inbox-notifications-for-agent-to-human-updates)
- [7 UX Patterns for Better Ambient AI Agents](https://www.bprigent.com/article/7-ux-patterns-for-human-oversight-in-ambient-ai-agents)
- [Desktop Agent UIs and the Rise of Ambient Computing](https://zylos.ai/zh/research/2026-07-16-desktop-agent-uis-ambient-computing/)

---

## 3. Accessibility

**Existing convention doc: `docs/accessibility.md`.** This is inherited from upstream Logseq
and is written for a web/HTML app (targets WCAG AA, talks about semantic HTML tags, heading
order, NVDA/Orca screen readers). It predates the KMP/Compose migration and needs translation,
not verbatim application — "use a `<button>` not a styled `<span>`" doesn't map 1:1 onto Compose,
but the underlying WCAG AA target and the "keyboard-only navigation + screen reader + 200% zoom +
manual contrast check" manual-testing checklist (§ "Manual testing musts") still applies and
should be the bar for a new review screen.

**Actual Compose-specific practice, evidenced in code:**
- `contentDescription` appears 271 times across `commonMain`; `Modifier.semantics`/`.semantics {}`
  34 times — accessibility semantics are an established, if not universal, practice.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/HostReconnectBanner.kt` is the
  strongest precedent for an error-state banner: the whole `Surface` carries
  `.semantics { contentDescription = message }` so a screen reader announces the full banner
  text as one unit, not fragment-by-fragment.
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/transfer/QrTransferAccessibilityUxTest.kt` is
  the codebase's actual automated-a11y-test pattern: a `ComposeTestRule` assertion that **every
  clickable control on a screen also has `hasRequestFocusAction()`**, i.e. is reachable via
  Tab/focus order, not just pointer-clickable. This is a ready-made, directly reusable test
  shape for an MCP-suggestion review screen.

**Gap found: the two existing review-screen precedents have weaker semantics than
`HostReconnectBanner`.** Neither `JournalMergeReviewScreen.kt` nor the already-built
`LlmSuggestionReviewScreen.kt` has any `Modifier.semantics`/`contentDescription` beyond the two
chevron `IconButton`s' built-in labels ("Previous suggestion" / "Next suggestion") in the latter.
Accept/Discard/Apply-all buttons rely solely on their `Text` label being read by default (Compose
does expose `Text` content to accessibility services automatically, so this is not broken, but it
is a lower bar than `HostReconnectBanner`'s explicit banner-level semantics). Concretely for
planning: **no new accessibility work is strictly required to reuse `LlmSuggestionReviewScreen`
as-is** (default Material3 button/text semantics satisfy basic screen-reader/keyboard needs), but
if the MCP-specific propose flow adds new UI (e.g. a "waiting on MCP client" banner, a
stale-suggestion warning card), it should follow the `HostReconnectBanner` semantics pattern, and
a `QrTransferAccessibilityUxTest`-style `hasClickAction && hasRequestFocusAction` assertion
should be added for the review screen if one doesn't already exist (not confirmed either way in
this pass — worth a `Glob` for `LlmSuggestionReviewScreen*Test.kt` at plan time).

---

## 4. Error states

### 4a. Stale suggestion (target content changed since proposal)

**Already solved, reusable as-is.** `LlmSuggestionWriter.writeBlockEdit` (lines 80-119) re-reads
the target block via a bounded point lookup (`BlockRepository.getBlockByUuid`) immediately before
writing and compares it against `currentContentSnapshot` captured at propose time:
- Target block deleted → `DomainError.DatabaseError.NotFound("Block", blockUuid)`.
- Target block content changed → `DomainError.ConflictError.ConcurrentWrite(pageUuid, "This
  block changed since the suggestion was made")`.
Both surface through the existing `Either` error channel to `StelekitViewModel`'s snackbar/error
state (per `acceptLlmSuggestion`'s doc comment: "Errors are surfaced via `sendSnackbar` — never
silently swallowed"). For MCP-sourced suggestions this needs no new mechanism — the same
`currentContentSnapshot`-vs-live-read check applies whether the snapshot was captured by the
internal page-scan matcher or an MCP tool call at propose time. The only new requirement: the MCP
tool's `propose_write` handler must capture and pass a `currentContentSnapshot` the same way the
existing feature does, or staleness detection silently degrades to "always looks fresh."

### 4b. MCP server can't start (port/stdio issue)

**No existing precedent in this codebase for a bind failure** — the only comparable JVM
networking code is `JvmGoogleAuthManager.kt`'s OAuth loopback `ServerSocket(REDIRECT_PORT)`,
which is a one-shot, short-lived listener for an OAuth redirect, not a long-running server, and
has no visible retry/user-facing-error handling audited here. For a stdio-transport MCP server
(the requirements doc's stated v1 transport — "no HTTP/network layer needed"), "port in use" is
largely moot: stdio MCP servers are typically launched as a subprocess by the MCP host (Claude
Desktop/Code) with stdin/stdout pipes, not a bound TCP port, so the more likely v1 failure modes
are (a) the SteleKit-side process fails to launch (e.g. `GraphManager`/DB can't open — reuses
existing `DomainError.DatabaseError` machinery) or (b) the subprocess crashes/exits. Given no
existing UI surface reports "a background service failed to start," recommend: surface this the
same way `HostReconnectBanner`/`BrowserOnlySyncBanner` surface other "something silently isn't
working" states — a dismissible top-of-app banner, not a blocking dialog, since MCP
connectivity is not required for the app's core read/write loop to keep working.

### 4c. Suggestions pile up unbounded because the user never opens the review screen

**Confirmed real gap, not hypothetical.** `LlmSuggestionInbox` (§0 above) is a flat
`Map<String, PendingLlmSuggestion>` with no cap, no TTL, no eviction policy — verified by reading
the full 35-line file. Today this is bounded in practice because the only producer is a
user-triggered page-scan feature (bounded by how often the user runs a scan). An MCP client is a
qualitatively different producer: an external LLM conversation could call a `propose_write` tool
many times per session with no natural rate limit, and — per the approval-fatigue research in §1
— a large pile encourages exactly the "stop reading, click Apply all" failure mode the review
screen's double-confirm was designed to prevent, not just a memory-growth concern (session-only,
in-memory, so unbounded growth is bounded by session length, not indefinite, but still worth
capping).

Recommend three concrete options for planning to choose among (not decided here):
1. **Cap total pending count** (e.g. 20) and have the MCP tool return an explicit error/refusal
   to the calling LLM ("review queue full, ask the user to review pending suggestions first")
   once the cap is hit — turns silent pileup into a legible signal back to the MCP client itself,
   which can relay it to the human on the other end of that conversation.
2. **Per-source-provider cap** — cap MCP-sourced suggestions separately from page-scan-sourced
   ones, so one noisy MCP conversation can't crowd out the existing feature's suggestions.
3. **Do nothing for v1, revisit if real usage shows a problem** — consistent with this doc's
   general "session-only inbox, worst case is re-run the proposal" philosophy (ADR-012, per
   `LlmSuggestionInbox.kt`'s doc comment), but should be an explicit decision, not a default by
   omission, given §1's approval-fatigue evidence that pileup actively degrades review quality
   rather than being merely inert.

---

## 5. Jobs-to-be-done

**Functional job**: "Get research/analysis I already did in an LLM conversation into my wiki
without manually copying, reformatting, and re-pasting it." This is the problem statement
verbatim from `project_plans/graph-mcp-server/requirements.md` ("the same class of benefit
issue #232 ... gives scripts/other apps, for MCP hosts specifically") — the job is already
partially served today by manual copy-paste; this feature's value is eliminating that manual
step for a workflow the user (VERIFIED: git author `tstapler`, this repo) already performs.
Secondary functional job: *querying* the graph from within an LLM conversation ("what have I
already written about X") without switching to the SteleKit window — served by the read-side
tools (search/read page/list backlinks), which need no approval gate at all since they're
non-mutating.

**Emotional job**: "Trust that an AI assistant operating on my personal notes in the background
won't silently corrupt or overwrite something I wrote, without me finding out until much later."
This is the load-bearing job the entire approval-gate architecture exists to serve — it's why
`requirements.md` treats "no auto-apply, no exceptions" as non-negotiable rather than a nice-to-
have, and it's the throughline connecting all of §1-4 above: diff-before-accept (§1), some signal
that a proposal is waiting (§2), a reviewable/undoable path for someone who can't or doesn't want
to use a mouse (§3), and legible failure instead of silent data loss when something goes wrong
(§4). A single silently-applied bad edit to a personal knowledge base — years of notes — would
break this trust in one incident in a way that's much harder to earn back than the cost of one
missed proposal ever was.

**Social job**: weak/absent for this feature specifically — SteleKit's graph is explicitly a
personal, local, single-user knowledge base (no sharing/collaboration surface exists in the
codebase: no multi-user auth, no sync-to-others). There is no "look what my AI assistant helped
me build" sharing loop this feature targets, unlike e.g. a team wiki where AI-assisted edits
might need attribution for teammates' benefit. Not a gap to fix — just worth naming so planning
doesn't invent social/attribution UI (e.g. "suggested by Claude" badges beyond the minimal
`sourceProviderId` already in `PendingLlmSuggestion`) that no actual user need supports.
