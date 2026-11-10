# ADR-003: Two-phase fusion: structural constraints first, observations compared after

Status: ACCEPTED (amended by plan review patch) | Epic: E1

## Context
Measured values from a table, drawing, YAML or prose can each be wrong. Treating them as hard equations lets one bad observation poison the whole system and gives poor blame (pitfall 6).

## Decision
Constraints are linear equalities over rationals only (`Sum`, `Equal`, `Offset`). Phase A solves structure (union-find for `Equal`/`Offset`, exact Bareiss elimination over `Rational` for `Sum`) to get expected values and derivable unknowns. Phase B compares every `Observation(dim, value, precision, kind, source)` with its expected value and groups same-dimension spreads. Row provenance is a bitset of constraint ids so an inconsistent row names its contributors. No inequalities, no geometry kernel. Output classes: confirmed (>= 2 independent sources), single-source, conflict, derivable, under-constrained, not-representable. Dimension kinds: `length` (precision tolerance) and `count` (equality, no precision).

Heterogeneous source keys reach a dimension only through the project's `DimensionRegistry` (Story 1.7): table `(row key, header)`, yaml path, svg label hint, svg geometry rule, prose anchor, and optional derivations such as `radius*2`. An unmapped item is `NeedsMapping`, never guessed.

## Independence and self-exclusion rule
Two observations are independent only if their `ref` differ after normalisation. Exclusion of our own output is at TWO levels (the digest is now a whole owned page, ADR-006): (1) page level: every extractor skips any page whose page properties OR first block include `stelekit-digest::` (the Active writer round trip may demote page properties to a first block, ADR-006); (2) block level: any block carrying `stelekit-digest::` is skipped. YAML comments that restate the YAML value share the scalar's single `ref` (same source). Excluded observations are counted (`excludedSelf=N`).

## Mirrored view (D1)
The `mirrored-view` warning is derived from data, not prose: when the zone order from table/yaml is the reverse of the SVG label x-order after matching labels to zones by value, the result carries the warning instead of a numeric conflict.

## Consequences
500 variables sparse: bounded by a pivot-operation count asserted in `FusionScaleTest`; wall time is a reported bench metric only.
