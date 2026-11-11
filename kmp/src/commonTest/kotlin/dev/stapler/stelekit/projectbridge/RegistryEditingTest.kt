// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Equal
import dev.stapler.stelekit.fusion.Offset
import dev.stapler.stelekit.fusion.Sum
import dev.stapler.stelekit.sketchimport.KeyError
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryEditingTest {
    private val empty = DimensionRegistry(dimensions = emptyList())

    private fun keys(n: Int) = (1..n).map { "dim.k$it" }

    private fun registryOf(n: Int) = keys(n).fold(empty) { acc, key ->
        RegistryEditing.addKey(acc, key, DimensionKind.LENGTH, "").getOrNull()!!
    }

    @Test
    fun addKey_should_RejectBadDuplicateAndOverlongInput() {
        val one = registryOf(1)
        assertEquals(EditError.BadKey(KeyError.Blank), RegistryEditing.addKey(one, " ", DimensionKind.LENGTH, "").leftOrNull())
        assertEquals(EditError.BadKey(KeyError.Duplicate("dim.k1")), RegistryEditing.addKey(one, "dim.k1", DimensionKind.LENGTH, "").leftOrNull())
        assertEquals(EditError.BadLabel, RegistryEditing.addKey(one, "x", DimensionKind.LENGTH, "l".repeat(RegistryEditing.MAX_LABEL + 1)).leftOrNull())
        assertEquals("dim.k1", one.entry("dim.k1")!!.label)
    }

    @Test
    fun aliases_should_BeAddedCaseInsensitivelyOnceAndNeverSharedBetweenKeys() {
        val two = registryOf(2)
        val withAlias = RegistryEditing.addAlias(two, "dim.k1", AliasKind.YAML, " niche.width ").getOrNull()!!
        assertEquals(listOf("niche.width"), withAlias.entry("dim.k1")!!.aliases.yaml)
        assertEquals(withAlias, RegistryEditing.addAlias(withAlias, "dim.k1", AliasKind.YAML, "NICHE.WIDTH").getOrNull())
        assertEquals(EditError.DuplicateAlias("dim.k1"), RegistryEditing.addAlias(withAlias, "dim.k2", AliasKind.YAML, "niche.width").leftOrNull())
        assertEquals(EditError.BlankAlias, RegistryEditing.addAlias(withAlias, "dim.k2", AliasKind.PROSE, "  ").leftOrNull())
        val removed = RegistryEditing.removeAlias(withAlias, "dim.k1", AliasKind.YAML, "niche.width").getOrNull()!!
        assertTrue(removed.entry("dim.k1")!!.aliases.yaml.isEmpty())
        assertIs<EditError.UnknownKey>(RegistryEditing.addAlias(two, "nope", AliasKind.PROSE, "x").leftOrNull())
    }

    @Test
    fun aliasKinds_should_EachLandInTheirOwnList() {
        val aliases = AliasKind.entries.fold(registryOf(1)) { acc, kind -> RegistryEditing.addAlias(acc, "dim.k1", kind, kind.name).getOrNull()!! }
        val entry = aliases.entry("dim.k1")!!
        AliasKind.entries.forEach { assertEquals(listOf(it.name), RegistryEditing.aliasesOf(entry, it)) }
    }

    @Test
    fun renameKey_should_FollowTheSpecAndRefuseWhileADerivationReadsIt() {
        val registry = registryOf(3)
        val spec = FusionSpec(listOf(Sum(1, "dim.k1", listOf("dim.k2", "dim.k3"))), listOf(SumCheck("Sum", 1, "dim.k1", listOf("dim.k2", "dim.k3"))), listOf("dim.k2"))
        val renamed = RegistryEditing.renameKey(Edited(registry, spec), "dim.k2", "dim.part").getOrNull()!!
        assertNull(renamed.registry.entry("dim.k2"))
        assertEquals(listOf("dim.part", "dim.k3"), (renamed.spec.constraints.single() as Sum).parts)
        assertEquals(listOf("dim.part"), renamed.spec.zoneOrder)
        assertNull(ConstraintEditing.validate(renamed.spec))

        val derived = registry.copy(dimensions = registry.dimensions + DimensionEntry("dim.d", DimensionKind.LENGTH, "d", derivation = "dim.k1 + dim.k2"))
        assertEquals(EditError.KeyInUse("dim.k2"), RegistryEditing.renameKey(Edited(derived, spec), "dim.k2", "dim.z").leftOrNull())
        assertEquals(EditError.KeyInUse("dim.k2"), RegistryEditing.deleteKey(Edited(derived, FusionSpec()), "dim.k2").leftOrNull())
        assertIs<EditError.BadKey>(RegistryEditing.renameKey(Edited(registry, spec), "dim.k2", "Bad Key").leftOrNull())
    }

    @Test
    fun deleteKey_should_RefuseWhileAConstraintReadsItAndDropItFromTheZoneOrder() {
        val registry = registryOf(3)
        val used = FusionSpec(listOf(Equal(1, "dim.k1", "dim.k2")))
        assertEquals(EditError.KeyInUse("dim.k1"), RegistryEditing.deleteKey(Edited(registry, used), "dim.k1").leftOrNull())
        val zoned = FusionSpec(zoneOrder = listOf("dim.k3", "dim.k1"))
        val deleted = RegistryEditing.deleteKey(Edited(registry, zoned), "dim.k3").getOrNull()!!
        assertEquals(listOf("dim.k1"), deleted.spec.zoneOrder)
        assertEquals(2, deleted.registry.dimensions.size)
    }

    @Test
    fun anyAddThenDeleteSequence_should_KeepTheRegistryValidAndEndEmpty() = runTest {
        checkAll(Arb.int(0..40)) { n ->
            val registry = registryOf(n)
            assertTrue(registry.validate().isRight())
            val cleared = keys(n).fold(Edited(registry, FusionSpec())) { acc, key -> RegistryEditing.deleteKey(acc, key).getOrNull()!! }
            assertEquals(empty, cleared.registry)
        }
    }

    @Test
    fun anyRename_should_PreserveSizeAndKeepEveryConstraintResolvable() = runTest {
        checkAll(Arb.int(3..30), Arb.int(1..1000)) { n, tag ->
            val registry = registryOf(n)
            val spec = ConstraintEditing.add(registry, FusionSpec(), ConstraintTemplate.PartsSum("dim.k1", keys(n).drop(1), "Sum")).getOrNull()!!
            val renamed = RegistryEditing.renameKey(Edited(registry, spec), "dim.k2", "renamed.$tag").getOrNull()!!
            assertEquals(n, renamed.registry.dimensions.size)
            assertNull(ConstraintEditing.validate(renamed.spec))
        }
    }

    @Test
    fun templates_should_MapToTheShippedConstraintsWithFreshIds() {
        val registry = registryOf(3)
        val sum = ConstraintEditing.add(registry, FusionSpec(), ConstraintTemplate.PartsSum("dim.k1", listOf("dim.k2", "dim.k3"), " Zones add up ")).getOrNull()!!
        assertEquals(Sum(1, "dim.k1", listOf("dim.k2", "dim.k3")), sum.constraints.single())
        assertEquals(SumCheck("Zones add up", 1, "dim.k1", listOf("dim.k2", "dim.k3")), sum.checks.single())
        val both = ConstraintEditing.add(registry, sum, ConstraintTemplate.Equality("dim.k2", "dim.k3")).getOrNull()!!
        assertEquals(Equal(2, "dim.k2", "dim.k3"), both.constraints.last())
        val offset = ConstraintEditing.add(registry, both, ConstraintTemplate.OffsetBy("dim.k1", "dim.k2", "3/4")).getOrNull()!!
        assertEquals(Offset(3, "dim.k1", "dim.k2", Rational(3, 4)), offset.constraints.last())
        assertEquals(offset.constraints.map { it.id }, listOf(1, 2, 3))
    }

    @Test
    fun templates_should_RejectEachInvalidShape() {
        val registry = registryOf(3)
        fun add(t: ConstraintTemplate, spec: FusionSpec = FusionSpec()) = ConstraintEditing.add(registry, spec, t).leftOrNull()
        assertEquals(EditError.NeedsParts, add(ConstraintTemplate.PartsSum("dim.k1", emptyList(), "x")))
        assertEquals(EditError.RepeatedKey, add(ConstraintTemplate.PartsSum("dim.k1", listOf("dim.k1"), "x")))
        assertEquals(EditError.RepeatedKey, add(ConstraintTemplate.PartsSum("dim.k1", listOf("dim.k2", "dim.k2"), "x")))
        assertEquals(EditError.BadLabel, add(ConstraintTemplate.PartsSum("dim.k1", listOf("dim.k2"), "a[b]")))
        assertEquals(EditError.RepeatedKey, add(ConstraintTemplate.Equality("dim.k1", "dim.k1")))
        assertEquals(EditError.BadOffset, add(ConstraintTemplate.OffsetBy("dim.k1", "dim.k2", "wide")))
        assertEquals(EditError.UnknownKey("nope"), add(ConstraintTemplate.Equality("dim.k1", "nope")))
        val once = ConstraintEditing.add(registry, FusionSpec(), ConstraintTemplate.Equality("dim.k1", "dim.k2")).getOrNull()!!
        assertEquals(EditError.DuplicateConstraint, add(ConstraintTemplate.Equality("dim.k1", "dim.k2"), once))
    }

    @Test
    fun removeConstraint_should_DropItsDigestCheckToo() {
        val registry = registryOf(2)
        val spec = ConstraintEditing.add(registry, FusionSpec(), ConstraintTemplate.PartsSum("dim.k1", listOf("dim.k2"), "x")).getOrNull()!!
        assertEquals(FusionSpec(), ConstraintEditing.remove(spec, 1))
    }

    @Test
    fun anyDistinctPartsTemplate_should_AddOnceAndValidate() = runTest {
        checkAll(Arb.int(2..40)) { n ->
            val registry = registryOf(n)
            val template = ConstraintTemplate.PartsSum("dim.k1", keys(n).drop(1), "Sum")
            val spec = ConstraintEditing.add(registry, FusionSpec(), template).getOrNull()!!
            assertNull(ConstraintEditing.validate(spec))
            assertEquals(EditError.DuplicateConstraint, ConstraintEditing.add(registry, spec, template).leftOrNull())
            assertEquals(spec, FusionSpecJson.decode(FusionSpecJson.encode(spec)))
        }
    }

    @Test
    fun describe_should_ReadLikeTheEquation() {
        assertEquals("a = b + c", ConstraintEditing.describe(Sum(1, "a", listOf("b", "c"))))
        assertEquals("a = b", ConstraintEditing.describe(Equal(1, "a", "b")))
        assertEquals("a - b = 1/2", ConstraintEditing.describe(Offset(1, "a", "b", Rational(1, 2))))
    }
}

