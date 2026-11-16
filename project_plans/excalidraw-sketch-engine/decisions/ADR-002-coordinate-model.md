# ADR-002: Two coordinate regimes, one lossless bridge (Double scene units, 1 unit = 1/16 inch)

**Status**: Accepted (2026-11-14, plan sign-off; no owner decision outstanding)
**Date**: 2026-11-14

## Context
Open Question 4. The integer-sixteenths model (`SketchPrimitive`, `Inches`) is load-bearing for exact rational dimensions, fusion and the CSV/SVG writers (ADR-002/003 of home-project-sketching). Excalidraw and the solver use doubles.

## Decision
- v2 elements use `Double` coordinates in "scene units" where 1 unit equals 1/16 inch, so a lifted v1 value keeps the same numeric value (`Long` below 2^53 to `Double` is exact; `MAX_COORD_SIXTEENTHS = 2^28` is safe).
- `V1ToV2.lift` is total and exact. `V2ToV1.project` is partial and returns a typed `ProjectionResult` (`Projected(primitive)` or `Dropped(reason)`) for axis-aligned Rect/Line with integer endpoints after rounding once at projection time. Dropped elements never enter fusion; the UI reports "N elements not used for dimensions".
- The solver works in Doubles; solved geometry is rounded to sixteenths only at projection, never per iteration. Fusion's own verification reports a mismatch rather than snapping.
- Non-finite values and `abs(coord) > 2^28` are rejected at every boundary (import, solver output, command).

## Alternatives rejected
- Unify on Double: forces tolerance logic into Bareiss elimination and breaks golden fixtures.
- Keep integers only: cannot carry curves, rotation, Excalidraw round trip.

## Scale addendum (Story 1.2.4)
Excalidraw pixels map 1:1 to scene units on import and export (1 px = 1 unit = 1/16 inch); no conversion factor, so `export(import(x))` is exact. Excalidraw pixels carry no physical meaning, so a 100 px rect is 6.25 in in a technical sheet; this is intentional. Hit and snap tolerances are screen pixels divided by zoom, never raw scene units. Shared bounds live in `SceneLimits` (5,000 elements, 200 constraints, abs coord 2^28, 2,000 points per element, JSON depth 64, 16 MB sheet, 10,000 text chars, 20 MB import file, 2 MB largest JSON span decoded in one import call (`maxImportSpanBytes`, D-31), 60 MB / 16 MP image, 200 library items and 16 MB of stored library (`maxLibraryBytes`, each item one import span), 64 layers, soft banner at 4,000 elements / 160 constraints, 16,000 px PNG side, 100 ms slow-solve indicator); no other numeric cap exists in the sources.

## Consequences
Property tests: lift then project is identity for all int sixteenths; project of a rotated or non-integer element is `Dropped`.
