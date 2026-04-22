plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // compileOnly: detekt-api pulls in kotlin-compiler-embeddable which must not
    // appear on the build classpath alongside the Kotlin Gradle plugin.
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:1.23.7")
}
