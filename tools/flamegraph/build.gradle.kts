plugins {
    kotlin("jvm") version "2.3.10"
    application
}

application {
    mainClass.set("FlamegraphKt")
}

// Forward -Pfg.* project properties as JVM system properties so the tool
// can be invoked without shell quoting issues around titles with spaces:
//   ./gradlew -q :tools:flamegraph:run \
//     -Pfg.width=1800 "-Pfg.title=Alloc flamegraph (abc1234)" \
//     -Pfg.colors=mem -Pfg.input=alloc.collapsed -Pfg.output=alloc.svg
tasks.named<JavaExec>("run") {
    project.properties
        .filterKeys { it.startsWith("fg.") }
        .forEach { (k, v) -> if (v is String) systemProperty(k, v) }
}
