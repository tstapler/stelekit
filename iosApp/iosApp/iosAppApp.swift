// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
//
// Story 5.1 (Epic 5, llm-service): minimal Xcode app-target scaffold. This is not the real
// SteleKit iOS app — it exists only so Stories 5.2-5.5 have a real Xcode project to attach
// PingShim/FoundationModelsShim targets and the kmp Kotlin/Native framework to, and so
// Task 5.1c's manual verification ("bare project builds and shows a placeholder screen") has
// something to build. See iosApp/README.md for the manual build/run workflow and the current
// state of what is/isn't wired up yet.

import SwiftUI

@main
struct iosAppApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
