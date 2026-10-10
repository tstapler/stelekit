# Cross-Artifact Consistency Check — Graph Sections

**Date:** 2026-06-24
**Verdict:** READY (with one minor gap noted)

---

## 1. Requirements → Plan Coverage (FR-1 through FR-8)

| FR | Description | Plan Coverage | Status |
|----|-------------|---------------|--------|
| **FR-1** | Section Manifest (TOML, ktoml, `.stele-sections`) | Epic 1 / Stories 1.1–1.3 cover data model, parser, read/write, `SectionFilter`. FR-1.1–FR-1.5 all addressed. | COVERED |
| **FR-2** | Section-Selective Graph Loading | Epic 2 / Stories 2.1–2.3 cover `GraphLoader` filter at enumeration time, file-watcher scoping, page deletion on deactivation. FR-2.1–FR-2.5 all addressed. | COVERED |
| **FR-3** | Cross-Section Link Tombstones | Epic 4 / Stories 4.1–4.2 cover `SectionResolver`, `SectionIndex`, `TombstoneLink` component, `SyncSectionDialog`. FR-3.1–FR-3.3 addressed. | COVERED |
| **FR-4** | Sparse Checkout Support (Desktop JVM) | Epic 5 / Stories 5.1–5.2 cover `SparseCheckoutService`, JVM `ProcessBuilder` impl, no-op actuals, dirty-worktree guard, remote guard. FR-4.1–FR-4.6 all addressed. | COVERED |
| **FR-5** | WASM Local Cache (OPFS) | Epic 6 / Stories 6.1–6.4 cover manifest fetch, section selector, REST API tree fetch, delta sync, quota handling, credential flow. FR-5.1–FR-5.8 all addressed. | COVERED |
| **FR-6** | Section Settings UI | Epic 7 / Story 7.1 covers `SectionSettingsScreen`, `SectionSettingsViewModel`, create/edit dialog, delete confirmation. FR-6.1–FR-6.5 all addressed. | COVERED |
| **FR-7** | Section Indicator on Pages | Epic 7 / Stories 7.2 covers color dots and section filter dropdown. FR-7.1–FR-7.3 all addressed. | COVERED |
| **FR-8** | Full-Merge View (Primary Device) | FR-8.1 (no behavioral change when `activeSectionSet = null`) is enforced throughout plan (AC-5, `SectionManifest.DEFAULT`, backward-compat tests). FR-8.2 ("Section view" toggle in toolbar) is NOT explicitly implemented as a story. The filter dropdown (Story 7.2.2) covers per-section filtering but the plan does not include a dedicated toolbar "Section view" toggle as described in FR-8.2. | MINOR GAP |

**Summary:** FR-1 through FR-7 are fully covered. FR-8.2 (toolbar "Section view" toggle) has no dedicated story — the section filter dropdown in the sidebar (Story 7.2.2) may be the intended implementation, but the plan does not explicitly call out the toolbar toggle. This is a minor scope gap; the sidebar filter may satisfy the intent.

---

## 2. Plan → Requirements Alignment (Scope Drift Check)

Stories and epics in plan.md that go beyond or extend the requirements:

| Plan Addition | Assessment |
|---|---|
| **Epic 3 (Schema migration as a standalone epic)** | Derived from FR-2 (requires DB column). Not scope drift — implementation detail correctly separated. |
| **Story 6.4 (WASM credential management, PAT flow)** | Not explicitly in FR-5, but FR-5.2 implies REST API fetches which implicitly require auth for private repos. The plan correctly identifies this as a required addition. Minimal justified scope expansion. |
| **`SectionConfig.SparseCheckoutUnavailable` flag + info banner** | Extension of FR-4.6 (silently skip). The info banner is a UX improvement not in requirements. Acceptable — does not contradict requirements. |
| **Dirty worktree guard (Task 5.2.2)** | Not in FR-4 explicitly, but UX design (Surface 1, error cases) specifies this behavior. Correctly added. |
| **`RepoCredential` sealed class** | Supporting type for Story 6.4. Justified. |
| **Two-step confirmation for section delete (Task 7.1.4)** | FR-6.4 says "confirmation dialog" — the two-step requirement (type section name) is added by the plan. Minor extra friction not in requirements; acceptable UX hardening. |

