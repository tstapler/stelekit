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

    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:1.23.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
