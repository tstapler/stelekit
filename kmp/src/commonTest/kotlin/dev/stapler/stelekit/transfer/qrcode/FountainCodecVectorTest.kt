package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.transfer.Crc32
import dev.stapler.stelekit.transfer.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replays official BlockchainCommons/bc-ur reference vectors (`test/test.cpp`, BSD-2-Clause Plus
 * Patent License) bit-for-bit against this codec's ported LT fountain math (ADR-001). See
 * `kmp/src/commonTest/resources/bcur-vectors/README.md` for exact provenance and why the vectors
 * are embedded here as Kotlin constants rather than loaded from that directory at test time.
 *
 * Every helper below (`makeMessage`, `nextMod100`) is built ONLY from this codec's own production
 * types (`Xoshiro256`, `chooseDegree`, `chooseFragments`, `findNominalFragmentLength`,
 * `partitionMessage`, `shuffled`, `RandomSampler`) — there is no separate "test implementation" of
 * the algorithm that could drift from what ships.
 */
class FountainCodecVectorTest {

    private fun makeMessage(len: Int, seed: String = "Wolf"): ByteArray {
        val rng = Xoshiro256.fromString(seed)
        return ByteArray(len) { rng.nextInt(0, 255).toByte() }
    }

    private fun Xoshiro256.nextMod100(): Long = (next().toULong() % 100u).toLong()

    @Test
    fun xoshiro256_should_MatchBcUrReferenceStream_When_SeededFromString() {
        val rng = Xoshiro256.fromString("Wolf")
        val numbers = List(100) { rng.nextMod100() }

        val expected = listOf(
            42L, 81, 85, 8, 82, 84, 76, 73, 70, 88, 2, 74, 40, 48, 77, 54, 88, 7, 5, 88, 37, 25, 82, 13, 69, 59, 30,
            39, 11, 82, 19, 99, 45, 87, 30, 15, 32, 22, 89, 44, 92, 77, 29, 78, 4, 92, 44, 68, 92, 69, 1, 42, 89, 50,
            37, 84, 63, 34, 32, 3, 17, 62, 40, 98, 82, 89, 24, 43, 85, 39, 15, 3, 99, 29, 20, 42, 27, 10, 85, 66, 50,
            35, 69, 70, 70, 74, 30, 13, 72, 54, 11, 5, 70, 55, 91, 52, 10, 43, 43, 52,
        )
        assertEquals(expected, numbers)
    }

    @Test
    fun xoshiro256_should_MatchBcUrReferenceStream_When_SeededFromChecksum() {
        val checksum = Crc32.of("Wolf".encodeToByteArray())
        val rng = Xoshiro256.fromChecksum(checksum)
        val numbers = List(100) { rng.nextMod100() }

        val expected = listOf(
            88L, 44, 94, 74, 0, 99, 7, 77, 68, 35, 47, 78, 19, 21, 50, 15, 42, 36, 91, 11, 85, 39, 64, 22, 57, 11, 25,
            12, 1, 91, 17, 75, 29, 47, 88, 11, 68, 58, 27, 65, 21, 54, 47, 54, 73, 83, 23, 58, 75, 27, 26, 15, 60, 36,
            30, 21, 55, 57, 77, 76, 75, 47, 53, 76, 9, 91, 14, 69, 3, 95, 11, 73, 20, 99, 68, 61, 3, 98, 36, 98, 56,
            65, 14, 80, 74, 57, 63, 68, 51, 56, 24, 39, 53, 80, 57, 51, 81, 3, 1, 30,
        )
        assertEquals(expected, numbers)
    }

    @Test
    fun findNominalFragmentLength_should_MatchBcUrReferenceValues_When_ReplayingVector() {
        assertEquals(1764, findNominalFragmentLength(12345, 1005, 1955))
        assertEquals(12345, findNominalFragmentLength(12345, 1005, 30000))
    }

