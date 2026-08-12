package dev.stapler.stelekit.platform

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [classifyReconciliation] and [classifyReconciliationBytes] — the pure four-way
 * classifier backing the reconciliation pass (Phase 3) of the web-local-folder-livesync feature.
 * See Story 1.4.1 in `project_plans/web-local-folder-livesync/implementation/plan.md` and the
 * corresponding rows in validation.md.
 */
class HostReconciliationTest {

    // ── classifyReconciliation (String) ──────────────────────────────────────

    @Test
    fun classifyReconciliation_should_ReturnIdentical_When_HostAndCacheContentMatch() {
        val hostContent = "# Foo\nbar"
        val cacheContent = "# Foo\nbar"

        val result = classifyReconciliation(hostContent, cacheContent)

        assertEquals(ReconciliationOutcome.Identical, result)
    }

    @Test
    fun classifyReconciliation_should_ReturnIdentical_When_BothSidesAreEmptyStringNotNull() {
        val result = classifyReconciliation("", "")

        assertEquals(ReconciliationOutcome.Identical, result)
    }

    @Test
    fun classifyReconciliation_should_ReturnHostChangedConflict_When_BothSidesNonNullAndDiffer() {
        val hostContent = "# Foo\nedited on disk"
        val cacheContent = "# Foo\nedited in browser"

        val result = classifyReconciliation(hostContent, cacheContent)

        assertEquals(ReconciliationOutcome.HostChangedConflict, result)
    }

    @Test
    fun classifyReconciliation_should_ReturnHostOnlyNew_When_CacheContentIsNull() {
        val result = classifyReconciliation("# NewPage", null)

        assertEquals(ReconciliationOutcome.HostOnlyNew, result)
    }

    @Test
    fun classifyReconciliation_should_ReturnBrowserOnlyNeedsPush_When_HostContentIsNull() {
        val result = classifyReconciliation(null, "# Created in browser")

        assertEquals(ReconciliationOutcome.BrowserOnlyNeedsPush, result)
    }

    // ── classifyReconciliationBytes (ByteArray) ─────────────────────────────

    @Test
    fun classifyReconciliationBytes_should_UseContentEqualsNotReferenceEquality_When_ByteArraysAreEqualButDifferentInstances() {
        val hostBytes = byteArrayOf(1, 2, 3, 4)
        val cacheBytes = byteArrayOf(1, 2, 3, 4) // equal content, distinct instance

        val result = classifyReconciliationBytes(hostBytes, cacheBytes)

        assertEquals(ReconciliationOutcome.Identical, result)
    }

    @Test
    fun classifyReconciliationBytes_should_ReturnHostChangedConflict_When_ByteArraysDifferAndBothNonNull() {
        val hostBytes = byteArrayOf(1, 2, 3, 4)
        val cacheBytes = byteArrayOf(9, 9, 9, 9)

        val result = classifyReconciliationBytes(hostBytes, cacheBytes)

        assertEquals(ReconciliationOutcome.HostChangedConflict, result)
    }

    @Test
    fun classifyReconciliationBytes_should_ReturnHostOnlyNew_When_CacheBytesIsNull() {
        val hostBytes = byteArrayOf(1, 2, 3, 4)

        val result = classifyReconciliationBytes(hostBytes, null)

        assertEquals(ReconciliationOutcome.HostOnlyNew, result)
    }

    @Test
    fun classifyReconciliationBytes_should_ReturnBrowserOnlyNeedsPush_When_HostBytesIsNull() {
        val cacheBytes = byteArrayOf(1, 2, 3, 4)

        val result = classifyReconciliationBytes(null, cacheBytes)

        assertEquals(ReconciliationOutcome.BrowserOnlyNeedsPush, result)
    }

    // ── String/Bytes agreement ───────────────────────────────────────────────

