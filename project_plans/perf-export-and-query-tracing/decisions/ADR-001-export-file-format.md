# ADR-001: Export File Format — JSON vs SQLite Dump vs CSV

**Status**: Accepted  
**Date**: 2026-04-21  
**Deciders**: Tyler Stapler  

---

## Context

The perf export feature needs to write span and histogram data to a file that:

1. Can be read by an AI assistant (Claude) in a follow-up session without specialized tooling
2. Preserves the nested attribute map (`Map<String, String>`) on `SerializedSpan`
3. Is human-readable enough to be manually inspected if needed
4. Is producible from `commonMain` Kotlin using existing dependencies

Three options were considered:

### Option A: JSON (kotlinx.serialization)

`SerializedSpan` is already annotated `@Serializable`. `PercentileSummary` is already annotated `@Serializable`. A top-level `PerfExportReport` data class can be serialized with zero new dependencies using `kotlinx-serialization-json` which is already a transitive dependency of SQLDelight's KMP stack.

Pros:
- Zero new dependencies
- `SerializedSpan` and `PercentileSummary` already have `@Serializable` — no schema duplication
- Human-readable; Claude can reason about it without a SQL client
- Attribute maps serialize cleanly as JSON objects
- Universally parseable in any follow-up analysis tool

Cons:
- Larger than binary formats for very large span sets; mitigated by 10,000-span cap (~10–15MB raw, acceptable per NFR-2)
- No incremental query; full file must be loaded into memory at parse time

### Option B: SQLite DB file copy

Copy the `stelekit.db` file directly. Zero serialization code.

Pros: Complete fidelity; all tables available.

Cons:
- On Android, the DB is in app-private storage (`/data/data/…`). Copying it to Downloads requires `FileInputStream` + `FileOutputStream`, and sharing it externally exposes all user graph data (pages, blocks), not just perf data — a privacy and security risk.
- Claude cannot query an attached SQLite file without a tool that reads binary SQLite format. The `sqlite3` tool is not available in the Claude Code environment.
- Includes all graph content — the export is not scoped to perf data only.

### Option C: CSV (spans) + separate CSV (histograms)

Pros: Easily opened in spreadsheets.

Cons:
- `Map<String, String>` attributes cannot be naturally represented in flat CSV; requires an awkward serialization (JSON-in-cell or attribute explosion).
- Two files complicate the export UX (which path to share? which to load?).
- `kotlinx-serialization-csv` is not a first-party library; would add a new dependency.

---

## Decision

**Option A: JSON using `kotlinx.serialization`.**

The file format is a single JSON object at `~/Downloads/stelekit-perf-YYYY-MM-DD-HHmm.json` (desktop) or `Downloads/stelekit-perf-YYYY-MM-DD-HHmm.json` (Android shared storage).

Top-level structure:

```kotlin
@Serializable
data class PerfExportReport(
    val exportedAt: Long,
    val appVersion: String,
    val platform: String,
    val spans: List<SerializedSpan>,
    val histograms: Map<String, PercentileSummary>
)
```

This type lives in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/performance/PerfExportReport.kt`.

---

## Consequences

- `kotlinx-serialization-json` must be confirmed present in `kmp/build.gradle.kts` (it is, as a transitive of `sqldelight-runtime`; verify or add explicitly if needed).
- The `PerfExportReport` schema is the contract between SteleKit and AI analysis sessions. Any schema change is a breaking change for any saved export files — treat with the same discipline as the DB schema.
- Android export requires `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)` + direct `File` write, which requires `WRITE_EXTERNAL_STORAGE` on API ≤28 or uses the scoped-storage public-directory exemption on API ≥29. This is a platform concern isolated to `androidMain`.