    @Test
    fun partitionMessage_should_MatchBcUrReferenceFragments_When_Replaying1024ByteMessage() {
        val message = makeMessage(1024)
        val fragmentLen = findNominalFragmentLength(message.size, 10, 100)
        val fragments = partitionMessage(message, fragmentLen)

        val expectedHex = listOf(
            "916ec65cf77cadf55cd7f9cda1a1030026ddd42e905b77adc36e4f2d3ccba44f7f04f2de44f42d84c374a0e149136f25b01852545961d55f7f7a8cde6d0e2ec43f3b2dcb644a2209e8c9e34af5c4747984a5e873c9cf5f965e25ee29039f",
            "df8ca74f1c769fc07eb7ebaec46e0695aea6cbd60b3ec4bbff1b9ffe8a9e7240129377b9d3711ed38d412fbb4442256f1e6f595e0fc57fed451fb0a0101fb76b1fb1e1b88cfdfdaa946294a47de8fff173f021c0e6f65b05c0a494e50791",
            "270a0050a73ae69b6725505a2ec8a5791457c9876dd34aadd192a53aa0dc66b556c0c215c7ceb8248b717c22951e65305b56a3706e3e86eb01c803bbf915d80edcd64d4d41977fa6f78dc07eecd072aae5bc8a852397e06034dba6a0b570",
            "797c3a89b16673c94838d884923b8186ee2db5c98407cab15e13678d072b43e406ad49477c2e45e85e52ca82a94f6df7bbbe7afbed3a3a830029f29090f25217e48d1f42993a640a67916aa7480177354cc7440215ae41e4d02eae9a1912",
            "33a6d4922a792c1b7244aa879fefdb4628dc8b0923568869a983b8c661ffab9b2ed2c149e38d41fba090b94155adbed32f8b18142ff0d7de4eeef2b04adf26f2456b46775c6c20b37602df7da179e2332feba8329bbb8d727a138b4ba7a5",
            "03215eda2ef1e953d89383a382c11d3f2cad37a4ee59a91236a3e56dcf89f6ac81dd4159989c317bd649d9cbc617f73fe10033bd288c60977481a09b343d3f676070e67da757b86de27bfca74392bac2996f7822a7d8f71a489ec6180390",
            "089ea80a8fcd6526413ec6c9a339115f111d78ef21d456660aa85f790910ffa2dc58d6a5b93705caef1091474938bd312427021ad1eeafbd19e0d916ddb111fabd8dcab5ad6a6ec3a9c6973809580cb2c164e26686b5b98cfb017a337968",
            "c7daaa14ae5152a067277b1b3902677d979f8e39cc2aafb3bc06fcf69160a853e6869dcc09a11b5009f91e6b89e5b927ab1527a735660faa6012b420dd926d940d742be6a64fb01cdc0cff9faa323f02ba41436871a0eab851e7f5782d10",
            "fbefde2a7e9ae9dc1e5c2c48f74f6c824ce9ef3c89f68800d44587bedc4ab417cfb3e7447d90e1e417e6e05d30e87239d3a5d1d45993d4461e60a0192831640aa32dedde185a371ded2ae15f8a93dba8809482ce49225daadfbb0fec629e",
            "23880789bdf9ed73be57fa84d555134630e8d0f7df48349f29869a477c13ccca9cd555ac42ad7f568416c3d61959d0ed568b2b81c7771e9088ad7fd55fd4386bafbf5a528c30f107139249357368ffa980de2c76ddd9ce4191376be0e6b5",
            "170010067e2e75ebe2d2904aeb1f89d5dc98cd4a6f2faaa8be6d03354c990fd895a97feb54668473e9d942bb99e196d897e8f1b01625cf48a7b78d249bb4985c065aa8cd1402ed2ba1b6f908f63dcd84b66425df00000000000000000000",
        )
        assertEquals(expectedHex, fragments.map { it.toHexTest() })
    }

    @Test
    fun chooseDegree_should_MatchBcUrReferenceDegrees_When_ReplayingVector() {
        val message = makeMessage(1024)
        val fragmentLen = findNominalFragmentLength(message.size, 10, 100)
        val fragmentCount = partitionMessage(message, fragmentLen).size

        val degrees = (1..200).map { nonce ->
            val partRng = Xoshiro256.fromString("Wolf-$nonce")
            chooseDegree(fragmentCount, partRng)
        }

        val expected = listOf(
            11, 3, 6, 5, 2, 1, 2, 11, 1, 3, 9, 10, 10, 4, 2, 1, 1, 2, 1, 1, 5, 2, 4, 10, 3, 2, 1, 1, 3, 11, 2, 6, 2,
            9, 9, 2, 6, 7, 2, 5, 2, 4, 3, 1, 6, 11, 2, 11, 3, 1, 6, 3, 1, 4, 5, 3, 6, 1, 1, 3, 1, 2, 2, 1, 4, 5, 1, 1,
            9, 1, 1, 6, 4, 1, 5, 1, 2, 2, 3, 1, 1, 5, 2, 6, 1, 7, 11, 1, 8, 1, 5, 1, 1, 2, 2, 6, 4, 10, 1, 2, 5, 5, 5,
            1, 1, 4, 1, 1, 1, 3, 5, 5, 5, 1, 4, 3, 3, 5, 1, 11, 3, 2, 8, 1, 2, 1, 1, 4, 5, 2, 1, 1, 1, 5, 6, 11, 10,
            7, 4, 7, 1, 5, 3, 1, 1, 9, 1, 2, 5, 5, 2, 2, 3, 10, 1, 3, 2, 3, 3, 1, 1, 2, 1, 3, 2, 2, 1, 3, 8, 4, 1, 11,
            6, 3, 1, 1, 1, 1, 1, 3, 1, 2, 1, 10, 1, 1, 8, 2, 7, 1, 2, 1, 9, 2, 10, 2, 1, 3, 4, 10,
        )
        assertEquals(expected, degrees)
    }