**No problematic scope drift found.** All plan additions are either derived from implicit requirements, cover UX design details, or are implementation necessities.

---

## 3. Naming Consistency

Checked all domain type names against the requirement (NFR-8, FR-1 naming note):

| Type | Required Name | Status in plan.md |
|------|---------------|-------------------|
| Graph section partition | `GraphSection` | Used consistently throughout — Domain Glossary, Story 1.1.2, all epics. PASS |
| TOML manifest file | `SectionManifest` | Used consistently — Domain Glossary, Story 1.1.2, Tasks 1.2.x. PASS |
| Path-matching gate | `SectionFilter` | Used consistently — Domain Glossary, Story 1.3. PASS |
| UI/VM render-time resolver | `SectionResolver` | Used consistently — Domain Glossary, Epic 4. PASS |
| Platform sync component | `SectionSyncService` | Used consistently — Domain Glossary, Epic 6. PASS |
| Active section set | `ActiveSectionSet` (typed `Set<String>?`) | Domain Glossary defines it. Used correctly throughout. PASS |
| Name-to-section snapshot | `SectionIndex` | Domain Glossary, Tasks 4.1.1–4.1.3. PASS |
| Manifest file name | `.stele-sections` | Used consistently in all tasks and migration plan. PASS |
| Forbidden type name | `Namespace` (for this feature) | Plan explicitly calls out the prohibition. No Namespace types introduced for this feature's domain. PASS |

**Naming is fully consistent.** No "Namespace" types appear for this feature's domain types.

---

## 4. UX → Plan Coverage

| UX Surface | Plan Coverage | Status |
|---|---|---|
| **Section Settings screen** (Surface 1) | Story 7.1 (Tasks 7.1.1–7.1.4) — `SectionSettingsViewModel`, `SectionSettingsScreen`, list with page counts, toggles, Edit/Delete buttons, Create FAB. All UX interaction flows covered. | COVERED |
| **Create/Edit Section dialog** (Surface 2) | Task 7.1.3 — `SectionFormDialog` with name, description, path patterns (add/remove), color picker, validation (no duplicates, path overlap warning). | COVERED |
| **WASM first-open section selector** (Surface 3) | Story 6.1, Tasks 6.1.2–6.1.3 — `SectionSelectorScreen` with per-section name/description/page count/color; confirm flow calls `syncSection` per selected section; progress state via `onProgress` callback. | COVERED |
| **Tombstone link visual treatment** (Surface 4) | Task 4.2.1 — `TombstoneLink` composable with dashed underline, section color tint, tooltip. Task 4.1.4 — wires `SectionResolver` into wikilink rendering path. | COVERED |
| **"Sync section?" dialog** (Surface 5) | Task 4.2.2 — `SyncSectionDialog` composable with section name/description, Confirm/Cancel, calls `GraphManager.toggleSection`. UX specifies desktop vs WASM variants; plan's `SyncSectionDialog` is platform-neutral but `toggleSection` routes to correct backend (OPFS vs sparse-checkout). Minor gap: the UX Desktop variant shows sparse-checkout bullet points, WASM variant shows size/OPFS copy — the plan's `SyncSectionDialog` composable does not explicitly mention platform-variant text. Acceptable because the UI text can reference the underlying `SectionConfig.SparseCheckoutUnavailable` flag at implementation time. | COVERED (with minor implementation note) |
| **Sidebar section indicator** (Surface 6) | Story 7.2, Tasks 7.2.1–7.2.2 — color dots in page list rows, section filter dropdown. | COVERED |
| **Error states** (Surface 7) | — Git not found: UX-AC-36/04, plan references `SparseCheckoutUnavailable` + info banner. Git merge conflict: Story 5.2 Task 5.2.2 (dirty-worktree guard). OPFS rate-limit 429: Task 6.2.3 handles `QuotaExceededError`; rate-limit (HTTP 429) is NOT explicitly handled in plan tasks — UX-AC-38 requires countdown auto-retry but no plan task covers 429 specifically. OPFS 401/403: Story 6.4 Task 6.4.2 covers `DomainError.CredentialRequired` on 401/403. Storage quota exceeded: Task 6.2.3. OPFS eviction: Story 6.3 Task 6.3.2 (offline fallback + re-download). Manifest parse error: Not explicitly handled in plan — UX-AC-42/43 specify showing a parse-error dialog with line/column info, but no plan task implements this error dialog. | MINOR GAPS: HTTP 429 handling + manifest parse error dialog |

