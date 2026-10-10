package dev.stapler.stelekit.db.sqlite

/**
 * Declares the SQLite feature set provided by a given [SqliteDriverProvider].
 *
 * Capabilities are declared **statically** by each provider — not probed at runtime —
 * to avoid cold-start overhead and threading complexity. [dev.stapler.stelekit.testing.SqliteCapabilityTest]
 * (CI only) validates that the declared capabilities match actual runtime behavior.
 *
 * Compare a provider's capabilities against the application's requirements:
 * ```kotlin
 * val missing = provider.capabilities.missingFor(STELEKIT_REQUIRED_CAPABILITIES)
 * if (missing.isNotEmpty()) log.warning("Missing SQLite capabilities: $missing")
 * ```
 */
data class SqliteCapabilities(
    val hasFts5: Boolean,
    val hasWal: Boolean,
    val hasJson1: Boolean,
    val hasPorterTokenizer: Boolean,
    val hasWithoutRowid: Boolean,
    val hasRecursiveCte: Boolean,
) {
    /** Returns the names of required capabilities this instance is missing. */
    fun missingFor(required: SqliteCapabilities): List<String> = buildList {
        if (required.hasFts5 && !hasFts5) add("FTS5")
        if (required.hasWal && !hasWal) add("WAL")
        if (required.hasJson1 && !hasJson1) add("JSON1")
        if (required.hasPorterTokenizer && !hasPorterTokenizer) add("porter tokenizer")
        if (required.hasWithoutRowid && !hasWithoutRowid) add("WITHOUT ROWID")
        if (required.hasRecursiveCte && !hasRecursiveCte) add("WITH RECURSIVE CTE")
    }
}

/** All capabilities present — the bundled Requery SQLite 3.49+ profile. */
val FULL_CAPABILITIES = SqliteCapabilities(
    hasFts5 = true,
    hasWal = true,
    hasJson1 = true,
    hasPorterTokenizer = true,
    hasWithoutRowid = true,
    hasRecursiveCte = true,
)

/**
 * Capabilities of the Android system SQLite.
 *
 * FTS5 and JSON1 are only guaranteed from API 31+ (system SQLite ≥ 3.36 with these modules
 * compiled in). AOSP "default" emulator images at API 30 and below lack both.
 * WAL mode, WITHOUT ROWID, and WITH RECURSIVE are available since SQLite 3.8 (API 21+).
 */
val SYSTEM_SQLITE_CAPABILITIES = SqliteCapabilities(
    hasFts5 = false,
    hasWal = true,
    hasJson1 = false,
    hasPorterTokenizer = false,
    hasWithoutRowid = true,
    hasRecursiveCte = true,
)

/** The full set of SQLite capabilities required by SteleKit at runtime. */
val STELEKIT_REQUIRED_CAPABILITIES = FULL_CAPABILITIES
