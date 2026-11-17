// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform

/** Whether the browser's `matchMedia(query)` matches right now; read at call time so a changed user setting is seen. */
internal fun webMediaQueryMatches(query: String): Boolean = js("window.matchMedia(query).matches")