**UX gaps found:**
- **HTTP 429 / rate-limit from git host**: UX Surface 7.2.1 and UX-AC-38 require a countdown auto-retry dialog. No plan task implements 429 detection or the countdown/retry flow.
- **Manifest parse error dialog**: UX Surface 7.3 and UX-AC-42/43 require a specific error dialog (showing line/column of parse error, offering "Open .stele-sections" action, "Continue with default"). No plan task implements this dialog. The plan falls back to `SectionManifest.DEFAULT` on file-absent but does not surface parse errors to the user.

---

## 5. Research / ADR → Plan Alignment

| ADR / Research Claim | Specified in Plan | Status |
|---|---|---|
| **ADR-011**: TOML library = `ktoml-core:0.7.1` | Task 1.1.1 explicitly: "Add `ktoml-core:0.7.1` to `commonMain` dependencies … add `ktoml-file:0.7.1` to `jvmMain` and `androidMain`." Pattern Decisions table confirms. | MATCH |
| **ADR-012**: System git via `ProcessBuilder` (not JGit) | Task 5.1.2 explicitly: JVM implementation uses `ProcessBuilder`. Pattern Decisions table cites Eclipse Bug #383772 as reason to reject JGit. | MATCH |
| **ADR-013**: REST API + OPFS (not isomorphic-git) | Task 6.2.1 uses `GET /repos/.../git/trees/{sha}?recursive=1` + OPFS writes. Pattern Decisions table explicitly rejects `isomorphic-git`. | MATCH |
| `ktoml-core:0.7.1` version pinned | Task 1.1.1. | MATCH |
| `ProcessBuilder` for sparse-checkout | Task 5.1.2. | MATCH |
| REST API (not isomorphic-git), OPFS | Tasks 6.2.1–6.2.2. | MATCH |

**All ADR choices match plan technology decisions.**

---

## 6. CLAUDE.md Constraints → Plan

