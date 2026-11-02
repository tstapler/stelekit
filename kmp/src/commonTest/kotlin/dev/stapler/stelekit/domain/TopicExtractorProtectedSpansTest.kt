// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TopicExtractorProtectedSpansTest {

    private fun terms(text: String) = TopicExtractor.extract(text).map { it.term }

    @Test
    fun noSuggestionsFromUrls() {
        val t = terms("Read https://github.com/PacktPublishing/Distributed-Computing and TensorFlow notes. TensorFlow rocks.")
        assertFalse(t.any { it.contains("Packt") || it.contains("Distributed") }, "got $t")
        assertTrue("TensorFlow" in t, "got $t")
    }

    @Test
    fun noSuggestionsFromCodeExistingLinksTagsOrPropertyLines() {
        val t = terms("`SqlDelight` and [[GraphQL]] and #[[PostgreSQL]] and #RocksDB\nsource:: MongoDB Atlas\nplain words here")
        assertTrue(t.isEmpty(), "got $t")
    }
}
