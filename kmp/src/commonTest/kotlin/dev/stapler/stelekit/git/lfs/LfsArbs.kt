package dev.stapler.stelekit.git.lfs

import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map

/** Shared generators for the LFS property tests. */
object LfsArbs {
    private val hexDigits = "0123456789abcdef".toList()

    val oid: Arb<LfsOid> =
        Arb.list(Arb.element(hexDigits), LfsOid.LENGTH..LfsOid.LENGTH).map { LfsOid(it.joinToString("")) }

    val size: Arb<Long> = Arb.long(0L, Long.MAX_VALUE)

    val pointer: Arb<LfsPointer> = Arb.bind(oid, size) { o, s -> LfsPointer(o, s) }

    val pointerBytes: Arb<ByteArray> = pointer.map { LfsPointerCodec.serialize(it) }
}
