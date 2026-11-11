// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

/** Text of the acknowledgement the user must accept before a provider may receive project data. */
object ProjectAnalysisAcknowledgement {
    const val TITLE = "Send project data to this provider?"

    /** A custom OpenAI-compatible endpoint may run locally or remotely, so the user confirms which. */
    fun text(kind: LlmProviderKind, isCustomEndpoint: Boolean): String = when {
        isCustomEndpoint ->
            "Project analysis sends a short summary of your project (measurements and status, no file paths) " +
                "to the endpoint you configured. Confirm where it runs: if it is hosted by a third party, " +
                "that party receives this data."
        kind == LlmProviderKind.ON_DEVICE ->
            "Project analysis runs on this device. Your project summary is not sent over the network."
        else ->
            "Project analysis sends a short summary of your project (measurements and status, no file paths) " +
                "to this provider's servers after each sync."
    }
}
