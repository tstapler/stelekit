// Root build file for Logseq Kotlin Multiplatform migration
// This allows gradual migration from ClojureScript to Kotlin Multiplatform

plugins {
    // Note: Most build logic is handled by individual modules
    // This root file mainly coordinates multi-module builds
}

// Convenience task: ./gradlew installAndroid
tasks.register("installAndroid") {
    dependsOn(":androidApp:installDebug")
    group = "install"
    description = "Build and install the SteleKit Android app on a connected device"
}

