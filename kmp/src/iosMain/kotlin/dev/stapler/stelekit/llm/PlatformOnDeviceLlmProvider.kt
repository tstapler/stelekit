// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

// Story 5.5c: replaces the Epic 1 placeholder (`null`) with the real Foundation Models
// Swift-shim-backed implementation. [IosOnDeviceLlmProvider] itself is always constructible —
// see its doc comment for why iOS unavailability is expressed via checkAvailability() rather
// than a null return here (unlike Android's ML-Kit-init-can-fail case). The `LlmProvider?`
// return type is kept to match the `expect fun` signature in commonMain exactly.
//
// COULD NOT BE COMPILED OR RUN IN THIS ENVIRONMENT — this depends transitively on the
// cinterop-generated `shim.foundationmodels.FoundationModelsShim` binding, which cannot be
// produced on this Linux sandbox (no Xcode/macOS/iOS SDK; `cinteropFoundationModelsShimIos*`
// Gradle tasks are SKIPPED on this host — see iosApp/README.md for the full explanation and
// what a developer with Xcode must do to finish verification).
actual fun platformOnDeviceLlmProvider(): LlmProvider? = IosOnDeviceLlmProvider()