    @Test
    fun chooseFragments_should_MatchBcUrReferenceIndexes_When_ReplayingVector() {
        val message = makeMessage(1024)
        val checksum = Crc32.of(message)
        val fragmentLen = findNominalFragmentLength(message.size, 10, 100)
        val fragmentCount = partitionMessage(message, fragmentLen).size

        val indexes = (1..30).map { seqNum ->
            chooseFragments(seqNum, fragmentCount, checksum).sorted()
        }

        val expected = listOf(
            listOf(0), listOf(1), listOf(2), listOf(3), listOf(4), listOf(5), listOf(6), listOf(7), listOf(8),
            listOf(9), listOf(10), listOf(9), listOf(2, 5, 6, 8, 9, 10), listOf(8), listOf(1, 5), listOf(1),
            listOf(0, 2, 4, 5, 8, 10), listOf(5), listOf(2), listOf(2), listOf(0, 1, 3, 4, 5, 7, 9, 10),
            listOf(0, 1, 2, 3, 5, 6, 8, 9, 10), listOf(0, 2, 4, 5, 7, 8, 9, 10), listOf(3, 5), listOf(4),
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), listOf(0, 1, 3, 4, 5, 6, 7, 9, 10), listOf(6), listOf(5, 6),
            listOf(7),
        )
        assertEquals(expected, indexes)
    }

    @Test
    fun fountainEncoder_should_MatchPublishedBcUrReferenceFragmentBytes_When_ReplayingVectors() {
        val message = makeMessage(256)
        val encoder = FountainEncoder(TransferId(0), message, maxFragmentBytes = 30).getOrNull()!!

        val parts = encoder.parts().take(20).toList()
        assertEquals(9, encoder.seqLen)

        val expectedFragmentHex = listOf(
            "916ec65cf77cadf55cd7f9cda1a1030026ddd42e905b77adc36e4f2d3c",
            "cba44f7f04f2de44f42d84c374a0e149136f25b01852545961d55f7f7a",
            "8cde6d0e2ec43f3b2dcb644a2209e8c9e34af5c4747984a5e873c9cf5f",
            "965e25ee29039fdf8ca74f1c769fc07eb7ebaec46e0695aea6cbd60b3e",
            "c4bbff1b9ffe8a9e7240129377b9d3711ed38d412fbb4442256f1e6f59",
            "5e0fc57fed451fb0a0101fb76b1fb1e1b88cfdfdaa946294a47de8fff1",
            "73f021c0e6f65b05c0a494e50791270a0050a73ae69b6725505a2ec8a5",
            "791457c9876dd34aadd192a53aa0dc66b556c0c215c7ceb8248b717c22",
            "951e65305b56a3706e3e86eb01c803bbf915d80edcd64d4d0000000000",
            "330f0f33a05eead4f331df229871bee733b50de71afd2e5a79f196de09",
            "3b205ce5e52d8c24a52cffa34c564fa1af3fdffcd349dc4258ee4ee828",
            "dd7bf725ea6c16d531b5f03254783803048ca08b87148daacd1cd7a006",
            "760be7ad1c6187902bbc04f539b9ee5eb8ea6833222edea36031306c01",
            "5bf4031217d2c3254b088fa7553778b5003632f46e21db129416f65b55",
            "73f021c0e6f65b05c0a494e50791270a0050a73ae69b6725505a2ec8a5",
            "b8546ebfe2048541348910267331c643133f828afec9337c318f71b7df",
            "23dedeea74e3a0fb052befabefa13e2f80e4315c9dceed4c8630612e64",
            "d01a8daee769ce34b6b35d3ca0005302724abddae405bdb419c0a6b208",
            "3171c5dc365766eff25ae47c6f10e7de48cfb8474e050e5fe997a6dc24",
            "e055c2433562184fa71b4be94f262e200f01c6f74c284b0dc6fae6673f",
        )
        // bc-ur's seqNum is 1-based (part 0 in this list == seqNum:1 in the upstream vector); this
        // codec's ChunkIndex is 0-based (ADR-001), so index n here == upstream seqNum n+1.
        assertEquals(expectedFragmentHex, parts.map { it.fragment.toHexTest() })
        assertEquals((0..19).toList(), parts.map { it.chunkIndex.value })

        // Feed the reference-matching parts into this codec's own decoder and confirm it
        // reassembles the exact original payload — the encoder/decoder round trip, not just the
        // encoder's byte output, matches the reference algorithm end to end.
        val buffer = ChunkBuffer(maxPayloadBytes = 65536)
        for (part in parts) {
            buffer.accept(part)
            if (buffer.isComplete()) break
        }
        assertEquals(message.decodeToString(), buffer.reassemble().getOrNull()?.markdown)
    }

    private fun ByteArray.toHexTest(): String {
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }
}
