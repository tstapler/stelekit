// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

private val URL_USERINFO = Regex("(?<=//)[^/@\\s]+@")
private val QUERY_VALUE = Regex("([?&][^=&\\s#]+=)[^&\\s#]*")

/**
 * Strips credentials that git/HTTP errors echo back: URL userinfo (`user:token@`) and query-string
 * values (`?token=...`). Run it over every exception message that reaches a log or an exported report.
 */
fun redactSecrets(text: String): String =
    text.replace(URL_USERINFO, "").replace(QUERY_VALUE, "$1<redacted>")
