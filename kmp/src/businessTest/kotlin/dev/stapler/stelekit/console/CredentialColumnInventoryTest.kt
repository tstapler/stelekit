// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.wiring.WiringInputs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 3.2: the console cannot deny reads of credential-alias columns (no SQLite authorizer is
 * reachable, Spike 0.3e = NO), so the honest control is an inventory: every credential-shaped column
 * is listed in `console-credential-columns.txt`, and a new one fails the build until a person has
 * looked at what it holds.
 */
class CredentialColumnInventoryTest {
    private val schema: String by lazy {
        javaClass.classLoader.getResourceAsStream("SteleDatabase.sq")?.bufferedReader()?.readText()
            ?: System.getProperty("stelekit.sq.file")?.let { File(it).readText() }
            ?: error("SteleDatabase.sq not found as a classpath resource or via -Dstelekit.sq.file")
    }

    private val credentialShaped = Regex("(_key$|token|secret|password|passphrase|credential)", RegexOption.IGNORE_CASE)
    private val tableStart = Regex("""^\s*CREATE TABLE(?: IF NOT EXISTS)?\s+(\w+)\s*\(""", RegexOption.IGNORE_CASE)
    private val constraintWords = setOf("primary", "foreign", "unique", "check", "constraint")

    private fun schemaColumns(): List<Pair<String, String>> {
        val found = mutableListOf<Pair<String, String>>()
        var table: String? = null
        for (line in schema.lines()) {
            val start = tableStart.find(line)
            when {
                start != null -> table = start.groupValues[1]
                table != null && line.trimStart().startsWith(")") -> table = null
                table != null -> {
                    val word = line.trim().takeWhile { it.isLetterOrDigit() || it == '_' }
                    if (word.isNotEmpty() && word.lowercase() !in constraintWords) found += table to word
                }
            }
        }
        return found
    }

    private fun committed(): Set<String> = WiringInputs.dataFile("console-credential-columns.txt").lines()
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    @Test
    fun credentialColumns_should_FailBuild_When_NewKeyTokenSecretColumnOutsideCommittedList() {
        val shaped = schemaColumns().filter { (_, column) -> credentialShaped.containsMatchIn(column) }.map { (t, c) -> "$t.$c" }.toSet()
        assertTrue(shaped.isNotEmpty(), "schema parse found no credential-shaped column; the parser is broken, not the schema")
        val unlisted = shaped - committed()
        assertTrue(
            unlisted.isEmpty(),
            "Credential-shaped columns not in console-credential-columns.txt: $unlisted. Confirm they hold aliases, not secret " +
                "values (secrets belong in CredentialStore), then list them with that note.",
        )
        val stale = committed() - shaped
        assertTrue(stale.isEmpty(), "console-credential-columns.txt lists columns the schema no longer has: $stale")
    }

    @Test
    fun committedList_should_ContainExactlyTheThreeGitConfigAliasColumns_And_SayTheyHoldAliases() {
        assertEquals(
            setOf("git_config.ssh_key_passphrase_key", "git_config.https_token_key", "git_config.oauth_token_key"),
            committed(),
        )
        val text = WiringInputs.dataFile("console-credential-columns.txt")
        assertTrue(text.contains("KEYSTORE ALIASES"), "the list must record that these columns hold aliases")
        assertTrue(text.contains("CredentialStore"), "the list must say where the secret values live")
    }
}
