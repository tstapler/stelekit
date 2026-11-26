// buildSrc is its own build and does not see the root settings' repositories.
// Google's mirror goes first because Gradle fails (rather than falls through) on Central's 429s.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}
