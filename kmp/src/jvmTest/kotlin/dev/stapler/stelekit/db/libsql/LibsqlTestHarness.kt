package dev.stapler.stelekit.db.libsql

import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files

object LibsqlTestHarness {

    fun isNativeAvailable(): Boolean = try {
        // Trigger the LibsqlJni object initializer, which calls loadNativeLibrary().
        // If the .so is missing from the classpath, this throws ExceptionInInitializerError
        // (an Error, not Exception) wrapping the UnsatisfiedLinkError.
        Class.forName("dev.stapler.stelekit.db.libsql.LibsqlJni")
        true
    } catch (e: Throwable) {
        false
    }

    fun createTempDriver(): JvmLibsqlDriver {
        val tmp = Files.createTempFile("libsql-test-", ".db").toFile()
        tmp.deleteOnExit()
        val driver = JvmLibsqlDriver(tmp.absolutePath, poolSize = 4)
        runBlocking { SteleDatabase.Schema.create(driver).await() }
        return driver
    }

    fun assumeNativeAvailable() {
        assumeTrue("libsql native library not available — skipping test", isNativeAvailable())
    }
}
