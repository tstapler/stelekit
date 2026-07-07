# Journey Docs

This directory documents every in-scope editing journey for the `rich-editing-experience` project (main block editor, voice-mode input; the annotation editor gets a stub here and its deep audit in Phase G). Each journey doc is the audit unit that Epic A.2's benchmarking/heuristic review and Epic A.3's `gap-backlog.md` reference by `journey_id`.

## Frontmatter schema

Every `docs/journeys/*.md` file starts with a YAML frontmatter block:

```yaml
---
journey_id: insert-tag              # stable, kebab-case slug: ^[a-z0-9]+(-[a-z0-9]+)*$ — never renamed once referenced by gap-backlog.md
platforms: [desktop, android, ios, web]   # which platforms this journey covers (subset allowed if a platform is confirmed N/A)
jtbd_tier: functional                # one of: functional | social | in-between — see Rubric below
step_count_target: "≤2 taps (button path); ≤(query length + 1) keystrokes (typed path)"  # numeric for functional/in-between; omitted/qualitative-only for social
current_step_count:
  desktop: "N/A — typed `#` path only, already at target"
  android: "4-5 discrete steps via SuggestionBottomSheet (button path) — target not met"
  ios: "4-5 discrete steps via SuggestionBottomSheet (button path) — target not met"
  web: "typed `#` path only, already at target"
heuristic_findings: |
  (populated in Epic A.2.3 via the ux-expert agent — see Rubric §Heuristic review below)
test_ids: []                         # populated once Phase B-H implementation lands corresponding tests; empty at Phase A audit time
status: audited                      # one of: audited | stub-pending-phase-g | audit-deferred
last_verified: 2026-07-05
---
```

| Field | Required | Notes |
|---|---|---|
| `journey_id` | yes | Stable kebab-case slug. Referenced directly by `gap-backlog.md` rows — never rename once a backlog row points at it. |
| `platforms` | yes | List of `desktop`, `android`, `ios`, `web` this journey is scoped to. |
| `jtbd_tier` | yes | Must be exactly one of `functional`, `social`, `in-between` — see Rubric. |
| `step_count_target` | functional/in-between only | Concrete numeric ceiling (e.g. "≤2 taps"). Must NOT be a bare number for `social`-tier docs — social tier uses a discoverability checklist instead (enforced by `JourneyStepCountRubricTest.kt` per validation.md). |
| `current_step_count` | functional/in-between only | Per-platform current-state measurement, filled in during Epic A.2.2, compared against `step_count_target` with an explicit pass/fail call-out. |
| `heuristic_findings` | yes | Populated via the `ux-expert` agent (Epic A.2.3), citing at least Nielsen's visibility-of-system-status, consistency, discoverability, and minimal-memory-load heuristics with concrete evidence (file:line or `GAP-` id), not generic prose. |
| `test_ids` | yes (may be empty at Phase A) | Populated as `[]` at audit time; filled in by later phases once tests land. |
| `status` | yes | `audited` (fully filled in), `stub-pending-phase-g` (annotation-editor stub only), or `audit-deferred` (Phase A time-box exhausted before this doc was completed — see plan.md Risk Control). |
| `last_verified` | yes | Date the current-state claims were last confirmed against the actual code (not just written). |

## Rubric — JTBD-weighted step-count + heuristic review (ux.md §5)

Per `design/ux.md` §5 and `research/ux.md` §5, journeys are not graded on one uniform standard — the grading dimension follows the job the journey serves:

- **`functional`** tier (capture-speed is the job — e.g. insert-tag, insert-link, format-text, toggle-todo, insert-code-block, insert-table, insert-image, voice-capture): graded primarily on a **hard numeric step-count ceiling** (keystrokes/taps). A social-tier discoverability checklist is optional context, not the primary grade.
- **`social`** tier (output quality/correctness matters more than input speed — e.g. annotate-asset): graded on a **discoverability checklist** (is every action visible/labeled? does every disabled state explain why? is there a step requiring guessing?). **No hard step-count number is assigned** — forcing a number here optimizes the wrong thing (research/ux.md §5).
- **`in-between`** tier (functional but not time-critical — e.g. reorder-block, multi-select-block): graded on **both** — a numeric step-count ceiling AND a discoverability checklist, since these journeys are frequent enough to matter for speed but complex enough that a fast-but-undiscoverable mechanism (e.g. gesture-only) fails the job too.

### Heuristic review (Epic A.2.3)

Each journey's `heuristic_findings` section is populated by dispatching the `ux-expert` subagent against the journey's documented current-state step sequence plus relevant code citations, evaluating at minimum these 4 named Nielsen heuristics:

1. **Visibility of system status** — does the user get feedback that their action registered (e.g., no silent no-op, no infinite spinner)?
2. **Consistency and standards** — does this journey's mechanism match the pattern used elsewhere in the app (e.g., same icon/label convention, same shortcut-badge convention)?
3. **Discoverability** — could a new user find this capability without being told, or does it require tribal knowledge?
4. **Minimal memory load (recognition rather than recall)** — does the user have to remember an arbitrary syntax/shortcut, or is the option visible when needed?

Findings must cite concrete evidence (a file:line reference, a `GAP-` backlog id, or a specific confirmed-broken behavior) — generic statements ("could be more discoverable") are not acceptable per `JourneyStepCountRubricTest.kt`'s `heuristicFindings_should_citeConcreteEvidence...` check (validation.md REQ-2).

## Changelog

- **2026-07-05 (Phase A, Story A.1.1/Task A.1.1a)**: Checked `git log --all --oneline -- docs/journeys` (this directory has never existed on any ref reachable from this checkout's history) and `git show b3de1ec7dc --stat`. Commit `b3de1ec7dc` ("docs(ux): add editor experience journey map") does exist and adds `docs/ux/journey-map.md` (494 lines, 10 diagrammed journeys + cross-cutting gaps) — but it lives on branch `stelekit-editing` (local + `origin/stelekit-editing`), which is **not an ancestor of this branch's HEAD** (confirmed via `git merge-base --is-ancestor b3de1ec7dc HEAD`, returned false) and is not present in this worktree. **Resolution**: the prior content is real, substantive prior art (it independently documents the exact same TODO/DOING/DONE-non-functional finding, the same reorder/multi-select drag-vs-button-gap finding, and the same orphaned-command-system finding this project's own research phase later confirmed from scratch) — it has been read (`git show b3de1ec7dc:docs/ux/journey-map.md`) and its relevant findings are cited/reused inline in the journey docs below (e.g. `insert-tag.md`, `toggle-todo.md`, `reorder-block.md`, `multi-select-block.md`) rather than re-derived, per pre-mortem.md's evidence-over-narrative guidance. It uses a different schema (Mermaid state diagrams, no frontmatter, 10 broader journeys rather than this directory's 11 narrower `journey_id`-keyed docs) and is not superseded/deleted — it remains on its own branch as freestanding prior art. This directory's docs are new files following the frontmatter schema above, not a migration of that file.
