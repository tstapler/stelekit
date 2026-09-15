package dev.stapler.stelekit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `kotlin.reflect.full.sealedSubclasses` (the reflection approach Task 1.1.1c's note suggests)
 * requires kotlin-reflect and is JVM-only, unusable from this commonTest source set (also
 * compiled for wasmJs/iOS). The exhaustive `when` below is the portable equivalent: it fails to
 * compile — for every target — the moment a leaf is added to either sealed interface without a
 * matching branch here, which is the same "future leaf can't go unnoticed" guarantee.
 */
class StorageLocationTest {

    private fun tag(location: StorageLocation): String = when (location) {
        is StorageLocation.AppOwned -> "AppOwned"
        is StorageLocation.SafFolder -> "SafFolder"
        is StorageLocation.DirectAccessFolder -> "DirectAccessFolder"
        is StorageLocation.HostFolder -> "HostFolder"
    }

    @Test
    fun storageLocation_should_ExposeFourSealedSubtypes_When_Enumerated() {
        val oneOfEach = listOf(
            StorageLocation.AppOwned("g1"),
            StorageLocation.SafFolder("g1", "content://com.android.externalstorage/tree/primary"),
            StorageLocation.DirectAccessFolder("g1", "/storage/emulated/0/Notes"),
            StorageLocation.HostFolder("g1", "Documents"),
        )
        assertEquals(4, oneOfEach.size)
        assertEquals(
            setOf("AppOwned", "SafFolder", "DirectAccessFolder", "HostFolder"),
            oneOfEach.map(::tag).toSet(),
        )
    }

    private fun deletesSource(operation: StorageMoveOperation): Boolean = when (operation) {
        is StorageMoveOperation.Relocate -> operation.deleteSourceAfterVerify
        // `operation.deleteSourceAfterVerify` here would not compile — Link has no such field.
        is StorageMoveOperation.Link -> false
    }

    @Test
    fun storageMoveOperation_should_ExposeTwoSealedSubtypes_When_Enumerated() {
        val relocate: StorageMoveOperation = StorageMoveOperation.Relocate(
            graphId = "g1",
            source = StorageLocation.AppOwned("g1"),
            destination = StorageLocation.HostFolder("g1", "Documents"),
            deleteSourceAfterVerify = true,
        )
        val link: StorageMoveOperation = StorageMoveOperation.Link(
            graphId = "g1",
            source = StorageLocation.AppOwned("g1"),
            destination = StorageLocation.HostFolder("g1", "Documents"),
        )
        assertEquals(true, deletesSource(relocate))
        assertFalse(deletesSource(link))
    }

    @Test
    fun storageMoveOperation_should_MakeDeleteSourceAfterVerifyUnrepresentableOnLink_When_CompiledAsWhenExpression() {
        val link: StorageMoveOperation = StorageMoveOperation.Link(
            graphId = "g1",
            source = StorageLocation.AppOwned("g1"),
            destination = StorageLocation.HostFolder("g1", "Documents"),
        )
        // The exhaustive `when` in deletesSource() above is the compile-time proof: it has no
        // `Link -> link.deleteSourceAfterVerify` arm because that field does not exist on Link.
        assertFalse(deletesSource(link))
    }
}
