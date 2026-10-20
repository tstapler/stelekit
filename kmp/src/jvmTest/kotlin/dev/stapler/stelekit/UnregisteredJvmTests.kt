package dev.stapler.stelekit

/**
 * jvmTest classes exempt from [AllJvmTestsCompletenessTest], which would otherwise fail for any class
 * missing from [AllJvmTests] (`bazel test //kmp:jvm_tests` never runs those). Empty: register new test
 * classes in [AllJvmTests] instead of adding entries.
 */
internal val UNREGISTERED_JVM_TESTS: Set<String> = emptySet()
