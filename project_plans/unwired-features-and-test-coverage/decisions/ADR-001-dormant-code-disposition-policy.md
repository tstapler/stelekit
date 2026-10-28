# ADR-001: Dormant code is wired when it adds functionality and deleted only when superseded

**Status**: Accepted (owner decision 2026-10-27) | **Date**: 2026-10-27

## Context
A name-based scan found types reachable only from their own file or tests. Wired-in tests of a stub prove nothing (`DatalogEngine.execute` returns `emptyList()`; `CloudClaudePlugin.processAsset` returns `Labels(emptyList())`). Deleting all of it would discard planned asset-pipeline and cloud work.

## Decision
- Every dormant item gets exactly one disposition in the plan's Disposition Register, from one vocabulary shared with the plan's Glossary: **Wire** (names the functionality it adds and what must be built so it is not a stub; qualifiers rebuild, build out, adopt), **DeleteSuperseded** (names the live class that replaces it, with a file or line citation), **OwnerDecision** (names the cost of each path), or **Keep** (qualifiers: false positive, verify, out of scope (iOS); recorded in `wiring-allowlist.txt`, where the iOS pair uses the non-expiring reason class `out-of-scope-ios` and every other entry expires after 90 days).
- Two conditional fallbacks can turn a Wire or Keep(verify) row into a deletion at implementation time (`CommandManagerCoordinator` in Task 7.4a, unlaunched benchmarks in Task 7.5a); each is an OD-5 row that needs confirmation before it deletes anything.
- Deleting requires a named superseding class or a cited absence of any consumer plus a cited replacement of function; "unused" alone is not sufficient.
- Every deletion is an `OwnerGate`: the PR description carries the owner's confirmation. Deletions follow the mechanics of `editor-subsystem-dead-code`.
- A wired feature is not done until its `WiringCatalog` entry names an entry-point test through the real composition root that asserts an observable effect (ADR-002), its Epic 12 owner device pass is recorded, and its flag-default decision is written down (on, or off with a dated reason); the Register's last column tracks this.

## Alternatives rejected
Delete all unreferenced code (loses planned features, contradicts owner decision); wire everything (wires stubs and duplicates live query and sync code); leave dormant code and rely on the allowlist (the allowlist expires after 90 days by design).

## Consequences
Six items need owner input (OD-1..OD-6; OD-6 schedules the unfunded dev-plan PR-A1/PR-A2); the rest proceed. The register is the single place a future reader learns why something was kept or removed.
