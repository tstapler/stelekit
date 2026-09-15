package dev.stapler.stelekit.platform

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * Regression coverage for [SafChangeDetector]'s FileObserver fast path — used when the graph
 * folder is direct local/internal storage (MANAGE_EXTERNAL_STORAGE granted, realGraphPath !=
 * null), which is what a graph on plain internal storage uses.
 *
 * Gap this closes: before this test, [SafChangeDetector] — the class actually responsible for
 * detecting external disk changes on Android (FileObserver/inotify, ContentObserver, the 30s
 * SAF poll, and the foreground-resume trigger) — had zero test coverage of any kind, on any
 * branch. No androidUnitTest or instrumented test referenced it. That meant a change to this
 * exact mechanism could regress silently; only GraphFileWatcher's common 5s poll fallback
 * (untested against real disk here too, but covered on JVM by GraphLoaderWatcherTest) offered
 * any safety net.
 *
 * Robolectric's ShadowFileObserver is backed by a real java.nio.file.WatchService (inotify on
 * Linux), so a real external write to a real temp directory here exercises the same code path
 * production runs on, not a mock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SafChangeDetectorFileObserverTest {

    @Test
    fun external_write_to_pages_dir_triggers_onExternalChange() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graphDir = File(System.getProperty("java.io.tmpdir"), "saf_fileobserver_test_${System.nanoTime()}")
        val pagesDir = File(graphDir, "pages").apply { mkdirs() }
        File(graphDir, "journals").mkdirs()

        val changeCount = AtomicInteger(0)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        // treeUri is only consulted on the ContentObserver branch (realGraphPath == null);
        // this test exercises the FileObserver branch, so any well-formed Uri is fine.
        val detector = SafChangeDetector(
            context = context,
            treeUri = Uri.parse("content://dummy/tree/x"),
            onExternalChange = { changeCount.incrementAndGet() },
            realGraphPath = graphDir.absolutePath,
        )

        try {
            detector.start(scope)
            // Let the WatchService register its watch before the write races it.
            delay(200)

            File(pagesDir, "External.md").writeText("- written by an external process\n")

            // handleFileEvent() dispatches onExternalChange via mainHandler.post{} — Robolectric's
            // main Looper is paused by default and must be idled explicitly to drain it, even
            // though the underlying WatchService (a real background thread) already saw the write.
            withTimeout(5_000L) {
                while (changeCount.get() == 0) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(50)
                }
            }

            assertTrue(changeCount.get() > 0, "onExternalChange should fire for an external write to pages/")
        } finally {
            detector.stop()
            scope.cancel()
            graphDir.deleteRecursively()
        }
    }
}
