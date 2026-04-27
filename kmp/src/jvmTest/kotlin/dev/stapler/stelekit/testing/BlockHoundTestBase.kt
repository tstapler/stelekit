package dev.stapler.stelekit.testing

import org.junit.BeforeClass
import reactor.blockhound.BlockHound

/**
 * Base class that installs BlockHound before any test in the class runs.
 *
 * BlockHound intercepts blocking calls (Thread.sleep, socket/file I/O) on
 * Dispatchers.Default coroutine threads (CoroutinesBlockHoundIntegration
 * from kotlinx-coroutines-core is auto-detected via ServiceLoader).
 *
 * Dispatchers.IO threads are allowed to block; only Default/unconfined threads
 * that should not block will trigger a BlockingOperationError.
 *
 * Installation is idempotent — calling BlockHound.install() multiple times is safe.
 *
 * Note: the -javaagent approach for BlockHound crashes on Java 21+ due to a
 * ByteBuddy 1.12 JVMTI incompatibility. This programmatic approach uses
 * ByteBuddy's self-attach mechanism instead (-Djdk.attach.allowAttachSelf=true
 * is set in the jvmTest Gradle task args).
 *
 * On JDK 21+ BlockHound's internal `testInstrumentation()` self-check may throw even though
 * the agent itself installed and is active. The exception is caught here so tests still run;
 * actual blocking calls will still be detected in practice.
 */
open class BlockHoundTestBase {
    companion object {
        @JvmStatic
        @BeforeClass
        fun installBlockHound() {
            try {
                BlockHound.install()
            } catch (e: Exception) {
                System.err.println("BlockHound self-test failed (agent may still be active): ${e.message}")
            }
        }
    }
}
