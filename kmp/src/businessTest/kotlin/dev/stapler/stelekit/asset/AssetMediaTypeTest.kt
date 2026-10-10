package dev.stapler.stelekit.asset

import kotlin.test.Test
import kotlin.test.assertEquals

class AssetMediaTypeTest {
    @Test fun `image mime maps to IMAGE`() = assertEquals(AssetMediaType.IMAGE, AssetMediaType.fromMimeType("image/jpeg"))
    @Test fun `pdf mime maps to PDF`() = assertEquals(AssetMediaType.PDF, AssetMediaType.fromMimeType("application/pdf"))
    @Test fun `audio mime maps to AUDIO`() = assertEquals(AssetMediaType.AUDIO, AssetMediaType.fromMimeType("audio/mpeg"))
    @Test fun `video mime maps to VIDEO`() = assertEquals(AssetMediaType.VIDEO, AssetMediaType.fromMimeType("video/mp4"))
    @Test fun `application non-pdf maps to DOCUMENT`() = assertEquals(AssetMediaType.DOCUMENT, AssetMediaType.fromMimeType("application/zip"))
    @Test fun `unknown mime maps to FILE`() = assertEquals(AssetMediaType.FILE, AssetMediaType.fromMimeType("x-custom/type"))
}
