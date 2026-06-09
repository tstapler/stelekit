// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.auto

import kotlinx.coroutines.flow.StateFlow

interface ObservedSession {
    val bookInfo: StateFlow<BookInfo>
    fun start()
    fun close()
    fun refreshBookInfo()
    fun isNotificationListenerEnabled(): Boolean
}
