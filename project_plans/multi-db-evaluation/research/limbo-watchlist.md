# Limbo / Turso Watchlist

**Evaluation date**: 2026-06-19
**Re-check target**: 2026-12-19 (6 months)

---

## Current Status

As of 2026-06-19, Turso (the upstream project formerly called Limbo) is in **beta**:

- FTS5 support is **experimental** — not declared stable in any release
- No Android ABI artifacts (`arm64-v8a`, `x86_64`) published to Maven Central or Turso's artifact registry as a stable release
- No iOS Kotlin/Native cinterop path documented or tested; no published `.xcframework`
- A JDBC binding exists at `bindings/java/` in the repo but is **not** available on Maven Central as a stable release

SteleKit cannot adopt Turso today. All four adoption blockers below must clear simultaneously before a migration spike is warranted.

---

## Adoption Blockers

All four must be true at the same time before starting a migration:

- [ ] **Stable 1.0 release** with explicit "FTS5 stable" in the release notes (not just planned or experimental)
- [ ] **Android ABI artifacts** (`arm64-v8a`, `x86_64`) published to Maven Central or Turso's artifact registry as a stable release
- [ ] **iOS path verified**: Kotlin/Native cinterop path documented and tested, or a published `.xcframework` that works with the SQLDelight `NativeSqliteDriver` pattern
- [ ] **`COMPAT.md` confirms FTS5 compatibility**: `bm25()`, `highlight()`, and all three FTS5 trigger forms used by `SteleDatabase.sq` are fully compatible (`snippet()` is NOT required — it is not used in the schema)

Check `COMPAT.md` at: https://github.com/tursodatabase/turso/blob/main/COMPAT.md

---

## Migration Path

When all four blockers are cleared, the estimated migration effort is **~1 week**:

1. Replace `LibsqlJni.kt` (Rust JNI bridge) with a Turso JDBC wrapper using `asJdbcDriver()` (~50 LOC). `LibsqlDriverCore.kt` in `jvmCommonMain` would be replaced or adapted; the `SqlDriver by core` delegation pattern makes this swap surgical.
2. Replace `JvmLibsqlDriver` and `AndroidLibsqlDriver` factory calls at all driver creation sites.
3. **No schema changes required**: `SteleDatabase.sq`, all migrations in `MigrationRunner`, and all SQLDelight queries use the same SQLite dialect and file format — they are compatible with Turso as-is.
4. Run the full test suite (`./gradlew ciCheck`) to confirm correctness.
5. Benchmark write throughput against the libsql JNI baseline to confirm no regression.

---

## Next Check Date

**2026-12-19** — Check the Turso GitHub releases page for a 1.0 stable release with FTS5 stable and published Android/iOS artifacts.

---

## Links

- Main repo: https://github.com/tursodatabase/turso
- Compatibility tracker: https://github.com/tursodatabase/turso/blob/main/COMPAT.md
- JDBC binding (in-repo, not yet on Maven Central): `bindings/java/` in the repo above
