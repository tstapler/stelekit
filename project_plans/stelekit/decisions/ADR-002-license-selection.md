# ADR-002: License Selection — Elastic License 2.0 (ELv2)

**Date**: 2026-04-11  
**Status**: Proposed  
**Deciders**: Tyler Stapler  
**Context**: Stelekit branding workstream

---

## Context

Stelekit has no license file. The project's intent is:
- Free to use for any purpose, including commercially (use at work, in a business workflow, etc.)
- Commercial forking prohibited — someone cannot take this codebase, fork it, and sell it as a competing product or hosted service
- Source code publicly visible (open to contributions, inspection, and trust-building)
- Not OSI "open source" — this is intentional, not accidental

Four candidates were evaluated: Elastic License 2.0, PolyForm Shield 1.0.0, PolyForm Noncommercial 1.0.0, and Apache 2.0 + Commons Clause.

---

## Decision

**Elastic License 2.0 (ELv2)**

Full license text: https://www.elastic.co/licensing/elastic-license  
SPDX identifier: `Elastic-2.0`

---

## Rationale

The key requirement is: **free to use commercially, but you cannot fork and sell it**. PolyForm Noncommercial was initially considered but rejected because it prohibits *all* commercial use — a user could not run Stelekit at work or use it in a professional context. That is too restrictive and does not match the project's intent.

**ELv2** matches the intent precisely:
- Anyone can use Stelekit for any purpose, including commercially
- Nobody can provide Stelekit (or a fork) as a hosted/managed service or sell it as a product
- Nobody can circumvent license key or access-control features (future-proofing)
- The license is short (~350 words), plain-English, and widely understood in the developer community

**PolyForm Shield** was the other close candidate. It prohibits use that "competes with the licensor" — but "competes" requires legal interpretation and could create ambiguity. ELv2's prohibition is more concrete: you cannot sublicense, sell, or provide the software as a service.

PolyForm Noncommercial was rejected: it blocks commercial *use* entirely, which contradicts the intent.

Apache 2.0 + Commons Clause was rejected: the Commons Clause is legally imprecise and has poor reputation in the developer community.

---

## Consequences

- The repository can be publicly hosted on GitHub with source visible
- Any user (individual, business, enterprise) can use Stelekit freely
- Nobody can fork Stelekit and sell it or offer it as a managed service without permission from Tyler Stapler
- Contributors implicitly license contributions under ELv2 (no separate CLA required at this scale)
- The SPDX identifier `Elastic-2.0` should appear in source file headers and build metadata
- This license does not make the project "open source" by OSI definition — use "source-available" in the README
