// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import dev.stapler.stelekit.db.DriverFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Spike 0.3e, JVM half: is `sqlite3_set_authorizer` (or an equivalent column-deny hook) reachable on xerial
 * `sqlite-jdbc`? Records YES/NO. The assertions pin the observed NO so a sqlite-jdbc upgrade that adds an authorizer
 * fails here and forces a re-run of the credential-denial decision (plan Story 3.2). The Android half is
 * `AuthorizerSpikeAndroidTest`.
 */
class AuthorizerSpikeTest {

    private val spike = "0.3e-jvm"

    private val credentialReads = listOf(
        "SELECT * FROM git_config",
        "WITH c AS (SELECT * FROM git_config) SELECT * FROM c",
        "SELECT https_token_key AS x FROM git_config",
        "SELECT x FROM (SELECT https_token_key AS x FROM git_config)",
        "SELECT a.oauth_token_key FROM git_config a JOIN git_config b ON a.graph_id = b.graph_id",
    )

    @Test
    fun spike0_3e_should_RecordNoAuthorizerHook_When_InspectingSqliteJdbcApiAndNativeLibrary() {
        assertFailsWith<ClassNotFoundException> { Class.forName("org.sqlite.Authorizer") }
        val nativeDb = Class.forName("org.sqlite.core.NativeDB")
        val authorizerish = (nativeDb.declaredMethods + Class.forName("org.sqlite.core.DB").declaredMethods)
            .map { it.name }.filter { it.contains("auth", ignoreCase = true) || it.contains("deny", ignoreCase = true) }
        SpikeSupport.record(spike, "public_or_reflective_authorizer_methods", authorizerish)
        assertTrue(authorizerish.isEmpty(), "sqlite-jdbc now exposes an authorizer-like method: $authorizerish. Re-run the credential-denial decision.")

        val libPath = "/org/sqlite/native/Linux/x86_64/libsqlitejdbc.so"
        val bytes = AuthorizerSpikeTest::class.java.getResourceAsStream(libPath)?.use { it.readBytes() }
        if (bytes == null) {
            SpikeSupport.record(spike, "native_lib_symbol_scan", "SKIPPED: $libPath not on classpath (non-Linux-x86_64 host)")
        } else {
            val hasSymbol = String(bytes, Charsets.ISO_8859_1).contains("sqlite3_set_authorizer")
            SpikeSupport.record(spike, "native_lib_symbol_scan", "$libPath (${bytes.size} bytes) contains 'sqlite3_set_authorizer': $hasSymbol")
            assertEquals(false, hasSymbol)
        }

        val dir = SpikeSupport.tempDir("spike03e")
        try {
            val c = SpikeSupport.readWriteConnection(dir.resolve("x.db"))
            val options = c.createStatement().use { st -> st.executeQuery("PRAGMA compile_options").use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() } }
            c.close()
            SpikeSupport.record(spike, "compile_options_mentioning_auth", options.filter { it.contains("AUTH", ignoreCase = true) })
            SpikeSupport.record(spike, "sqlite_version", SpikeSupport.readWriteConnection(dir.resolve("x.db")).use { SpikeSupport.scalarString(it, "SELECT sqlite_version()") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3e_should_RecordNoDenial_When_CredentialColumnsAreReadFiveWays() {
        val dir = SpikeSupport.tempDir("spike03e-read")
        try {
            val db = dir.resolve("app.db")
            val app = DriverFactory().createDriver(SpikeSupport.url(db))
            app.execute(
                null,
                "INSERT INTO git_config(graph_id, repo_root, https_token_key, oauth_token_key, ssh_key_passphrase_key) " +
                    "VALUES ('g1','/r','git_https_token_g1','git_oauth_token_g1','git_ssh_pass_g1')",
                0,
            ).value
            val ro = SpikeSupport.readOnlyConnection(db)
            val rowCounts = credentialReads.map { sql ->
                ro.createStatement().use { st -> st.executeQuery(sql).use { rs -> var n = 0; while (rs.next()) n++; n } }
            }
            SpikeSupport.record(spike, "credential_read_rows_per_variant", rowCounts)
            assertTrue(rowCounts.all { it == 1 }, "a variant was denied or empty: $rowCounts (an authorizer may now exist)")

            // Console handle independence: abandoning it leaves the app's pooled connections usable.
            ro.close()
            repeat(16) { app.execute(null, "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('p$it','p$it',1,1)", 0).value }
            SpikeSupport.record(spike, "console_handle_independent_of_createReadDriver", "JVM createReadDriver=${DriverFactory().createReadDriver(SpikeSupport.url(db))}; app pool writes after console close: 16 ok")
            app.close()
        } finally {
            dir.deleteRecursively()
        }
    }
}
