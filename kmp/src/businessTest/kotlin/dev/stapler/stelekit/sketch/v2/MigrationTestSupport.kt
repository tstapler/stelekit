// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.platform.BrowserPersistence
import dev.stapler.stelekit.platform.PersistenceState
import dev.stapler.stelekit.platform.Settings

/** An in-memory `Settings`: a "restart" is a second object over the same [store]. */
class MapSettings(val store: MutableMap<String, String> = mutableMapOf()) : Settings {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = store[key]?.toBoolean() ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        store[key] = value.toString()
    }

    override fun getString(key: String, defaultValue: String): String = store[key] ?: defaultValue

    override fun putString(key: String, value: String) {
        store[key] = value
    }

    override fun containsKey(key: String): Boolean = store.containsKey(key)
}

/** Counts `persist()` requests and answers with [answer]. */
class CountingPersistence(private val answer: PersistenceState = PersistenceState.Granted) : BrowserPersistence {
    var requests = 0
        private set

    override suspend fun request(): PersistenceState {
        requests++
        return answer
    }
}
