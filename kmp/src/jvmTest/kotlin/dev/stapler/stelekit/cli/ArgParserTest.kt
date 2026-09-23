// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [parseArgs].
 *
 * Each test covers a distinct branch in the parser: help flag, missing argument,
 * mutually-exclusive flag combinations, unknown flags, and a fully-valid invocation.
 */
class ArgParserTest {

    // ── TC-1: --help exits with code 0 and usage text ────────────────────────

    /**
     * TC-1: Passing `--help` must throw [ArgParseException] with [exitCode] == 0 and
     * a message that contains usage information (the word "Usage" and/or "--graph").
     */
    @Test
    fun `--help throws ArgParseException with exitCode 0 and usage message`() {
        val ex = assertFailsWith<ArgParseException> {
            parseArgs(arrayOf("--help"))
        }

        assertEquals(0, ex.exitCode, "--help must produce exitCode 0")
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("Usage", ignoreCase = true) || msg.contains("--graph"),
            "Help message must contain 'Usage' or '--graph'; got: $msg",
        )
    }

    // ── TC-2: --graph without a path argument exits with code 5 ──────────────

    /**
     * TC-2: `--graph` with no following token must throw [ArgParseException] with
     * [exitCode] == 5. Supplying `--graph` as the last argument exercises the bounds check
     * (`i + 1 >= args.size`).
     */
    @Test
    fun `--graph without path argument throws ArgParseException with exitCode 5`() {
        val ex = assertFailsWith<ArgParseException> {
            parseArgs(arrayOf("--graph"))
        }

        assertEquals(5, ex.exitCode, "--graph missing path must produce exitCode 5")
    }

    // ── TC-3: --commit-only and --fetch-only together exit with code 5 ────────

    /**
     * TC-3: `--commit-only` and `--fetch-only` are mutually exclusive. Supplying both
     * must throw [ArgParseException] with [exitCode] == 5.
     */
    @Test
    fun `--commit-only and --fetch-only together throw ArgParseException with exitCode 5`() {
        val ex = assertFailsWith<ArgParseException> {
            parseArgs(arrayOf("--commit-only", "--fetch-only"))
        }

        assertEquals(5, ex.exitCode, "mutually-exclusive flags must produce exitCode 5")
    }

    // ── TC-4: --dry-run and --commit-only together exit with code 5 ──────────

    /**
     * TC-4: `--dry-run` cannot be combined with `--commit-only` or `--fetch-only`.
     * Supplying `--dry-run --commit-only` must throw [ArgParseException] with [exitCode] == 5.
     */
    @Test
    fun `--dry-run and --commit-only together throw ArgParseException with exitCode 5`() {
        val ex = assertFailsWith<ArgParseException> {
            parseArgs(arrayOf("--dry-run", "--commit-only"))
        }

        assertEquals(5, ex.exitCode, "--dry-run + --commit-only must produce exitCode 5")
    }

    // ── TC-5: unknown flag exits with code 5 ─────────────────────────────────

    /**
     * TC-5: Any flag that is not in the recognised set must throw [ArgParseException]
     * with [exitCode] == 5.
     */
    @Test
    fun `unknown flag throws ArgParseException with exitCode 5`() {
        val ex = assertFailsWith<ArgParseException> {
            parseArgs(arrayOf("--unknown-flag"))
        }

        assertEquals(5, ex.exitCode, "unrecognised flag must produce exitCode 5")
    }

    // ── TC-6: compatible flags parse successfully ─────────────────────────────

    /**
     * TC-6: All flags that are mutually compatible can appear together and must produce
     * a fully-populated [SyncArgs] with no exception.
     *
     * Compatible combination: `--graph <path> --dry-run --json`
     * (`--commit-only` and `--fetch-only` are excluded because they conflict with `--dry-run`)
     */
    @Test
    fun `compatible flags parse to SyncArgs without exception`() {
        val result = parseArgs(arrayOf("--graph", "/my/graph", "--dry-run", "--json"))

        assertEquals("/my/graph", result.graphPath)
        assertTrue(result.dryRun, "--dry-run must set dryRun = true")
        assertTrue(result.jsonOutput, "--json must set jsonOutput = true")
        assertFalse(result.commitOnly, "commitOnly must be false when not supplied")
        assertFalse(result.fetchOnly, "fetchOnly must be false when not supplied")
    }

    // ── TC-7: no arguments parses to all-default SyncArgs ────────────────────

    /**
     * TC-7: Supplying an empty argument array must return a [SyncArgs] with all defaults
     * (null graphPath, all boolean flags false).
     */
    @Test
    fun `empty args parses to default SyncArgs`() {
        val result = parseArgs(emptyArray())

        assertNull(result.graphPath, "graphPath must default to null")
        assertFalse(result.commitOnly)
        assertFalse(result.fetchOnly)
        assertFalse(result.dryRun)
        assertFalse(result.jsonOutput)
    }

    // ── TC-8: --dry-run and --fetch-only together exit with code 5 ────────────

    /**
     * TC-8: `--dry-run` cannot be combined with `--fetch-only` either.
     * Supplying `--dry-run --fetch-only` must throw [ArgParseException] with [exitCode] == 5.
     */
    @Test
    fun `--dry-run and --fetch-only together throw ArgParseException with exitCode 5`() {
        val ex = assertFailsWith<ArgParseException> {
            parseArgs(arrayOf("--dry-run", "--fetch-only"))
        }

        assertEquals(5, ex.exitCode, "--dry-run + --fetch-only must produce exitCode 5")
    }
}
