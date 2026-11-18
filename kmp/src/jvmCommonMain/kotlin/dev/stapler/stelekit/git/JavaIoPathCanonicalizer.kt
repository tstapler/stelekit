// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import java.io.File

/** The JVM/Android [PathCanonicalizer]: resolves symlinks, `..` and trailing slashes; throws `IOException` when it cannot. */
object JavaIoPathCanonicalizer : PathCanonicalizer {
    override fun canonicalize(path: String): String = File(path).canonicalFile.path
}

/** The one gate key for a resolved `.git` directory on JVM and Android; null (`Unreadable`) when it cannot be canonicalised. */
fun RepoOpGate.Companion.keyForOrNull(gitDir: File): String? = keyForOrNull(gitDir.path, JavaIoPathCanonicalizer)