    @Test
    fun classifyReconciliation_and_classifyReconciliationBytes_should_AgreeOnOutcome_When_GivenEquivalentTextAndUtf8BytesContent() {
        val hostText = "# Foo\nbar"
        val cacheText = "# Foo\nbar"

        val stringResult = classifyReconciliation(hostText, cacheText)
        val bytesResult = classifyReconciliationBytes(
            hostText.encodeToByteArray(),
            cacheText.encodeToByteArray(),
        )

        assertEquals(stringResult, bytesResult)
        assertEquals(ReconciliationOutcome.Identical, stringResult)
    }

    // ── Property-based coverage ─────────────────────────────────────────────
    //
    // No property-testing library (e.g. kotest-property) is on the classpath for this
    // multiplatform module, so these hand-roll the same idea with a seeded `Random`: generate many
    // varied inputs and assert invariants that must hold for *all* of them, rather than fixed
    // examples. The seed is fixed so failures reproduce deterministically across CI runs.

    private val propertyIterations = 200

    /** Biased towards content likely to trip up naive string/byte handling: empty, ASCII, Latin-1
     *  supplement, CJK, combining marks, and characters outside the BMP that require UTF-16
     *  surrogate pairs (and therefore multi-byte, non-trivial UTF-8 encodings). */
    private fun randomUnicodeString(random: Random, maxLen: Int): String {
        val len = random.nextInt(0, maxLen + 1)
        val sb = StringBuilder()
        repeat(len) {
            val codePoint = when (random.nextInt(6)) {
                0 -> random.nextInt(0x20, 0x7F) // printable ASCII
                1 -> random.nextInt(0xA0, 0x100) // Latin-1 supplement
                2 -> random.nextInt(0x4E00, 0x9FFF) // CJK
                3 -> random.nextInt(0x0300, 0x0370) // combining diacritical marks
                4 -> random.nextInt(0x1F300, 0x1FAFF) // emoji (supplementary plane)
                else -> random.nextInt(0x0000, 0x10FFFF)
            }.let { if (it in 0xD800..0xDFFF) 0x41 else it } // avoid lone surrogate code points
            sb.append(codePointToChars(codePoint))
        }
        return sb.toString()
    }

    /** Manual UTF-16 surrogate-pair encoding (portable across all KMP targets — `StringBuilder
     *  .appendCodePoint` and the JVM `String(IntArray, ...)` constructor are not). */
    private fun codePointToChars(codePoint: Int): String =
        if (codePoint <= 0xFFFF) {
            codePoint.toChar().toString()
        } else {
            val c = codePoint - 0x10000
            val high = (c shr 10) + 0xD800
            val low = (c and 0x3FF) + 0xDC00
            "${high.toChar()}${low.toChar()}"
        }

    private fun randomByteArray(random: Random, maxLen: Int): ByteArray =
        ByteArray(random.nextInt(0, maxLen + 1)) { random.nextInt(0, 256).toByte() }

    @Test
    fun classifyReconciliation_should_ReturnIdentical_When_ContentIsComparedWithItself_AcrossManyRandomStrings() {
        val random = Random(42)
        repeat(propertyIterations) {
            val content = randomUnicodeString(random, 64)

            assertEquals(
                ReconciliationOutcome.Identical,
                classifyReconciliation(content, content),
                "expected reflexivity for content=$content",
            )
        }
    }

    @Test
    fun classifyReconciliationBytes_should_ReturnIdentical_When_ContentIsComparedWithItself_AcrossManyRandomByteArrays() {
        val random = Random(43)
        repeat(propertyIterations) {
            val bytes = randomByteArray(random, 64)

            assertEquals(
                ReconciliationOutcome.Identical,
                classifyReconciliationBytes(bytes, bytes.copyOf()), // distinct instance, equal content
            )
        }
    }

    @Test
    fun classifyReconciliation_should_MatchEqualityDefinition_AcrossManyRandomStringPairs() {
        val random = Random(44)
        repeat(propertyIterations) {
            val host = randomUnicodeString(random, 32)
            val cache = randomUnicodeString(random, 32)

            val expected = if (host == cache) ReconciliationOutcome.Identical else ReconciliationOutcome.HostChangedConflict
            assertEquals(expected, classifyReconciliation(host, cache), "host=$host cache=$cache")
        }
    }

