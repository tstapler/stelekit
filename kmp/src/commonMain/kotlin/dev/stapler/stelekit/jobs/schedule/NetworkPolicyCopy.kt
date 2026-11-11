// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** User-facing network-policy strings shared by every surface that waits on `NetworkPolicy.Unmetered`. */
object NetworkPolicyCopy {
    /** Model-download row and (Story 3.2.2) the LFS deferral status. */
    const val WAITING_FOR_UNMETERED = "Waiting for an unmetered network"
}
