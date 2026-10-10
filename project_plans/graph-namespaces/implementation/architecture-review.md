# Architecture Review: graph-namespaces

**Date**: 2026-06-24
**Reviewer**: Architecture Review Subagent
**Verdict**: CONCERNS (no hard blockers; 4 concerns; 6 nitpicks)

No ADR-000 (architecture constitution) found — constitution-violation section omitted.

---

## Blockers

None.

---

## Concerns

- [ ] **Story 2.2.2 / Task 2.2.2 — `SectionResolver` path-heuristic is fragile and may not be testable in isolation**
  `SectionResolver.resolveInactiveSection(pageName)` reconstructs a plausible file path from a page name (`pageName.replace(" ", "-") + ".md"`) and then prefix-matches against section paths. This is a naming-convention guess, not a lookup. Logseq actually supports multiple filename conventions (`%2F`-encoded slashes, triple-dot encoding, etc.) that `PageNameIndex` already handles. If the SectionResolver uses a different naming algorithm than `GraphLoader` uses to populate `Page.filePath`, it will produce false negatives (real tombstones rendered as broken links). **Recommendation**: instead of reconstructing the path from the name, store `section_name` in the DB (already planned) and look it up directly — `PageRepository.getPageByName(pageName)` already returns `Page?`, so when it returns null you cannot know the section from the DB. The fix: add a separate `getPageSectionName(pageName: String): String?` read to the `SqlDelightPageRepository` that queries `SELECT section_name FROM pages WHERE name = ? LIMIT 1`. Since inactive-section pages are *not in the DB* (FR-2.4), this returns null regardless — but the SectionResolver can then do manifest prefix-matching on the *name* string, which is a stable API, not on a reconstructed file path. This does not fully resolve the naming-convention fragility but is honest about what it can know. If the tombstone accuracy guarantee matters, the alternative is to store a lightweight mapping of inactive-section page names in a second table or a small OPFS sidecar file during `toggleSection` (before pages are deleted). This is the only design that gives `SectionResolver` accurate data about pages that no longer exist in the DB.

- [ ] **Story 2.1.1 / Task 2.1.1 — `GraphLoaderPort` signature change couples all callers**
  `GraphLoaderPort.loadGraphProgressive` is the public interface used by `StelekitViewModel`. Adding `activeSectionSet: Set<String>? = null` and injecting `sectionManifest` into the `GraphLoader` constructor (not via the port interface) splits the section-filtering configuration across two different injection points (constructor vs. call-site parameter). This violates ISP: the port becomes aware of section concerns even when the VM doesn't care. **Recommendation**: treat section configuration as constructor-time state injected once into `GraphLoader`, not as a per-call parameter. `GraphManager.switchGraph` / `toggleSection` reconstructs `GraphLoader` (or calls a `reconfigure(manifest, activeSectionSet)` method) when the active set changes. The port method signature stays unchanged. This is cleaner than threading `activeSectionSet` through every `loadGraphProgressive` call site.