    @Test
    fun classifyReconciliation_should_ReturnHostOnlyNew_When_CacheIsNull_AcrossManyRandomStrings() {
        val random = Random(45)
        repeat(propertyIterations) {
            val host = randomUnicodeString(random, 32)

            assertEquals(ReconciliationOutcome.HostOnlyNew, classifyReconciliation(host, null))
        }
    }

    @Test
    fun classifyReconciliation_should_ReturnBrowserOnlyNeedsPush_When_HostIsNull_AcrossManyRandomStrings() {
        val random = Random(46)
        repeat(propertyIterations) {
            val cache = randomUnicodeString(random, 32)

            assertEquals(ReconciliationOutcome.BrowserOnlyNeedsPush, classifyReconciliation(null, cache))
        }
    }

    @Test
    fun classifyReconciliation_should_MirrorHostOnlyNewAndBrowserOnlyNeedsPush_When_ArgumentsAreSwapped_AcrossManyRandomInputs() {
        val random = Random(47)
        repeat(propertyIterations) {
            // Swapping host/cache must swap the two "one side missing" outcomes and leave
            // Identical/HostChangedConflict fixed points — a regression here (e.g. a copy-pasted
            // `!hostPresent` where `!cachePresent` was meant) would silently invert which side
            // reconciliation treats as the source of truth.
            val useNullHost = random.nextBoolean()
            val host = if (useNullHost) null else randomUnicodeString(random, 32)
            val cache = if (!useNullHost) null else randomUnicodeString(random, 32)

            val forward = classifyReconciliation(host, cache)
            val swapped = classifyReconciliation(cache, host)

            val expectedSwap = when (forward) {
                ReconciliationOutcome.HostOnlyNew -> ReconciliationOutcome.BrowserOnlyNeedsPush
                ReconciliationOutcome.BrowserOnlyNeedsPush -> ReconciliationOutcome.HostOnlyNew
                ReconciliationOutcome.Identical, ReconciliationOutcome.HostChangedConflict -> forward
            }
            assertEquals(expectedSwap, swapped, "host=$host cache=$cache forward=$forward")
        }
    }

    @Test
    fun classifyReconciliation_and_classifyReconciliationBytes_should_Agree_AcrossManyRandomUnicodeStringPairs() {
        val random = Random(48)
        repeat(propertyIterations) {
            val includeHost = random.nextBoolean()
            val includeCache = random.nextBoolean()
            val hostText = if (includeHost) randomUnicodeString(random, 48) else null
            val cacheText = if (includeCache) randomUnicodeString(random, 48) else null

            val stringResult = classifyReconciliation(hostText, cacheText)
            val bytesResult = classifyReconciliationBytes(
                hostText?.encodeToByteArray(),
                cacheText?.encodeToByteArray(),
            )

            assertEquals(
                stringResult,
                bytesResult,
                "UTF-8 round-trip disagreement for hostText=$hostText cacheText=$cacheText",
            )
        }
    }

    @Test
    fun classifyReconciliationBytes_should_UseStructuralEquality_When_ByteArraysDifferOnlyInOneByte_AcrossManyRandomByteArrays() {
        val random = Random(49)
        repeat(propertyIterations) {
            val bytes = randomByteArray(random, 16).let { if (it.isEmpty()) byteArrayOf(0) else it }
            val mutated = bytes.copyOf()
            val flipIndex = random.nextInt(mutated.size)
            mutated[flipIndex] = (mutated[flipIndex] + 1).toByte()

            val result = classifyReconciliationBytes(bytes, mutated)

            assertTrue(
                result == ReconciliationOutcome.HostChangedConflict,
                "flipping byte $flipIndex must be detected as a change: bytes=${bytes.toList()} mutated=${mutated.toList()}",
            )
        }
    }
}
