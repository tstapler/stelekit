# Branding: Source-Available License

**Epic**: Add a source-available license that is free for any use (including commercial) and prohibits commercial forking  
**Status**: Planned  
**ADR**: [ADR-002: License Selection](../../project_plans/stelekit/decisions/ADR-002-license-selection.md)

---

## Background

The project currently has no `LICENSE` file. The brand strategy specifies:

> Source-available — free to use (including commercially); commercial forking and resale prohibited.  
> Selected license: Elastic License 2.0 (ELv2)

Without a license, the project is legally "all rights reserved" by default, which actively discourages contributors and users. A clearly chosen source-available license signals intent, protects the project's identity, and aligns with the "personal knowledge management, free for personal use" positioning.

---

## License Candidates

### Option A: PolyForm Noncommercial 1.0.0

**What it does**: Permits any use except commercial use. "Commercial use" means using the software to earn revenue or to support a commercial business.

**Pros**:
- Simple, purpose-built for "free for personal use" positioning
- Well-understood by open source lawyers; drafted by reputable attorneys
- Single file, no additional terms needed
- Brand strategy already names this as the front-runner
- Compatible with developers contributing to non-commercial forks

**Cons**:
- "Noncommercial" boundary can be ambiguous for freelancers or small businesses using it internally
- Does not have a "convert to open source after N years" provision (unlike BUSL)
- Less name recognition than Apache or MIT in the developer community

### Option B: Business Source License 1.1 (BUSL)

**What it does**: Source-available with a "Change Date" (typically 4 years) after which the code converts to an open source license (usually Apache 2.0). Commercial use before the Change Date requires a commercial license from the author.

**Pros**:
- Precedent: used by MariaDB, CockroachDB, HashiCorp Vault
- Automatically becomes open source after the Change Date — signals long-term openness
- Clear commercial vs. non-commercial boundary based on production use

**Cons**:
- Requires specifying a "Change Date" and "Change License" — ongoing maintenance burden
- More complex text; users must understand the time-based conversion
- Overkill for a personal PKM tool (designed for infrastructure/SaaS businesses)
- The "production use" framing doesn't map cleanly to a desktop app

### Option C: Apache 2.0 + Commons Clause

**What it does**: Apache 2.0 with an appended "Commons Clause" that prohibits selling the software or selling a service whose value primarily derives from the software.

**Pros**:
- Apache 2.0 is well-understood; the Commons Clause adds a targeted restriction
- Used by Redis (prior to their own license), Confluent

**Cons**:
- Not OSI-approved; the Commons Clause is explicitly called out as a non-open-source restriction
- Legal ambiguity around what "primarily derived from" means
- Less respected by the developer community — seen as misleading ("it's called Apache but isn't")
- Some package registries and CI services have policies against Commons Clause software

---

## Recommendation

**Use Elastic License 2.0 (ELv2).**

The intent is "free to use for any purpose including commercially, but you cannot fork and sell it." PolyForm Noncommercial would block all commercial use (users couldn't even run it at work) — that is too restrictive. ELv2 permits all use, but prohibits sublicensing, selling, or providing as a hosted/managed service. It is short, plain-English, and widely understood in the developer community.

See [ADR-002](../../project_plans/stelekit/decisions/ADR-002-license-selection.md) for the full decision record.

---

## Implementation Plan

### Story 1: Add the LICENSE File

#### Task 1.1 — Create LICENSE File
- Files: `LICENSE` (root)
- Copy the full Elastic License 2.0 text from https://www.elastic.co/licensing/elastic-license
- Fill in the licensor name: "Tyler Stapler" (or the project legal entity if one exists)
- Effort: 30 minutes

### Story 2: Add License Headers to Key Source Files

Rationale: Copyright headers are optional under PolyForm NC (the license file governs the whole repo), but headers in key files serve as a clear signal and deter accidental misuse.

#### Task 2.1 — Add Header to Core Entry Points
- Files (4–5 files):
  - `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt`
  - `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt`
- Header format:
  ```
  // Copyright (c) 2026 Tyler Stapler
  // SPDX-License-Identifier: Elastic-2.0
  // https://www.elastic.co/licensing/elastic-license
  ```
- Effort: 30 minutes

### Story 3: Update Project Metadata

#### Task 3.1 — Add License Field to build.gradle.kts
- Files: `kmp/build.gradle.kts`
- If the file uses any `publishing {}` or `pom {}` block, add `licenses { license { name = "PolyForm Noncommercial 1.0.0" } }`
- If not, add a comment at the top of the file: `// License: PolyForm Noncommercial 1.0.0`
- Effort: 15 minutes

#### Task 3.2 — Update settings.gradle.kts Project Name (Optional Scope Check)
- Files: `settings.gradle.kts`
- Current: `rootProject.name = "logseq"` — this is the Gradle project name, not a user-visible string, but it affects artifact names (e.g., `logseq-kmp.jar`)
- Decision point: rename to `"stelekit"` here, or leave for a dedicated rename task
- Note: changing `rootProject.name` renames all build output artifacts and may affect CI scripts. Treat as a separate decision; document in this file but do not block license work on it.
- Effort: 15 minutes (if decided to do it)

---

## File Change Summary

| File | Change Type |
|---|---|
| `LICENSE` | New file |
| `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt` | Add header comment |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` | Add header comment |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/theme/Theme.kt` | Add header comment |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphManager.kt` | Add header comment |
| `kmp/build.gradle.kts` | Add license metadata comment |

---

## Known Issues

### Legal Uncertainty: Contributors
PolyForm NC does not include a Contributor License Agreement (CLA). Contributors who submit PRs are implicitly licensing their contributions under the same PolyForm NC terms (by GitHub's terms of service). This is sufficient for a personal project but worth noting if the project grows a significant contributor base.

**Mitigation**: A short `CONTRIBUTING.md` note stating "By contributing you agree your code is licensed under PolyForm Noncommercial 1.0.0" is sufficient for now.

### Risk: Logseq Format Compatibility Notes
Several files reference "Logseq graph format" in comments or strings. These are references to a file format, not a trademark claim, and are unaffected by the license choice. They do not need to be changed as part of this task.

### Risk: settings.gradle.kts rootProject.name
The Gradle project name `"logseq"` is embedded in build outputs. If this is changed without updating CI and any deployment scripts, builds may break. This is deferred to a dedicated rename task and is out of scope for the license task.

---

## Success Criteria

- `LICENSE` file exists at the root of the repository
- SPDX identifier `PolyForm-Noncommercial-1.0.0` is present in at least the 4 key source files
- `./gradlew :kmp:compileKotlinJvm` still passes (no functional code changes)
- The license is referenced in `CONTRIBUTING.md` or a note is added to `README.md` once that file exists
- The license correctly permits commercial *use* while prohibiting commercial resale/hosting