| Constraint | Plan Compliance | Status |
|---|---|---|
| **Every new `CREATE TABLE IF NOT EXISTS` in `MigrationRunner.all`** | Epic 3 / Story 3.1 / Task 3.1.2 explicitly adds a versioned `Migration` entry to `MigrationRunner.all`. Task 3.1.3 verifies `MigrationRunnerSchemaSyncTest` passes. The plan explicitly warns "Do NOT rely on SQLite swallowing duplicate-column errors." | COMPLIANT |
| **`@DirectSqlWrite` for new mutating SQL methods** | Task 2.3.1 adds `deletePagesInPaths` (DELETE). Task 2.2.3 adds `getPagesBySection` (SELECT — read, not write). The plan does not explicitly mention `@DirectSqlWrite` for new write methods. `deletePagesInPaths`, `insertPage`/`updatePage` for `section_name` column, and the new `INSERT`/`UPDATE` queries will need this annotation. The plan says routes through `DatabaseWriteActor` (Task 2.3.2 uses `pageRepository.deletePagesInPaths`) which implies `@DirectSqlWrite` on the repository impl. **The plan does not explicitly call out adding `@DirectSqlWrite` annotations on the new write stubs.** This is a documentation gap in the plan — an implementer could miss it. | MINOR GAP (documentation only — not a design flaw) |
| **`PlatformDispatcher.DB` for database reads/writes** | Task 2.2.3 and Story 2.2 follow existing patterns (`getPagesBySection` as paginated bounded flow). Task 2.3.1 is a `suspend fun deletePagesInPaths` — plan does not explicitly specify `withContext(PlatformDispatcher.DB)` for this write, but this is implied by CLAUDE.md write pattern. The `SparseCheckoutService` correctly specifies `PlatformDispatcher.IO` for `ProcessBuilder` calls (Task 5.1.2, with explicit `withContext(PlatformDispatcher.IO)` inside the method body per Round 2 Blocker A fix). | MOSTLY COMPLIANT — implementer must apply DB dispatcher to `deletePagesInPaths` per CLAUDE.md write pattern |
| **No `rememberCoroutineScope` leaking into remembered classes** | `SectionSettingsViewModel` (Task 7.1.1) owns its own `CoroutineScope` internally — consistent with CLAUDE.md rules. No task passes `rememberCoroutineScope()` into a `remember { }` class. | COMPLIANT |
| **No unbounded reads** | Task 4.1.2 explicitly uses a projection-only query (`SELECT name, section_name FROM pages WHERE section_name = ?`) — not `List<Page>`. Task 2.2.3 specifies `getPagesBySection` as paginated. Task 2.3.1 uses `WHERE file_path LIKE ?` (per-path-prefix batched deletes). No task introduces an unbounded full-table read. | COMPLIANT |

---

## Summary of Gaps

| ID | Severity | Gap | Location |
|----|----------|-----|----------|
| G-1 | Minor | FR-8.2 toolbar "Section view" toggle has no dedicated plan story. Sidebar filter (Story 7.2.2) may satisfy intent but is not explicitly mapped. | Requirements FR-8.2 vs plan |
| G-2 | Minor | HTTP 429 / rate-limit from git host: no plan task covers countdown auto-retry (UX-AC-38). | UX Surface 7.2.1 vs Epic 6 |
| G-3 | Minor | Manifest parse error dialog: no plan task implements the parse-error UI with line/column info and "Open .stele-sections" action (UX-AC-42/43). Plan falls back to DEFAULT silently. | UX Surface 7.3 vs plan |
| G-4 | Minor (doc) | Plan does not explicitly call out `@DirectSqlWrite` annotation requirement for new write methods (`deletePagesInPaths` stub in `RestrictedDatabaseQueries`). Implementer must infer from CLAUDE.md. | CLAUDE.md constraint vs Epic 2/3 tasks |
| G-5 | Minor (doc) | `SyncSectionDialog` platform-variant text (desktop sparse-checkout bullets vs WASM download/size copy per UX Surface 5) not specified in Task 4.2.2. | UX Surface 5 vs Task 4.2.2 |

---

## Verdict

**READY** — The plan covers all 8 functional requirements at the story level, uses consistent `GraphSection`/`SectionManifest`/`SectionResolver`/`SectionSyncService`/`ActiveSectionSet`/`SectionIndex`/`.stele-sections` naming with no `Namespace` type leakage, matches all three ADR technology choices exactly, and respects CLAUDE.md constraints (migration runner, bounded reads, dispatcher rules, no scope leakage).

Three minor UX coverage gaps (G-1, G-2, G-3) are present — none are design flaws or blockers, but they should be addressed with lightweight tasks before implementation of Epics 6–7:

- **G-1**: Add a note to Story 7.2.2 explicitly mapping it to FR-8.2 (or add a minimal toolbar toggle task).
- **G-2**: Add a Task 6.2.4 to handle HTTP 429 with exponential backoff and countdown UI.
- **G-3**: Add a Task 6.1.4 (or 1.2.4) to implement the manifest parse error dialog with parse-error line/column and fallback-to-default behavior.
- **G-4/G-5**: Documentation-only; no structural change needed.
