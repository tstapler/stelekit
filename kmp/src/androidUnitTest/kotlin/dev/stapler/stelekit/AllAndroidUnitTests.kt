package dev.stapler.stelekit

import dev.stapler.stelekit.db.WalConfiguredCallbackTest
import dev.stapler.stelekit.platform.LegacyPathValidationTest
import dev.stapler.stelekit.platform.PlatformFileSystemPickerTest
import dev.stapler.stelekit.platform.PlatformFileSystemSafTest
import dev.stapler.stelekit.platform.PlatformFileSystemUriLogicTest
import dev.stapler.stelekit.platform.SafPermissionPersistenceTest
import dev.stapler.stelekit.platform.SafPermissionStateTransitionTest
import dev.stapler.stelekit.platform.ShadowFileCacheTest
import dev.stapler.stelekit.platform.UpgradePathTest
import dev.stapler.stelekit.platform.security.AndroidCredentialStoreCommitTest
import dev.stapler.stelekit.platform.security.AndroidCredentialStoreFailsLoudTest
import dev.stapler.stelekit.platform.sensor.ExifOrientationFixerTest
import dev.stapler.stelekit.ui.LoadingStateTest
import dev.stapler.stelekit.voice.AndroidSpeechRecognizerPermissionTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    WalConfiguredCallbackTest::class,
    LegacyPathValidationTest::class,
    PlatformFileSystemPickerTest::class,
    PlatformFileSystemSafTest::class,
    PlatformFileSystemUriLogicTest::class,
    SafPermissionPersistenceTest::class,
    SafPermissionStateTransitionTest::class,
    ShadowFileCacheTest::class,
    UpgradePathTest::class,
    AndroidCredentialStoreCommitTest::class,
    AndroidCredentialStoreFailsLoudTest::class,
    ExifOrientationFixerTest::class,
    LoadingStateTest::class,
    AndroidSpeechRecognizerPermissionTest::class,
)
class AllAndroidUnitTests
