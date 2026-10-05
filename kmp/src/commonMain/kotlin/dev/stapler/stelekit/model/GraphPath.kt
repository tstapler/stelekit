// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.model

import kotlin.jvm.JvmInline

/** Root directory of a repository or graph on disk or SAF storage. */
@JvmInline
value class RepoRoot(val value: String)

/** Relative subdirectory within the repository where notes reside (e.g., "logseq", "notes", ""). */
@JvmInline
value class WikiSubdir(val value: String) {
    val isRoot: Boolean get() = value.isBlank()
}

/** Fully-resolved path where pages/ and journals/ directories actually live. */
@JvmInline
value class EffectiveNotesPath(val value: String) {
    fun pagesDir(): String = if (value.isEmpty()) "pages" else "$value/pages"
    fun journalsDir(): String = if (value.isEmpty()) "journals" else "$value/journals"
}

/** Wrapper around Android SAF URIs (`saf://...` or `content://...`). */
@JvmInline
value class SafUri(val rawUri: String) {
    val isSaf: Boolean get() = rawUri.startsWith("saf://") || rawUri.startsWith("content://")

    fun decodeTreeUri(): String = rawUri.removePrefix("saf://")
}

/** Sealed hierarchy for graph storage locations. */
sealed interface GraphLocation {
    val repoRoot: String
    val wikiSubdir: WikiSubdir
    val effectivePath: EffectiveNotesPath

    /** Local filesystem graph (JVM / POSIX / Android Internal). */
    data class Local(
        override val repoRoot: String,
        override val wikiSubdir: WikiSubdir = WikiSubdir(""),
    ) : GraphLocation {
        override val effectivePath: EffectiveNotesPath
            get() = EffectiveNotesPath(
                if (wikiSubdir.isRoot) repoRoot else "$repoRoot/${wikiSubdir.value}"
            )
    }

    /** Android Storage Access Framework (SAF) graph. */
    data class Saf(
        val uri: SafUri,
        override val wikiSubdir: WikiSubdir = WikiSubdir(""),
    ) : GraphLocation {
        override val repoRoot: String get() = uri.rawUri
        override val effectivePath: EffectiveNotesPath
            get() = EffectiveNotesPath(
                if (wikiSubdir.isRoot) uri.rawUri else "${uri.rawUri}/${wikiSubdir.value}"
            )
    }

    /** Demo / in-memory graph. */
    object Demo : GraphLocation {
        override val repoRoot: String = "__demo__"
        override val wikiSubdir: WikiSubdir = WikiSubdir("")
        override val effectivePath: EffectiveNotesPath = EffectiveNotesPath("__demo__")
    }

    companion object {
        fun from(
            path: String,
            detectedRepoRoot: String? = null,
            detectedWikiSubdir: String? = null,
            effectivePathStr: String? = null,
        ): GraphLocation {
            val subdir = WikiSubdir(detectedWikiSubdir ?: "")
            if (path == "__demo__") return Demo
            val isSaf = path.startsWith("saf://") || path.startsWith("content://")
            return if (isSaf) {
                Saf(SafUri(path), subdir)
            } else {
                Local(detectedRepoRoot ?: path, subdir)
            }
        }
    }
}