class RegistryEditingExtraTest {
    private val registry = DimensionRegistry(
        dimensions = listOf("a", "b", "c").map { DimensionEntry(it, DimensionKind.LENGTH, it) },
    )

    @Test
    fun setLabelAndSetKind_should_UpdateOrRefuse() {
        assertEquals("Wide", RegistryEditing.setLabel(registry, "a", " Wide ").getOrNull()!!.entry("a")!!.label)
        assertEquals("a", RegistryEditing.setLabel(registry, "a", "  ").getOrNull()!!.entry("a")!!.label)
        assertEquals(EditError.BadLabel, RegistryEditing.setLabel(registry, "a", "l".repeat(RegistryEditing.MAX_LABEL + 1)).leftOrNull())
        assertEquals(DimensionKind.COUNT, RegistryEditing.setKind(registry, "a", DimensionKind.COUNT).getOrNull()!!.entry("a")!!.kind)
        assertIs<EditError.UnknownKey>(RegistryEditing.setKind(registry, "zz", DimensionKind.COUNT).leftOrNull())
    }

    @Test
    fun everyEditError_should_HaveOwnerFacingText() {
        val errors = listOf(
            EditError.BadKey(KeyError.Blank), EditError.BadKey(KeyError.BadFormat), EditError.BadKey(KeyError.TooLong),
            EditError.BadKey(KeyError.Duplicate("a")), EditError.BadKey(KeyError.Unknown("a")), EditError.UnknownKey("a"),
            EditError.KeyInUse("a"), EditError.BlankAlias, EditError.DuplicateAlias("a"), EditError.BadLabel, EditError.NeedsParts,
            EditError.RepeatedKey, EditError.BadOffset, EditError.DuplicateConstraint, EditError.TooMany, EditError.InvalidRegistry,
        )
        val texts = errors.map { it.message() }
        assertEquals(texts.size, texts.distinct().size)
        assertTrue(texts.all { it.isNotBlank() })
    }

