// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

/**
 * Resolves the Unix-domain-socket path [CaptureSocketListener] binds and [CaptureSocketClient]
 * connects to. Single source of truth so the two sides of the protocol can't silently drift --
 * a mismatch wouldn't throw or fail a test, it would just downgrade every capture to the
 * slower pending-captures-file poll path (see [CaptureSocketClient.trySend]'s fallback).
 */
object CaptureSocketPath {
    fun path(): String = "${System.getProperty("user.home")}/.stelekit/stelekit.sock"
}
