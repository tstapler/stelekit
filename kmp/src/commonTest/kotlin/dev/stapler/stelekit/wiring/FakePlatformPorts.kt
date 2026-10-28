// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.wiring

import arrow.core.right
import dev.stapler.stelekit.git.GitRepository
import dev.stapler.stelekit.git.testsupport.StubFileSystem
import dev.stapler.stelekit.git.testsupport.StubGitRepository
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label

/** In-memory [Settings] whose writes are observable by a test. */
class FakeSettings : Settings {
    private val store = mutableMapOf<String, String>()
    override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
    override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
    override fun putString(key: String, value: String) { store[key] = value }
    override fun containsKey(key: String) = store.containsKey(key)
}

/** [ImageLabeler] returning fixed labels; the real ML Kit labeler is exercised by instrumented tests. */
class FakeImageLabeler(private val labels: List<Label> = emptyList()) : ImageLabeler {
    override suspend fun labelImage(imageBytes: ByteArray) = labels.right()
}

/**
 * The fake platform ports `WiredAppFixture` (androidUnitTest) and `DesktopWiredAppFixture` (jvmTest)
 * hand to the real `GraphContent*Setup` stack (plan Task 1.3a, `FakePlatformPorts`). Lives in
 * `commonTest` because that is the one source set both fixtures reach.
 */
class FakePlatformPorts(
    val settings: Settings = FakeSettings(),
    val fileSystem: FileSystem = StubFileSystem(),
    val imageLabeler: ImageLabeler = FakeImageLabeler(),
    val gitRepository: GitRepository = object : StubGitRepository() {},
)