- [ ] **Story 7.1.1 / Task 7.1.1 — `SectionSettingsViewModel` own-scope rule likely violated**
  The plan places `SectionSettingsViewModel` in `ui/viewmodel/sections/` and says it wraps `GraphManager.toggleSection`. The existing codebase mandates (CLAUDE.md: Coroutine scope ownership) that classes instantiated inside `remember { }` own their `CoroutineScope` internally. The plan does not address this — if the composable uses `rememberCoroutineScope()` to feed this VM (a common mistake when ad-hoc VMs are created for a screen), it will crash on recomposition. **Recommendation**: explicitly state in the task that `SectionSettingsViewModel` must own its own `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, and add a lint comment. This is not a showstopper but the plan's current silence is a trap for the implementer.

- [ ] **Story 2.3.1 / Task 2.3.1 — `deletePagesInPaths` uses `LIKE` with unbounded prefix scan, violating the bounded-read architecture rule**
  `DELETE FROM pages WHERE file_path LIKE ?` with a path prefix is a full-table scan on `file_path`. The `file_path` column has no index in the current schema (only `idx_pages_section_name` is being added). On an 8 000-row `pages` table this is O(N) per deleted section. Worse, `BlockRepository` and `references` table deletes must also cascade. The plan says "cascade-delete blocks and references for those pages via existing FK relationships" — but SQLite FK cascade is only active when `PRAGMA foreign_keys = ON`, which must be verified per platform (Android, WASM). If FK cascade is not enabled, the blocks and references orphan silently. **Recommendation**: (a) verify `PRAGMA foreign_keys = ON` is set in `DriverFactory.createDriver()` on all platforms; (b) add `idx_pages_file_path` to the migration if this path is used frequently; (c) or pivot to DELETE via `section_name` (already indexed) which is O(index lookup) — `DELETE FROM pages WHERE section_name = ?`.

---

## Nitpicks

- **`ActiveSectionSet` is `Set<String>?` not a newtype** — the plan acknowledges this in the glossary but does not define it as a `value class ActiveSectionSet(val names: Set<String>?)`. Using a raw `Set<String>?` at every call boundary means the compiler cannot distinguish "set of section names" from "set of page names" or "set of path strings". A `@JvmInline value class` (or plain `data class` for WASM compat) would close this hole. Low risk for v1 but worth noting.

- **`Page.section: GraphSection? = null` — null is a valid but ambiguous state** — `null` means "no manifest / default section / not yet resolved." The plan says existing pages after migration get `section_name = 'default'` from the SQL DEFAULT, so they are never null at the DB layer. However, `Page.section: GraphSection?` in the Kotlin model is null until the `SectionManifest` is consulted at read time. The plan relies on `SqlDelightPageRepository` doing this lookup at hydration time (Task 2.2.3). Make this explicit: `Page.section` should always be non-null post-hydration (the default section is a real `GraphSection`, not null). Consider `Page.section: GraphSection = GraphSection.DEFAULT` to make the always-non-null invariant compiler-enforced.

- **Task 1.1.2 — `SectionManifest.Companion.DEFAULT` as a property extension on `companion object`** — the plan shows `val SectionManifest.Companion.DEFAULT get() = ...`. This is non-idiomatic Kotlin; extension properties on companion objects require an explicit `companion object` declaration in the class. Since `SectionManifest` is `@Serializable`, verifying that `@Serializable` generates a companion object is required before using this pattern. Standard pattern: `companion object { val DEFAULT = SectionManifest(...) }` inside the class body.

- **Task 3.1.2 — MigrationRunner swallows "duplicate column name" errors** — the plan states this is per CLAUDE.md rules but CLAUDE.md does not actually document this behavior. Verify that `MigrationRunner.applyAll` does in fact catch `SQLiteException: duplicate column name` before shipping. If it does not, re-running migrations on a fresh DB (fresh install path where `CREATE TABLE IF NOT EXISTS` runs first, then migration runs) will throw and surface as a startup crash. The safer SQL is `SELECT COUNT(*) FROM pragma_table_info('pages') WHERE name='section_name'` before running the `ALTER TABLE`.

- **Story 4.1.1 — `LruCache` in `SectionResolver`** — `LruCache` is not in Kotlin stdlib or `kotlinx.collections`. The plan must specify which `LruCache` to use. On JVM there is `java.util.LinkedHashMap` for LRU; on Kotlin/Native and WASM there is none natively. If `SectionResolver` is in `commonMain` (as the plan implies), using a JVM `LruCache` is a compile error on WASM. Either (a) scope `SectionResolver` to JVM/Android and use `expect`/`actual`, or (b) provide a simple `LinkedHashMap`-based ring buffer in `commonMain`, or (c) just skip caching for v1 (the resolver is O(sections × paths) and section counts are typically < 10 — amortized cost is negligible without caching).

- **`SectionManifest.resolveActivePaths` helper (Task 2.1.2) is not defined in Epic 1** — the plan adds a `fun SectionManifest.resolveActivePaths(graphPath, activeSectionSet)` extension in `sections/SectionManifest.kt` but Epic 1 (the SectionManifest epic) does not include a task for this function. It is mentioned only in Task 2.1.2 (GraphLoader epic). If Epic 1 and Epic 2 are implemented by different developers or in parallel, this dependency will cause a compilation break mid-epic-2. Add a task to Epic 1 Story 1.3 (or a new Story 1.4) for `resolveActivePaths`.
