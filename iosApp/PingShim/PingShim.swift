// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
//
// Story 5.2 (Epic 5, llm-service): trivial no-op cinterop smoke-test shim. Zero FoundationModels
// dependency, by design — this exists purely to prove a hand-authored Kotlin/Native cinterop
// `.def` can bind to a custom Swift-authored, @objc-visible symbol *in this codebase*, before
// Story 5.3 attempts the materially riskier FoundationModels integration. See
// project_plans/llm-service/implementation/plan.md's Story 5.2 and
// project_plans/llm-service/decisions/ADR-013-ios-on-device-llm-swift-shim.md (B3 finding: the
// codebase's only prior iOS cinterop precedent, IosCredentialStore.kt, binds against Apple's own
// Security system framework headers, not a hand-authored shim module — that claim is untested
// until this smoke test passes).
//
// Task 5.2c (MANUAL VERIFICATION ONLY, hard gate — Story 5.3 must not start until this passes):
// build this as part of the iosApp scaffold (Story 5.1), call the cinterop-generated Kotlin
// binding for PingShim().ping() from a throwaway iosMain entry point, and confirm it returns
// exactly 42. NOT performed in this environment (Linux sandbox, no Xcode/macOS/iOS SDK
// available) — record Xcode version, macOS version, device/simulator used, and the observed
// return value here once a contributor with Xcode performs it:
//
//   Xcode version:      <fill in>
//   macOS version:      <fill in>
//   Device/simulator:   <fill in>
//   Observed ping():    <fill in>
//   Date:                <fill in>

import Foundation

@objc(PingShim)
public class PingShim: NSObject {
    @objc public func ping() -> Int32 {
        return 42
    }
}
