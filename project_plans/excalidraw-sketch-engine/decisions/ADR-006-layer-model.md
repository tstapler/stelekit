# ADR-006: Flat z-order plus groups and frames are primary; named layers are an optional second axis

**Status**: Accepted (2026-11-14, plan sign-off; no owner decision outstanding)
**Date**: 2026-11-14

## Context
Open Question 6; requirement 5 asks for layers with create, reorder, lock, hide, opacity. Excalidraw has only z-order (`index`), groups, frames.

## Decision
- Every element has `layerId` (default layer exists on every sheet), a fractional string z-key `index`, `groupIds` (innermost first, nested groups), optional `frameId`, `locked`.
- Effective paint order = layer order, then z-key within layer. Layer carries `name`, `visible`, `locked`, `opacity` (0..1, multiplied with element opacity), `order`. No blend modes in v1.
- The layers panel is hidden until a sheet has a second layer (progressive disclosure); reorder has drag plus Move up/down buttons (non-drag alternative).
- v1 named layers (`layer: String` on primitives, `layer_json` on sheets) lift to v2 layers by name, order preserved; `TooManyLayers` limits are kept for the v1 projection only.
- `.excalidraw` export flattens: layer order folded into `index`, hidden layers omitted, locked flag copied to `locked`, layer opacity multiplied into element opacity, a `customData.stelekit.layerId` hint written for round trip. Import creates the default layer plus layers from that hint; the hint is untrusted: layer id length capped at 64 and at most 64 distinct layers, extras beyond that are ignored.
- Hiding a layer clears selection of its elements and announces it; editing a locked layer shows the toast "Locked layer: X." with an Unlock action.

## Alternatives rejected
Layers as the only organisation (Photoshop-style load for the diagram persona); no layers (fails requirement 5).
