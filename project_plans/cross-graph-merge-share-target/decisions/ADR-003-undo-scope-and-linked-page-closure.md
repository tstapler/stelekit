# ADR-003: Undo scope, linked-page closure default and cap

**Status**: Proposed. No spike tests this ADR directly. It depends on the hash-checked `removeBlocks`/`deletePageFile` of both writers, so it flips to Accepted at the Phase 0 checkpoint together with ADR-001 (Spikes 0.1.1 and 0.1.4 green). The closure default/cap (Off, depth 1, confirm above 200, cap 1000) are design choices with no measurement behind them; they are Accepted as initial values and revisited after Gate 1 usage (metrics M1-M5 in requirements.md).
**Date**: 2026-10-07

## Decision
- **Undo = last completed run only**, via a per-run manifest (`<appDataDir>/merge-manifests/<mergeId>.json`:
  target graph id, created page files + SHA-256 of what was written, added block UUIDs per page). "Undo this copy"
  (result dialog + snackbar) removes only created files whose current hash still equals the written hash, and only
  added blocks whose content is unchanged; anything the user edited since is left and reported. Offered for 7 days
  (same sweep as staging markers), then the manifest is deleted. No history list in v1. No pre-merge target snapshot (heavy at 8k pages).
- Share capture undo stays the existing "delete just-added block".
- **Include linked pages**: default OFF. When on: depth 1 only (never transitive), visited-set dedup, live delta in the
  picker ("adds N pages"); N > 200 requires explicit confirmation; hard cap 1 000 added pages (the user can run again).
  **Assets**: opt-in with linked pages; copied by content hash; same name + different bytes -> `name-<hash8>.ext` and
  links rewritten; missing source asset = warning, not failure.

## Rationale
Manifest undo is safe by construction (touches only what this run added) and cheap; closure explosion is the named rabbit hole.
