# Features Research: comparable-product UX patterns for LLM provider management and approval-gated edits

Scope: how existing products solve the same four problems SteleKit's llm-service must
solve — (1) multi-provider settings UX, (2) on-device model availability/download UX,
(3) approval-gated AI-edit review UX, (4) cross-note AI synthesis UX. Findings are
written to be directly usable by the Phase 3 plan-writing agent when designing
SteleKit's Settings screen and approval/inbox UI.

---

## 1. Multi-provider LLM settings UX

### Pattern: central "provider hub" that other features consume (Obsidian AI Providers)

[`pfrankov/obsidian-ai-providers`](https://github.com/pfrankov/obsidian-ai-providers) is
the closest existing analogue to what SteleKit needs: a plugin whose *entire job* is to
be the shared provider registry so other plugins (its own "Local GPT" plugin, and
others) don't each re-implement credential storage/provider selection.

- **It does no AI work itself** — purely configuration + a typed SDK
  (`aiProviders.execute({ provider, prompt, onProgress, abortController })`) that
  consuming plugins call. This maps directly onto SteleKit's "unified provider
  abstraction + registry" requirement — the registry is a first-class object, not a
  side effect of one feature's settings.
- **Provider creation flow, per provider type**, is a uniform 3-4 step recipe
  regardless of vendor:
  1. Select `Provider type` from a dropdown (OpenAI, Anthropic, Google Gemini,
     Ollama, LM Studio, OpenRouter, Groq, "OpenAI compatible API", etc. — 19 built-in
     types).
  2. Set `Provider URL` (pre-filled with the correct default per type, e.g.
     `https://api.openai.com/v1`, editable for custom/self-hosted).
  3. Paste `API key` (skipped entirely for local types like Ollama/LM Studio — the
     form conditionally hides the field when the provider type doesn't need one).
  4. Click a **refresh button that lists available models** pulled live from the
     provider's `/models` endpoint, and pick one from a dropdown — this doubles as a
     connection test: if the URL/key is wrong, the refresh fails visibly instead of
     silently saving bad credentials.
- Directly answers **Open Question 2** (endpoint validation for the generic
  OpenAI-compatible provider): the "refresh models list" action *is* the validation
  step — no separate "Test Connection" button needed if you already need a model
  picker. Recommend SteleKit reuse this: the custom-base-URL provider's model dropdown
  populate-on-save doubles as the connectivity check.

### Pattern: one flat settings section, provider chosen inline per model dropdown (Zed)

Zed's [Agent Settings panel](https://zed.dev/docs/ai/agent-settings) (`agent: open
settings`) is a dedicated settings *surface* (not buried in general preferences) with
three sections:

- **LLM Providers** — sign in to subscription-backed providers, enter API keys, **add
  OpenAI-compatible providers**, remove providers. Keys are stored in the **OS
  keychain**, not the JSON settings file — non-empty env vars take precedence over
  keychain values (useful precedent for a "detect existing env var / secret" flow).
- **Feature-specific model overrides** — Zed lets *specific features* (inline
  assistant, commit-message generation, thread summaries) each pick a different
  model/provider than the default agent, expressed as `agent.inline_assistant_model`,
  `agent.commit_message_model`, etc. This is the direct precedent for SteleKit's
  requirement "user picks which provider each LLM-powered feature (tagging, voice,
  synthesis) uses" — Zed proves the "one global default + per-feature override" model
  scales cleanly rather than requiring per-feature credential re-entry.
- Model-level parameter overrides (e.g. temperature per provider+model pair) are
  matched last-entry-wins against a list — a pattern worth borrowing if SteleKit ever
  needs per-feature generation parameters, though not required for v1.

### Pattern: plugin-level single provider + explicit mode switch (Obsidian Text Generator)

[`obsidian-textgenerator-plugin`](https://github.com/nhaouari/obsidian-textgenerator-plugin)
supports OpenAI, Anthropic, Google, and local models but — unlike the AI Providers hub
— each is configured directly in that one plugin's own settings: choose provider →
paste API key → (for OpenAI/Azure) explicit **Chat Mode vs. Completion Mode** toggle.
This is the "naive" pattern SteleKit is explicitly moving away from (per requirements:
"every new LLM feature re-solves provider selection from scratch") — worth citing in
the plan as the anti-pattern being fixed, since it's exactly what `LlmTagProvider`'s
current hand-checking of Anthropic/OpenAI keys resembles.

### Pattern: default-to-local, credential only required for opt-in cloud escalation (Smart Connections)

[Smart Connections](https://smartconnections.app/smart-environment/settings/) ships
with **zero required API key** — its default retrieval path uses a local on-device
embedding model. API keys are only requested if the user explicitly opts into a
cloud-backed chat model. Settings let the user **keep multiple chat model configs
(local and cloud) simultaneously and choose a default**, accessed via a gear icon in
the chat UI itself (contextual settings entry point, not just a global prefs page).

This validates SteleKit's constraint "on-device tiers must continue to function with
zero network dependency; remote providers are an optional enhancement layered on top"
— it's a proven, user-tested pattern, not a hypothetical.

### Pattern: per-command / per-agent model selection with grouping (Raycast)

[Raycast AI](https://manual.raycast.com/ai) groups models by provider in a "Manage
Models" list (expand/collapse per provider, enable/disable individual models or whole
providers at once), and separately lets per-command settings (e.g. Quick AI) override
which model that command defaults to. An "Auto Model" toggle lets Raycast pick the best
available model per-request instead of a fixed pinned choice — a possible future
extension for SteleKit (not required for v1, but worth flagging as an option in the
settings screen design: "Auto (recommended)" vs. explicit pin per feature).

### Synthesized settings-screen design for SteleKit

Combining the above, recommend a two-level settings screen:

1. **Provider list (top level)** — flat list of configured provider *instances*
   (a user might configure two different OpenAI-compatible endpoints, e.g. Ollama
   locally and OpenRouter remotely, so this must be a list of instances, not a fixed
   8-provider table). Each row shows: display name, provider type icon, connection
   status (dot: connected / needs attention / not configured), and on Android/iOS rows
   for the on-device provider also show eligibility status inline (see §2) instead of
   a credential field.
2. **Add/Edit provider form** — provider-type dropdown → conditionally-shown fields
   (API key field hidden for local/on-device types; base-URL field shown only for
   custom/self-hosted OpenAI-compatible type; model-name field with a "fetch available
   models" button that both populates the dropdown and serves as the connectivity
   test) → Save.
3. **Per-feature assignment (separate section or inline per feature)** — for each
   feature (Tag suggestion, Voice formatting, Synthesis), a dropdown of currently
   configured+available providers, defaulting to "Auto" (first available in a
   priority order: on-device → cheapest/fastest configured remote) mirroring Raycast's
   Auto Model and SteleKit's existing degrade-gracefully constraint.

---

## 2. On-device LLM UX: eligibility, download, and graceful degradation

### Android — ML Kit GenAI / Gemini Nano: `checkStatus()` state machine

This is a very close match to SteleKit's existing `MlKitLlmFormatterProvider.checkEligible()`
— confirms the shape is already right, and gives the canonical 4-state enum plus the
UX rule for when to call it, straight from
[Google's docs](https://developers.google.com/ml-kit/genai/prompt/android/get-started):

```
FeatureStatus.UNAVAILABLE   // not supported on this device, or device hasn't
                             // fetched latest server config yet
FeatureStatus.DOWNLOADABLE  // supported, not yet downloaded
FeatureStatus.DOWNLOADING   // download in progress
FeatureStatus.AVAILABLE     // downloaded and ready
```

Hard rule from Google's own docs, worth enforcing in SteleKit's provider-status UI
logic: **"Make sure to call `checkFeatureStatus()` / `checkStatus()` first before
showing any related UI, so your app users won't see \[raw AICore\] errors in any
case."** i.e. never show the on-device provider as selectable/usable in the settings
list until the status check has resolved — show a neutral "Checking availability…"
placeholder first, not an optimistic "Available" default.

`download()` returns a **cancellable progress stream** (`DownloadStarted` →
`DownloadProgress(bytesDownloaded)` → `DownloadCompleted` / `DownloadFailed`) — this
maps directly onto a settings-row UI: tapping "Download" on the on-device provider row
turns it into a determinate progress bar with a cancel affordance, not a spinner.

Known device/setup failure modes Google explicitly calls out (worth surfacing as
distinct, human-readable messages rather than one generic "unavailable"):
unlocked bootloader (never supported — show "not supported on this device", no retry
button), AICore not yet finished downloading server config after a fresh device
setup/reset (show "check back later" with a retry button, not an error), no network
during download (transient — show retry). This directly informs a `DomainError`-style
sealed eligibility-reason type rather than a boolean `isAvailable`.

### iOS — Apple Foundation Models: `SystemLanguageModel.availability`

[Apple's docs](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel)
expose a 4-case `availability` property that is the direct iOS analogue of Android's
`FeatureStatus`, and is the concrete API surface Open Question 4 asked for:

```swift
.available
.unavailable(.deviceNotEligible)          // hardware doesn't support Apple Intelligence
.unavailable(.appleIntelligenceNotEnabled) // capable device, feature toggled off in Settings
.unavailable(.modelNotReady)               // downloading / not yet staged
.unavailable(.other)                       // unknown/future reasons — must have a catch-all
```

UX guidance repeated across multiple independent write-ups (converging recommendation,
not just one source): **wrap this into an app-specific enum and show three distinct
messages**, not one generic "AI unavailable":
- `deviceNotEligible` → "This device doesn't support on-device AI." (no retry, no
  settings deep-link — nothing the user can do)
- `appleIntelligenceNotEnabled` → "Turn on Apple Intelligence in Settings to use
  on-device AI here." (deep-link to Settings app if possible)
- `modelNotReady` → "The on-device model is still downloading/preparing. Try again in
  a bit." (retry button, no user action required otherwise)

This is the same 3-way split Android's state machine implies (unsupported hardware /
user-actionable toggle / transient-wait-for-download) — **recommend SteleKit define one
shared cross-platform `OnDeviceAvailability` sealed type** with these three branches
(`Unsupported`, `DisabledByUser` (Android has no exact equivalent but the shape still
fits for AICore-not-installed-and-can't-be), `Preparing`) plus `Available`, so the
Settings UI renders identical messaging/iconography for both platforms and only the
underlying eligibility check differs per `expect`/`actual`.

### General progressive-enhancement pattern across both platforms

The convergent pattern across Android, iOS, and Smart Connections (§1) is:
**probe availability before rendering any AI-feature affordance; never let a user tap
into a dead end.** Concretely: the Settings provider row and the in-feature "provider
picker" (e.g. for tag suggestion) must both consult the same async availability check,
and both must render the not-yet-available on-device provider as *visible but
disabled-with-reason* rather than hidden — hiding it silently would make an eligible
user think on-device AI doesn't exist at all, and would make Android's "call
checkStatus first" guidance harder to honor consistently across two UI surfaces.

---

## 3. Approval-gated AI edit review UX

### Pattern: per-file/per-hunk diff with selective accept, not all-or-nothing (Cursor)

Cursor's Composer, per its own docs and forum threads, presents **one diff per file the
agent wants to create/change**, navigable, each with independent accept/reject — "you
are not forced into all-or-nothing... you can accept some changes and not others."
Final review is the same UI as single-file inline edits: a diff view with
line-level accept/reject before it becomes a real edit.

Directly informs **Open Question 3** (queue/inbox vs. synchronous): Cursor's answer is
effectively **a batch, but rendered as an explicit queue the user steps through one
item at a time** (multi-file diffs presented together, but reviewed and
accepted/rejected individually) — not silent, not fully synchronous-blocking either.
Recommend SteleKit's proposal queue follow this shape: a queue/inbox of pending
suggestions **persists** (survives app restart — nothing is lost if the user closes the
app mid-review), but the review interaction itself is one-at-a-time with clear
next/previous navigation, plus a bulk "accept all" / "reject all" escape hatch only
after the user has seen at least the first item (Google Docs and Notion both offer
"accept all", see below — but always alongside per-item review, never instead of it).

### Pattern: Insert / Replace / Close, and separate suggestion-thread accept/reject (Google Docs)

Google Docs "Help me write" gives a proposed block of AI text three explicit outcomes,
not a binary accept/reject: **Replace** (overwrite selection), **Insert** (add
alongside, non-destructive), **Close** (discard, keep original untouched) — plus
**Refine** to re-generate without committing either way. Separately, Docs'
collaborative "suggested edits" mode (non-AI feature, but same underlying UI) supports
**Accept suggestion** / **Accept all** / **Reject all** per inline change, with a
required "Good suggestion / Bad suggestion" feedback signal on AI-specific output.

This maps onto **Open Question 1** (new page vs. edit-anchored-to-existing-blocks):
Docs' distinction between *replace* (edit) and *insert* (net-new, additive) is exactly
the same shape as SteleKit's distinction between "propose an edit to an existing
block/page" and "propose a synthesized new note." Recommend the approval UI use the
same verb pairing: an edit-type proposal offers **Apply / Discard**, a new-note-type
proposal offers **Create / Discard** — same underlying accept/reject mechanics, but
different button labels and a different preview affordance (diff view for edits vs.
full-note preview, effectively "insert" rendering, for new notes) so the user always
knows *which kind* of write they're approving before they approve it.

### Pattern: reviewable Plan before execution, not just reviewable output (Notion AI "Plan Mode")

Notion's [Plan Mode](https://www.notion.com/help/review-and-approve-plans-before-notion-ai-runs)
shows the *plan* (what the agent intends to do) before it runs, lets the user edit/steer
the plan in natural language, and only after an explicit **Approve plan** does Notion AI
execute and produce the actual changes. This is one layer earlier than Cursor/Docs
(which show the *result* for approval) — Notion shows *intent* for approval.

This is **explicitly out of scope for SteleKit v1** per the requirements ("not an
agentic loop... single-shot propose → user approves/rejects"), but worth flagging in
the research doc as the next rung up the ladder if a future iteration needs
multi-step/agentic graph edits: Notion's model is the concrete precedent for
"approve the plan, then approve the diff" as a two-gate pattern, should SteleKit ever
need it. For v1, only the second gate (approve the diff/output) applies.

Notion's plain (non-AI) "Suggested edits" — accept via ✔️, reject via ✖️, threaded
alongside comments — is also a useful precedent for **surfacing rejection as
non-destructive and reversible-feeling**: rejecting doesn't feel punitive, it just
"disappears" the suggestion, no confirmation dialog required. Recommend the same for
SteleKit: reject should be a single tap with no "are you sure?" (approval, which
writes to disk, is the side that deserves more friction/confirmation — not rejection).

### Pattern: comment-anchored suggestions, with a distinct "apply directly vs. open new PR" choice (GitHub Copilot code review)

GitHub's Copilot code review "Fix with Copilot" flow shows a dialog **before** handoff
begins, letting the user choose whether the fix applies directly to the current branch
or opens a separate PR targeting it — i.e., even after approving *that a change is
correct*, the user gets a second, independent choice about *how invasively* it lands.
For SteleKit, the graph-equivalent is: even after approving a synthesized-note
proposal, offer a choice of "create as a new page now" vs. "save the proposal content
somewhere reviewable first" — though this may be over-engineering for v1 single-shot
scope; flagging as a *possible* v1.1 refinement rather than a v1 requirement, since it
adds a second decision to what should stay a single-shot accept/reject per the
requirements' "no exceptions" simplicity goal.

### Recommended approval-UI shape for SteleKit, synthesized

- **A persistent queue/inbox** (answers Open Question 3): proposals accumulate and
  survive app restart; the entry point is a dedicated "Pending Suggestions" surface
  (badge count, similar to a notification inbox) rather than a modal that must be
  resolved immediately at generation time.
- **One-at-a-time review with next/previous navigation** through the queue, each item
  showing:
  - A **type badge** distinguishing "Edit" vs. "New Note" vs. "Tag Change" (answers
    Open Question 1 — both edit and net-new note are first-class proposal types with
    distinct rendering, not variations of one).
  - For edits: a **block-level diff** (before/after, using the same rendering
    primitives as SteleKit's existing markdown block renderer, so proposed changes
    look like real SteleKit content, not a generic text diff).
  - For new notes: a **full-page preview** rendered exactly as it would appear if
    created (title, properties, block tree) — Docs' "Insert" pattern, not "Replace."
  - **Apply/Create** and **Discard** buttons, no confirmation dialog on discard,
    normal write-confirmation semantics on apply (consistent with existing
    `DatabaseWriteActor` write path — the approval action is the trigger that finally
    calls into the actor).
  - A bulk "Discard all" / "Apply all" available only after entering the queue (never
    as the first action offered), mirroring Docs/Notion's all-or-nothing shortcuts
    layered on top of, not replacing, per-item review.

---

## 4. Knowledge-synthesis features in PKM tools

Direct precedent for "LLM synthesizes across multiple notes and proposes a new note":

- **Mem** — "Mem Chat" lets users ask cross-note questions ("what were the key
  takeaways from my meetings with the design team last month?") and Mem synthesizes an
  answer grounded in the user's notes. The synthesis is presented as a **chat
  response**, not automatically written back as a new note — the user must explicitly
  save/promote it if they want it persisted. This is the safer, SteleKit-aligned
  pattern: synthesis-as-conversation first, explicit "save as note" as a separate,
  user-initiated second step — which is naturally approval-gated by construction
  (nothing is proposed as a *write* until the user asks for it to become one).
- **Reflect** — positions AI synthesis (summaries, extracted action items, pattern
  identification across notes) as running **automatically in the background**
  ("without manual review" per its own marketing). This is the anti-pattern relative to
  SteleKit's hard requirement of explicit per-suggestion approval — worth citing in the
  plan as what SteleKit is deliberately *not* doing, since "no exceptions, even for
  high-confidence output" directly contradicts Reflect's always-on auto-synthesis
  model.
- **Tana** — treats AI synthesis as one operation among many inside its
  supertag/command system (summarize daily notes, extract action items from meeting
  notes) — synthesis output lands as a **generated block the user can then edit or
  discard inline**, i.e. it's inserted provisionally into the editing surface rather
  than silently committed, giving an implicit discard-by-deleting affordance. Weaker
  guarantee than an explicit approval gate, but confirms that "insert as a
  visibly-distinct, still-editable block before commit" is a workable middle-ground UI
  treatment worth considering for how a synthesized note first *appears* to the user
  before they hit the queue's Create button.

**Recommendation**: model SteleKit's synthesis feature on Mem's "propose in a
conversational/preview surface, require an explicit save-as-note action" shape, but
route that explicit action through the same approval queue described in §3 rather than
writing directly — this keeps synthesis, edit-proposals, and tag-proposals as three
instances of one queue/proposal abstraction instead of three bespoke flows, and avoids
Reflect's auto-apply anti-pattern the requirements explicitly rule out.

---

## Summary of answers to the requirements doc's Open Questions

1. **Synthesized note shape**: support both, as distinct proposal types with distinct
   preview UI (full-page preview for new notes à la Docs' "Insert"; block diff for
   edits à la Docs' "Replace") — not one generic diff view. (§3)
2. **Custom-endpoint validation**: reuse the "fetch available models" action as the
   connectivity/compatibility check (Obsidian AI Providers pattern) — no separate
   "Test Connection" button needed. (§1)
3. **Queue vs. synchronous**: persistent queue/inbox that survives restart, reviewed
   one-at-a-time with next/previous, bulk actions available only as a secondary
   shortcut layered on top of per-item review (Cursor + Docs + Notion converge on this
   shape). (§3)
4. **iOS eligibility API surface**: `SystemLanguageModel.availability` with cases
   `.available` / `.unavailable(.deviceNotEligible)` /
   `.unavailable(.appleIntelligenceNotEnabled)` / `.unavailable(.modelNotReady)` —
   maps cleanly onto Android's `FeatureStatus` 4-state enum, enabling one shared
   cross-platform `OnDeviceAvailability` domain type. (§2)
