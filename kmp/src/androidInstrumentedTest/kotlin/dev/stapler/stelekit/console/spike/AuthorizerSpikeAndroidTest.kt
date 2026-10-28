// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stapler.stelekit.console.spike.AndroidSpikeSupport.record
import dev.stapler.stelekit.db.DriverFactory
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Spike 0.3e, Android half: is an authorizer hook reachable on platform `android.database.sqlite` or the requery fork,
 * and is the console's `OPEN_READONLY` handle independent of `DriverFactory.createReadDriver`?
 * Assertions pin the observed NO so a requery upgrade that adds an authorizer forces a re-decision.
 */
@RunWith(AndroidJUnit4::class)
class AuthorizerSpikeAndroidTest {

    private val spike = "0.3e-android"

    private val credentialReads = listOf(
        "SELECT * FROM git_config",
        "WITH c AS (SELECT * FROM git_config) SELECT * FROM c",
        "SELECT https_token_key AS x FROM git_config",
        "SELECT x FROM (SELECT https_token_key AS x FROM git_config)",
        "SELECT a.oauth_token_key FROM git_config a JOIN git_config b ON a.graph_id = b.graph_id",
    )

    private fun rows(run: (String) -> android.database.Cursor): List<Int> =
        credentialReads.map { sql -> run(sql).use { it.count } }

    @Test
    fun spike0_3e_should_RecordNoAuthorizerHook_When_InspectingPlatformAndRequeryApiAndNativeLibs() {
        val dir = AndroidSpikeSupport.freshDir("auth")
        val db = dir.resolve("app.db")
        val app = AndroidSpikeSupport.appDriver(db)
        try {
            app.execute(
                null,
                "INSERT INTO git_config(graph_id, repo_root, https_token_key, oauth_token_key, ssh_key_passphrase_key) " +
                    "VALUES ('g1','/r','git_https_token_g1','git_oauth_token_g1','git_ssh_pass_g1')",
                0,
            ).value

            record(spike, "api_level", android.os.Build.VERSION.SDK_INT)

            // Requery fork: OPEN_READONLY handle.
            val rq = AndroidSpikeSupport.requeryReadOnly(db)
            val rqVersion = AndroidSpikeSupport.scalarString(rq, "SELECT sqlite_version()")
            val rqAuthOptions = rq.rawQuery("PRAGMA compile_options", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.filter { it.contains("AUTH", true) }.toList() }
            val rqMethods = authorizerish(io.requery.android.database.sqlite.SQLiteDatabase::class.java) +
                authorizerish(io.requery.android.database.sqlite.SQLiteConnection::class.java) +
                authorizerish(io.requery.android.database.sqlite.SQLiteDatabaseConfiguration::class.java)
            val rqRows = rows { rq.rawQuery(it, null) }
            val rqWrite = runCatching { rq.execSQL("UPDATE pages SET name='x'") }.exceptionOrNull()
            record(spike, "requery", "sqlite=$rqVersion compile_options_auth=$rqAuthOptions authorizer_methods=$rqMethods credential_rows=$rqRows update_error=${rqWrite?.message}")
            rq.close()

            // Platform handle.
            val pf = AndroidSpikeSupport.platformReadOnly(db)
            val pfVersion = AndroidSpikeSupport.scalarLong(pf, "SELECT 1").let { pf.rawQuery("SELECT sqlite_version()", null).use { c -> c.moveToFirst(); c.getString(0) } }
            val pfAuthOptions = pf.rawQuery("PRAGMA compile_options", null).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.filter { it.contains("AUTH", true) }.toList() }
            val pfMethods = authorizerish(android.database.sqlite.SQLiteDatabase::class.java)
            val pfRows = rows { pf.rawQuery(it, null) }
            val pfWrite = runCatching { pf.execSQL("UPDATE pages SET name='x'") }.exceptionOrNull()
            record(spike, "platform", "sqlite=$pfVersion compile_options_auth=$pfAuthOptions authorizer_methods=$pfMethods credential_rows=$pfRows update_error=${pfWrite?.message}")
            pf.close()

            // Native libs: requery ships libsqlite3x.so in the APK; look for the authorizer symbol/string.
            val apk = ZipFile(AndroidSpikeSupport.context.applicationInfo.sourceDir)
            val scans = apk.entries().asSequence().filter { it.name.endsWith("libsqlite3x.so") }.map { e ->
                val bytes = apk.getInputStream(e).use { it.readBytes() }
                e.name to String(bytes, Charsets.ISO_8859_1).contains("sqlite3_set_authorizer")
            }.toList()
            record(spike, "requery_native_lib_contains_sqlite3_set_authorizer_string", scans)
            val platformLib = runCatching { System.loadLibrary("sqlite"); "loaded" }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            record(spike, "platform_libsqlite_loadLibrary", platformLib)

            assertTrue(rqMethods.isEmpty() && pfMethods.isEmpty(), "authorizer-like API appeared: $rqMethods $pfMethods")
            assertTrue(rqRows.all { it == 1 } && pfRows.all { it == 1 }, "a credential read was denied/empty: $rqRows $pfRows")
            assertNotNull(rqWrite); assertNotNull(pfWrite)
        } finally {
            app.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3e_should_ConfirmConsoleHandleIndependent_When_CreateReadDriverIsReadWriteAndHandleIsAbandoned() {
        val dir = AndroidSpikeSupport.freshDir("indep")
        val db = dir.resolve("app.db")
        val app = AndroidSpikeSupport.appDriver(db)
        try {
            AndroidSpikeSupport.appInsertPage(app, "seed")
            val readDriver = DriverFactory().createReadDriver("jdbc:sqlite:${db.absolutePath}")
            assertNotNull(readDriver, "Android createReadDriver should return a driver")
            val rwViaReadDriver = runCatching { AndroidSpikeSupport.appInsertPage(readDriver, "via-read-driver") }
            record(spike, "createReadDriver", "class=${readDriver.javaClass.simpleName} write_through_it=${if (rwViaReadDriver.isSuccess) "SUCCEEDS (read-write)" else "fails: ${rwViaReadDriver.exceptionOrNull()?.message}"}")
            readDriver.close()

            // Console handle: separate OPEN_READONLY handle; abandon-and-close it and the app pool still works.
            val rq = AndroidSpikeSupport.requeryReadOnly(db)
            val before = AndroidSpikeSupport.scalarLong(rq, "SELECT COUNT(*) FROM pages")
            val writeOnConsole = runCatching { rq.execSQL("INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('c','c',1,1)") }
            rq.close()
            repeat(16) { AndroidSpikeSupport.appInsertPage(app, "after-close-$it") }
            val fresh = AndroidSpikeSupport.requeryReadOnly(db)
            val after = AndroidSpikeSupport.scalarLong(fresh, "SELECT COUNT(*) FROM pages")
            fresh.close()
            record(spike, "console_handle_independence", "console_write_rejected=${writeOnConsole.isFailure} (${writeOnConsole.exceptionOrNull()?.message}) pages_before=$before pages_after_16_app_writes=$after")
            assertTrue(writeOnConsole.isFailure)
            assertEquals(before + 16, after)
        } finally {
            app.close(); dir.deleteRecursively()
        }
    }

    private fun authorizerish(cls: Class<*>): List<String> =
        (cls.declaredMethods + cls.methods).map { it.name }.distinct().filter { it.contains("authoriz", true) || it.contains("deny", true) }
}