    @Test
    fun renameKey_should_FollowEveryConstraintKind() {
        val spec = FusionSpec(
            listOf(
                dev.stapler.stelekit.fusion.Fixed(1, "a", Rational(1)),
                Equal(2, "a", "b"),
                Offset(3, "a", "c", Rational(1, 2)),
            ),
        )
        val renamed = ConstraintEditing.renameKey(spec, "a", "z")
        assertEquals(listOf("z", "z", "z"), renamed.constraints.map { it.dimensions.first() })
        assertEquals("z - c = 1/2", ConstraintEditing.describe(renamed.constraints[2]))
        assertEquals("z = 1", ConstraintEditing.describe(renamed.constraints[0]))
    }

    @Test
    fun duplicateDetection_should_CoverEveryShape() {
        val fixed = dev.stapler.stelekit.fusion.Fixed(1, "a", Rational(1))
        val spec = FusionSpec(listOf(fixed, Offset(2, "a", "b", Rational(3, 4))))
        val again = ConstraintEditing.add(registry, spec, ConstraintTemplate.OffsetBy("a", "b", "3/4"))
        assertEquals(EditError.DuplicateConstraint, again.leftOrNull())
        assertEquals(EditError.TooMany, ConstraintEditing.add(
            registry,
            FusionSpec((1..ConstraintEditing.MAX_CONSTRAINTS).map { Equal(it, "a", "b") }),
            ConstraintTemplate.Equality("b", "c"),
        ).leftOrNull())
        assertEquals(EditError.InvalidRegistry, ConstraintEditing.validate(FusionSpec(checks = listOf(SumCheck("x", 9, "a", listOf("b"))))))
        assertEquals(EditError.DuplicateConstraint, ConstraintEditing.validate(FusionSpec(listOf(Equal(1, "a", "b"), Equal(1, "b", "c")))))
    }
}
