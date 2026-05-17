package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * FileSystem spy that delegates all calls to a real [FileSystem] implementation and
 * counts calls to [writeFile], [readFile], and [fileExists]. Used to enforce per-insert
 * file-call budgets so regressions that add spurious SAF writes are caught by CI.
 *
 * Thread-safe: counters use [AtomicInteger] so multiple coroutines may call concurrently
 * without lost increments.
 */
class CountingFileSystem(private val delegate: FileSystem) : FileSystem by delegate {

    val writeFileCount = AtomicInteger(0)
    val readFileCount = AtomicInteger(0)
    val existsCount = AtomicInteger(0)

    override fun writeFile(path: String, content: String): Boolean {
        writeFileCount.incrementAndGet()
        return delegate.writeFile(path, content)
    }

    override fun readFile(path: String): String? {
        readFileCount.incrementAndGet()
        return delegate.readFile(path)
    }

    override fun fileExists(path: String): Boolean {
        existsCount.incrementAndGet()
        return delegate.fileExists(path)
    }

    /** Reset all counters to zero. Call before each test assertion window. */
    fun reset() {
        writeFileCount.set(0)
        readFileCount.set(0)
        existsCount.set(0)
    }

    /**
     * Assert the per-insert budget: no [writeFile] or [readFile] calls should fire
     * synchronously during an insert (both must be deferred by the debounce).
     */
    fun assertInsertBudget(label: String = "") {
        val tag = if (label.isBlank()) "" else "[$label] "
        assertEquals(
            0, writeFileCount.get(),
            "${tag}writeFile must not be called synchronously during insert (debounce must defer it)"
        )
        assertEquals(
            0, readFileCount.get(),
            "${tag}readFile must not be called during normal insert"
        )
    }

    /**
     * Assert that exactly one [writeFile] fired after the debounce window elapsed.
     */
    fun assertDebounceFired(label: String = "") {
        val tag = if (label.isBlank()) "" else "[$label] "
        assertEquals(
            1, writeFileCount.get(),
            "${tag}exactly 1 writeFile expected after debounce, got ${writeFileCount.get()}"
        )
    }
}
