package dev.stapler.stelekit.vault.perf

import dev.stapler.stelekit.vault.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.measureTime

/**
 * Performance tests for vault operations.
 * Skipped in CI fast-path unless RUN_PERF_TESTS=true is set.
 */
class VaultPerformanceTest {
    private val engine = JvmCryptoEngine()

    private fun isPerfEnabled() = System.getenv("RUN_PERF_TESTS") == "true"

    // PERF-01 — Argon2id at default params completes in ≤ 5,000 ms
    @Test fun `argon2id at default params completes within 5 seconds`() {
        if (isPerfEnabled()) {
            val password = "test-password".encodeToByteArray()
            val salt = engine.secureRandom(16)
            val elapsed = measureTime {
                engine.argon2id(password, salt, memory = 65536, iterations = 3, parallelism = 1, outputLength = 32)
            }
            println("PERF-01: Argon2id (64MiB/3iter) = ${elapsed.inWholeMilliseconds}ms")
            assertTrue(elapsed.inWholeMilliseconds <= 5000, "Argon2id must complete in ≤5000ms, took ${elapsed.inWholeMilliseconds}ms")
        }
    }

    // PERF-02 — Encrypt 100 KB file in ≤ 5 ms (median)
    @Test fun `encrypt 100KB file within 5ms median`() {
        if (isPerfEnabled()) {
            val dek = engine.secureRandom(32)
            val layer = CryptoLayer(engine, dek)
            val content = ByteArray(100 * 1024) { it.toByte() }
            val path = "pages/bigfile.md.stek"

            val times = (1..100).map { measureTime { layer.encrypt(path, content) }.inWholeMilliseconds }
            val median = times.sorted()[50]
            println("PERF-02: Encrypt 100KB median = ${median}ms")
            assertTrue(median <= 5, "Median encrypt time must be ≤5ms, got ${median}ms")
        }
    }

    // PERF-03 — Decrypt 100 KB file in ≤ 5 ms (median)
    @Test fun `decrypt 100KB file within 5ms median`() {
        if (isPerfEnabled()) {
            val dek = engine.secureRandom(32)
            val layer = CryptoLayer(engine, dek)
            val content = ByteArray(100 * 1024) { it.toByte() }
            val path = "pages/bigfile.md.stek"
            val encrypted = layer.encrypt(path, content)

            val times = (1..100).map { measureTime { layer.decrypt(path, encrypted) }.inWholeMilliseconds }
            val median = times.sorted()[50]
            println("PERF-03: Decrypt 100KB median = ${median}ms")
            assertTrue(median <= 5, "Median decrypt time must be ≤5ms, got ${median}ms")
        }
    }

    // PERF-04 — Per-file HKDF subkey derivation overhead ≤ 0.5 ms per file
    @Test fun `HKDF subkey derivation within 0_5ms per call`() {
        if (isPerfEnabled()) {
            val dek = engine.secureRandom(32)
            val info = "stelekit-file-v1".encodeToByteArray()
            val elapsed = measureTime {
                repeat(10_000) { i ->
                    engine.hkdfSha256(dek, "pages/Note$i.md.stek".encodeToByteArray(), info, 32)
                }
            }
            val meanMs = elapsed.inWholeMilliseconds.toDouble() / 10_000
            println("PERF-04: HKDF mean per call = ${meanMs}ms")
            assertTrue(meanMs <= 0.5, "Mean HKDF time must be ≤0.5ms, got ${meanMs}ms")
        }
    }

    // PERF-05 — Lock (DEK zeroing) completes in ≤ 1,000 ms
    @Test fun `lock completes within 1000ms`() = runTest {
        if (isPerfEnabled()) {
            val store = mutableMapOf<String, ByteArray>()
            val vm = VaultManager(
                crypto = engine,
                fileReadBytes = { path -> store[path] },
                fileWriteBytes = { path, data -> store[path] = data; true },
            )
            val graphPath = "/tmp/perf-test"
            vm.createVault(graphPath, "pass".toCharArray(), argon2Params = TEST_ARGON2_PARAMS)
            vm.unlock(graphPath, "pass".toCharArray(), TEST_ARGON2_PARAMS)
            val dekRef = vm.currentDek()!!
            val elapsed = measureTime { vm.lock() }
            assertTrue(dekRef.all { it == 0.toByte() }, "DEK must be zeroed after lock()")
            println("PERF-05: Lock elapsed = ${elapsed.inWholeMilliseconds}ms")
            assertTrue(elapsed.inWholeMilliseconds <= 1000, "Lock must complete in ≤1000ms")
        }
    }
}
