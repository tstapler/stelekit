package dev.stapler.stelekit.merge

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.merge.ActiveTargetWriterContractTest.ActiveHarness
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [OwnedPageWriterContractSuite] against the real active writer (file/DB hop), plus its own cases. */
class ActiveOwnedPageWriterContractTest : OwnedPageWriterContractSuite() {
    private class Adapter(val h: ActiveHarness) : OwnedHarness {
        override val writer: OwnedPageWriter get() = h.writer
        override suspend fun seed(key: PageKey, text: String) = h.seed(key, text)
        override suspend fun seedFileOnly(key: PageKey, text: String) { h.fs.writeFile("/graphs/active/pages/${key.name}.md", text) }
        override suspend fun editContent(key: PageKey, from: String, to: String) = h.editContent(key, from, to)
        override fun fileText(key: PageKey) = h.fileText(key)
        override suspend fun snapshot(key: PageKey) = h.snapshot(key)
        override suspend fun close() = h.close()
    }

    override fun newHarness(): OwnedHarness = Adapter(ActiveHarness())

    @Test
    fun aPageBeingEditedIsRetryableNotReplaced() = runBlocking<Unit> {
        val h = ActiveHarness()
        try {
            h.writer.replaceOwnedPage(key, owned("alpha", "beta"), null)
            h.editInEditor(key, "beta", "typing...")
            val result = h.writer.replaceOwnedPage(key, owned("gamma"), OwnedHash.of(owned("alpha", "beta")))
            val err = assertIs<DomainError.MergeError.Retryable>((result as Either.Left).value)
            assertEquals(WriteRetryReason.PageBeingEdited.message, err.message)
            h.flushEditor(key)
        } finally {
            h.close()
        }
    }

    @Test
    fun aFailedUuidLookupLeavesTheOldPageIntact() = runBlocking<Unit> {
        val h = ActiveHarness()
        try {
            h.writer.replaceOwnedPage(key, owned("alpha", "beta"), null)
            val before = h.fileText(key)
            h.failUuidLookup = true
            val result = h.writer.replaceOwnedPage(key, owned("gamma"), OwnedHash.of(owned("alpha", "beta")))
            assertIs<Either.Left<*>>(result)
            h.failUuidLookup = false
            assertEquals(before, h.fileText(key))
            assertEquals(listOf("alpha", "beta"), h.snapshot(key)!!.blocks.drop(1).map { it.content })
        } finally {
            h.close()
        }
    }
}
