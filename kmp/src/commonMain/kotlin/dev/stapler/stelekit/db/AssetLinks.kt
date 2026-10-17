// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

/** `../assets/<path>` links in page markdown; shared by asset copying and file-path repair so they cannot drift. */
object AssetLinks {
    /** `[text](../assets/<path>)` or `![alt](../assets/<path>)`; group 2 is the path under assets/. */
    val LINK = Regex("""(!?\[[^\]]*]\(\s*\.\./assets/)([^)\s]+)""")

    fun refs(content: String): List<String> = LINK.findAll(content).map { it.groupValues[2] }.toList()
}
