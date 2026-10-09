# ADR-004: Share inbox keying when no graph is configured

**Status**: Proposed (design choice, no spike; becomes Accepted together with ADR-001 at the go/no-go checkpoint, because it reuses the same inbox and drain)
**Date**: 2026-10-07

## Context
`ShareInbox` is keyed per `GraphId` (plan Story 4.4.1). The Android share overlay's "no graphs configured" placeholder (UX S10, plan Story 4.2.1) keeps the shared text on Close, but no `GraphId` exists to key it by. Consistency review N6 flagged this and Repair pass 4 left the keying undecided.

## Decision
- Reserve one sentinel slot, `ShareInbox.UNASSIGNED` (directory `share-inbox/_unassigned/`), for shares made while the graph registry is empty. It is not a `GraphId` and never appears in the graph chooser.
- On Close the text (and any image, copied to app-private storage at enqueue time) is written to the unassigned slot with its `captureId`. The overlay says "Saved. It will be added to the first graph you create." and the pending indicator (S13) reads "1 share waiting for a graph".
- When the registry gains its first graph, `ShareInboxDrain` re-keys every unassigned item to that graph by atomic directory rename (items keep their `captureId`, so the existing idempotent append applies), then drains normally once that graph is ready. Re-keying happens once; later graphs never claim unassigned items.
- Redelivery of the same share with the same `captureId` shows "Already added" (existing rule), including after the item has been re-keyed.
- Rescue actions (Copy text, Discard; Gate 1 since Repair pass 7; the UNASSIGNED slot itself is Gate 2) work on unassigned items exactly as on keyed ones. Items are never expired or dropped automatically.

## Alternatives rejected
- Refuse to close the placeholder until a graph exists: traps the user and loses text if they back out (violates "nothing is lost silently").
- Key by a synthetic default GraphId pre-created for the user: invents a graph the user did not ask for.
- Drop the text with a warning: silent loss in effect.

## Consequences
One extra directory and one rename path in `ShareInbox`; tests: enqueue with empty registry, create first graph, drain once, replay is `AlreadyPresent`, and crash between rename and drain loses nothing (see the inbox crash-safety spec in plan Task 4.4.1a). Not verified on a device yet.
