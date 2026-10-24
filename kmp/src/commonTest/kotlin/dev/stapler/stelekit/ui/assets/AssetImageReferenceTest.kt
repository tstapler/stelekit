// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.assets

import kotlin.test.Test
import kotlin.test.assertEquals

class AssetImageReferenceTest {
    @Test
    fun assetImageReference_should_PreferGraphRelativeLink_When_PathIsUnderAssets() {
        assertEquals("../assets/a.jpg", assetImageReference("../assets/a.jpg", "/g/assets/a.jpg"))
    }

    @Test
    fun assetImageReference_should_FallBackToFilePath_When_RelativePathIsNotAGraphAsset() {
        assertEquals("saf://tree/x.jpg", assetImageReference("x.jpg", "saf://tree/x.jpg"))
        assertEquals("/photos/x.jpg", assetImageReference("", "/photos/x.jpg"))
    }

    @Test
    fun coilModelFor_should_LeaveReferencesAndUrisUnchanged() {
        listOf("../assets/a.jpg", "saf://t/a.jpg", "content://p/a", "file:///a.jpg", "https://h/a.jpg")
            .forEach { assertEquals(it, coilModelFor(it)) }
    }

    @Test
    fun coilModelFor_should_PrefixFileScheme_When_GivenBareAbsolutePath() {
        assertEquals("file:///g/.stelekit/cache/images/k.thumb.jpg", coilModelFor("/g/.stelekit/cache/images/k.thumb.jpg"))
    }
}
