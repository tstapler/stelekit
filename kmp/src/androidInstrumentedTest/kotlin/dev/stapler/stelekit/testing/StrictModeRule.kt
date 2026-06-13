// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.testing

import android.os.StrictMode
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit4 rule that enables Android StrictMode on the main thread for the duration of each test.
 *
 * Detects disk reads, disk writes, and network access on the main (UI) thread and fails the
 * test immediately via [StrictMode.ThreadPolicy.Builder.penaltyDeath]. This catches the class
 * of bug where blocking I/O is accidentally called from a LaunchedEffect or other main-thread
 * context, which manifests as ANR on real devices.
 *
 * Usage:
 * ```kotlin
 * @get:Rule val strictMode = StrictModeRule()
 * ```
 *
 * The policy is applied only to the main thread (via [runOnMainSync]); the test/instrumentation
 * thread is unaffected, so @Before / @After setup that does disk I/O is fine.
 *
 * The original policy is restored after each test so tests don't bleed into each other.
 */
class StrictModeRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                var savedPolicy: StrictMode.ThreadPolicy? = null

                instrumentation.runOnMainSync {
                    savedPolicy = StrictMode.getThreadPolicy()
                    StrictMode.setThreadPolicy(
                        StrictMode.ThreadPolicy.Builder()
                            .detectDiskReads()
                            .detectDiskWrites()
                            .detectNetwork()
                            .penaltyLog()
                            .penaltyDeath()
                            .build()
                    )
                }

                try {
                    base.evaluate()
                } finally {
                    instrumentation.runOnMainSync {
                        savedPolicy?.let { StrictMode.setThreadPolicy(it) }
                    }
                }
            }
        }
}
