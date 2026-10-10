// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
//
// Story 5.1 placeholder screen — Task 5.1c's manual verification gate is "confirm the bare
// project builds and runs (e.g. shows a placeholder screen) on a contributor's Mac via Xcode."
// This view is that placeholder; it deliberately does nothing else yet.

import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "hammer.fill")
                .imageScale(.large)
            Text("SteleKit iOS — scaffold")
                .font(.headline)
            Text("Story 5.1 placeholder screen. See iosApp/README.md.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
