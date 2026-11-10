# ADR-002: Exact rational values for observations; `Inches` only for sheet geometry; no `Double` in the model

Status: ACCEPTED (amended by plan review patch: observation values are `Rational`) | Epic: E1

## Context
Closet values are multiples of 1/16" except lossy decimals (for example `37.9"`, `27.31"`, `5.4"`; the real ones are the private `FC.*` constants). Forcing every value into sixteenths would round `27.31` to sixteenths at ingestion and defeat the tolerance negative control, and it cannot represent `5.4` or `37.9` (adversarial review M7). `CalibrationService` uses `Double` metres.

## Decision
- `Rational(num, den)` (normalised, checked `Long` ops written in common code, no `Math.*Exact`) is the value type of every `Observation`, of solver coefficients and of derived values. `precision` is also a `Rational` (1/16 for exact fractions, 1/200 for 2-dp labels, 1/20 for 1-dp prose); a `count` dimension has no precision and compares by equality.
- `@JvmInline value class Inches(val sixteenths: Long)` is a VIEW used for sheet geometry (sketch primitives, snap, grid) and for display of rationals whose denominator divides 16.
- Comparison across sources is `abs(a-b) <= max(tol, precisionA, precisionB)`, never `==`. Non-representable results (137 / 5 to `Inches`) are returned as `NotRepresentable(exact, remainder)`, never rounded silently.
- `Double` stays in pixel math and crosses into `Rational`/`Inches` only at the display/import edge, through the pure function `CalibratedLength` (Story 4.5).
- Storage and JSON carry numerator and denominator for value and precision plus a display string; a `37.9"` observation survives a DB round trip exactly (tests in 2.1, 2.3, 2.5).
- `DomainLayerAuditTest` bans `Double`/`Float`/`toDouble`/`toFloat` in `units` and `fusion`.

## Consequences
Property tests (kotest-property) cover round trip, normalisation, field axioms, rounding, overflow. Negative control: `FC.lossyDecimalA` vs `FC.fractionA` must not conflict, by precision logic.
