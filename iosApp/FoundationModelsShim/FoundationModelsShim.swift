// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
//
// Story 5.3 (Epic 5, llm-service): the real Foundation Models Swift shim. Wraps Apple's
// FoundationModels framework (iOS 26+ / iPadOS 26+ / macOS 26+ — Apple Intelligence's on-device
// ~3B-parameter model) behind an @objc-compatible, completion-handler-based API that
// Kotlin/Native cinterop can bind to. FoundationModels itself is NOT reachable from cinterop
// directly: its surface (`LanguageModelSession`, the `@Generable` macro, Swift structured
// concurrency `async`/`await`) has no Objective-C representation and cannot be exposed via
// `@objc` even with effort — see
// project_plans/llm-service/decisions/ADR-013-ios-on-device-llm-swift-shim.md's "Alternatives
// Considered" section. This shim is the intermediate module ADR-013 requires.
//
// ============================================================================================
// COULD NOT BE COMPILED, RUN, OR VERIFIED IN THIS ENVIRONMENT.
// ============================================================================================
// This was authored in a Linux sandbox with zero Xcode/macOS/iOS SDK access. The code below is
// written to the best-effort documented shape of the FoundationModels API (SystemLanguageModel,
// LanguageModelSession, LanguageModelSession.GenerationError) as understood at authoring time.
// Apple's framework was young/actively evolving when this was written — a developer with
// Xcode 26+ MUST:
//   1. Build this file against the real SDK and fix any API-surface drift (exact enum case
//      names for `SystemLanguageModel.Availability`/`.UnavailableReason` and
//      `LanguageModelSession.GenerationError`, exact `LanguageModelSession` initializer and
//      `respond(to:)` method signatures, may differ from what's written here).
//   2. Perform Task 5.3c's manual verification (below) on macOS 26+ with Apple Intelligence
//      enabled on Apple-Intelligence-eligible hardware.
// No CI lane in this repository can catch drift here (see plan.md Epic 5's corrected CI
// framing) — this is a known, documented limitation, not an oversight.
//
// Task 5.3c (MANUAL VERIFICATION ONLY): exercise checkAvailability()/format() from a throwaway
// Swift host app or Xcode Playground; confirm expected values for at least the `.available`
// case and one `.unavailable` case. Record verification method/results here once performed:
//
//   Xcode version:        <fill in>
//   macOS version:        <fill in>
//   Apple Intelligence:   <fill in — enabled/disabled, region>
//   checkAvailability():  <fill in>
//   format() (success):   <fill in>
//   format() (guardrail): <fill in — how content rejection was triggered/observed>
//   Date:                  <fill in>

import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Availability codes shared with the Kotlin side. MUST be kept in sync manually with
/// `dev.stapler.stelekit.llm.mapShimCodeToAvailability`
/// (kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/IosAvailabilityMapping.kt) — that
/// function is the pure, businessTest-covered mirror of this mapping (Task 5.5d). If these two
/// code tables drift, `IosAvailabilityMappingTest` will keep passing (it only tests the Kotlin
/// side in isolation) while the real runtime behavior silently diverges — this is a known gap a
/// reviewer should check by inspection when either side changes.
private enum AvailabilityCode: Int {
    case available = 0
    case deviceNotEligible = 1
    case appleIntelligenceNotEnabled = 2
    case modelNotReady = 3
    case other = 4
}

/// `format(...)` result codes, per Story 5.3's acceptance criteria:
///   0 = success, 1 = guardrail content rejection, 2 = any other failure.
private enum FormatResultCode: Int {
    case success = 0
    case contentRejected = 1
    case otherFailure = 2
}

@objc(FoundationModelsShim)
public class FoundationModelsShim: NSObject {

    public override init() {
        super.init()
    }

    /// Live availability check — per ADR-013, region/eligibility is a moving target and MUST be
    /// evaluated fresh every call, never cached. Kotlin's `IosOnDeviceLlmProvider.checkAvailability()`
    /// (Story 5.5c) must likewise never cache this across calls.
    @objc(checkAvailabilityWithCompletion:)
    public func checkAvailability(completion: @escaping (Int, String?) -> Void) {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, macOS 26.0, *) {
            let availability = SystemLanguageModel.default.availability
            switch availability {
            case .available:
                completion(AvailabilityCode.available.rawValue, nil)
            case .unavailable(let reason):
                // NOTE: exact case names (deviceNotEligible / appleIntelligenceNotEnabled /
                // modelNotReady) are per ADR-013's stack research at authoring time — verify
                // against the actual SDK; `@unknown default` covers any drift defensively.
                switch reason {
                case .deviceNotEligible:
                    completion(AvailabilityCode.deviceNotEligible.rawValue, "deviceNotEligible")
                case .appleIntelligenceNotEnabled:
                    completion(AvailabilityCode.appleIntelligenceNotEnabled.rawValue, "appleIntelligenceNotEnabled")
                case .modelNotReady:
                    completion(AvailabilityCode.modelNotReady.rawValue, "modelNotReady")
                @unknown default:
                    completion(AvailabilityCode.other.rawValue, String(describing: reason))
                }
            }
        } else {
            completion(
                AvailabilityCode.deviceNotEligible.rawValue,
                "OS version below iOS 26 / macOS 26"
            )
        }
        #else
        completion(
            AvailabilityCode.deviceNotEligible.rawValue,
            "FoundationModels framework not present in this SDK"
        )
        #endif
    }

    /// Formats `transcript` per `systemPrompt` using the on-device model. A completion-handler
    /// shape is used (not async/await) because Swift structured concurrency does not bridge to
    /// Obj-C/cinterop either (ADR-013) — Kotlin's `IosOnDeviceLlmFormatterProvider` wraps this
    /// call in `suspendCancellableCoroutine`.
    @objc(formatWithTranscript:systemPrompt:completion:)
    public func format(
        transcript: String,
        systemPrompt: String,
        completion: @escaping (String?, Int, String?) -> Void
    ) {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, macOS 26.0, *) {
            Task {
                do {
                    let session = LanguageModelSession(instructions: systemPrompt)
                    let response = try await session.respond(to: transcript)
                    completion(response.content, FormatResultCode.success.rawValue, nil)
                } catch let error as LanguageModelSession.GenerationError {
                    // NOTE: `.guardrailViolation` matched without binding its associated value
                    // (Swift allows this regardless of payload shape) — verify this case name
                    // against the real SDK; per ADR-013 this is the distinct failure class that
                    // Kotlin maps to `LlmResult.Failure.ContentRejected` (Story 5.5a/5.5b).
                    switch error {
                    case .guardrailViolation:
                        completion(
                            nil,
                            FormatResultCode.contentRejected.rawValue,
                            "Content was rejected by on-device safety guardrails"
                        )
                    default:
                        completion(nil, FormatResultCode.otherFailure.rawValue, error.localizedDescription)
                    }
                } catch {
                    completion(nil, FormatResultCode.otherFailure.rawValue, error.localizedDescription)
                }
            }
        } else {
            completion(
                nil,
                FormatResultCode.otherFailure.rawValue,
                "OS version below iOS 26 / macOS 26"
            )
        }
        #else
        completion(
            nil,
            FormatResultCode.otherFailure.rawValue,
            "FoundationModels framework not present in this SDK"
        )
        #endif
    }
}
