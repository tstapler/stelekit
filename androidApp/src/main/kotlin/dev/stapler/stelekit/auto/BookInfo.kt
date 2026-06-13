// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

data class BookInfo(
    val title: String? = null,
    val author: String? = null,
    val chapter: String? = null,
    val positionMs: Long? = null,
    val isActive: Boolean = false,
) {
    companion object {
        val Unknown = BookInfo(isActive = false)
    }
}
