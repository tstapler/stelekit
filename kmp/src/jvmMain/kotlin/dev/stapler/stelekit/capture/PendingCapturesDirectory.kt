// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

/** Resolves the on-disk directory where [PendingCaptureFile] JSON files are written. */
object PendingCapturesDirectory {
    fun path(): String = "${System.getProperty("user.home")}/.stelekit/pending-captures"
}
